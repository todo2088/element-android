/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package im.vector.matrix.android.internal.crypto.verification

import im.vector.matrix.android.api.auth.data.Credentials
import im.vector.matrix.android.api.session.crypto.sas.CancelCode
import im.vector.matrix.android.api.session.crypto.sas.OutgoingSasVerificationRequest
import im.vector.matrix.android.api.session.crypto.sas.SasVerificationTxState
import im.vector.matrix.android.api.session.crypto.sas.VerificationMethod
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.internal.crypto.actions.SetDeviceVerificationAction
import im.vector.matrix.android.internal.crypto.model.rest.toValue
import im.vector.matrix.android.internal.crypto.store.IMXCryptoStore
import timber.log.Timber

internal class DefaultOutgoingSASVerificationRequest(
        setDeviceVerificationAction: SetDeviceVerificationAction,
        credentials: Credentials,
        cryptoStore: IMXCryptoStore,
        deviceFingerprint: String,
        transactionId: String,
        otherUserId: String,
        otherDeviceId: String
) : SASVerificationTransaction(
        setDeviceVerificationAction,
        credentials,
        cryptoStore,
        deviceFingerprint,
        transactionId,
        otherUserId,
        otherDeviceId,
        isIncoming = false),
        OutgoingSasVerificationRequest {

    override val uxState: OutgoingSasVerificationRequest.UxState
        get() {
            return when (state) {
                SasVerificationTxState.None           -> OutgoingSasVerificationRequest.UxState.WAIT_FOR_START
                SasVerificationTxState.SendingStart,
                SasVerificationTxState.Started,
                SasVerificationTxState.OnAccepted,
                SasVerificationTxState.SendingKey,
                SasVerificationTxState.KeySent,
                SasVerificationTxState.OnKeyReceived  -> OutgoingSasVerificationRequest.UxState.WAIT_FOR_KEY_AGREEMENT
                SasVerificationTxState.ShortCodeReady -> OutgoingSasVerificationRequest.UxState.SHOW_SAS
                SasVerificationTxState.ShortCodeAccepted,
                SasVerificationTxState.SendingMac,
                SasVerificationTxState.MacSent,
                SasVerificationTxState.Verifying      -> OutgoingSasVerificationRequest.UxState.WAIT_FOR_VERIFICATION
                SasVerificationTxState.Verified       -> OutgoingSasVerificationRequest.UxState.VERIFIED
                SasVerificationTxState.OnCancelled    -> OutgoingSasVerificationRequest.UxState.CANCELLED_BY_ME
                SasVerificationTxState.Cancelled      -> OutgoingSasVerificationRequest.UxState.CANCELLED_BY_OTHER
                else                                  -> OutgoingSasVerificationRequest.UxState.UNKNOWN
            }
        }

    override fun onVerificationStart(startReq: VerificationInfoStart) {
        Timber.e("## SAS O: onVerificationStart - unexpected id:$transactionId")
        cancel(CancelCode.UnexpectedMessage)
    }

    fun start(method: VerificationMethod) {
        if (state != SasVerificationTxState.None) {
            Timber.e("## SAS O: start verification from invalid state")
            // should I cancel??
            throw IllegalStateException("Interactive Key verification already started")
        }

        val startMessage = transport.createStart(
                credentials.deviceId ?: "",
                method.toValue(),
                transactionId,
                KNOWN_AGREEMENT_PROTOCOLS,
                KNOWN_HASHES,
                KNOWN_MACS,
                KNOWN_SHORT_CODES
        )

        startReq = startMessage
        state = SasVerificationTxState.SendingStart

        sendToOther(
                EventType.KEY_VERIFICATION_START,
                startMessage,
                SasVerificationTxState.Started,
                CancelCode.User,
                null
        )
    }

//    fun request() {
//        if (state != SasVerificationTxState.None) {
//            Timber.e("## start verification from invalid state")
//            // should I cancel??
//            throw IllegalStateException("Interactive Key verification already started")
//        }
//
//        val requestMessage = KeyVerificationRequest(
//                fromDevice = session.sessionParams.credentials.deviceId ?: "",
//                methods = listOf(KeyVerificationStart.VERIF_METHOD_SAS),
//                timestamp = System.currentTimeMillis().toInt(),
//                transactionID = transactionId
//        )
//
//        sendToOther(
//                EventType.KEY_VERIFICATION_REQUEST,
//                requestMessage,
//                SasVerificationTxState.None,
//                CancelCode.User,
//                null
//        )
//    }

    override fun onVerificationAccept(accept: VerificationInfoAccept) {
        Timber.v("## SAS O: onVerificationAccept id:$transactionId")
        if (state != SasVerificationTxState.Started) {
            Timber.e("## SAS O: received accept request from invalid state $state")
            cancel(CancelCode.UnexpectedMessage)
            return
        }
        // Check that the agreement is correct
        if (!KNOWN_AGREEMENT_PROTOCOLS.contains(accept.keyAgreementProtocol)
                || !KNOWN_HASHES.contains(accept.hash)
                || !KNOWN_MACS.contains(accept.messageAuthenticationCode)
                || accept.shortAuthenticationStrings!!.intersect(KNOWN_SHORT_CODES).isEmpty()) {
            Timber.e("## SAS O: received accept request from invalid state")
            cancel(CancelCode.UnknownMethod)
            return
        }

        // Upon receipt of the m.key.verification.accept message from Bob’s device,
        // Alice’s device stores the commitment value for later use.
        accepted = accept
        state = SasVerificationTxState.OnAccepted

        //  Alice’s device creates an ephemeral Curve25519 key pair (dA,QA),
        // and replies with a to_device message with type set to “m.key.verification.key”, sending Alice’s public key QA
        val pubKey = getSAS().publicKey

        val keyToDevice = transport.createKey(transactionId, pubKey)
        // we need to send this to other device now
        state = SasVerificationTxState.SendingKey
        sendToOther(EventType.KEY_VERIFICATION_KEY, keyToDevice, SasVerificationTxState.KeySent, CancelCode.User) {
            // It is possible that we receive the next event before this one :/, in this case we should keep state
            if (state == SasVerificationTxState.SendingKey) {
                state = SasVerificationTxState.KeySent
            }
        }
    }

    override fun onKeyVerificationKey(userId: String, vKey: VerificationInfoKey) {
        Timber.v("## SAS O: onKeyVerificationKey id:$transactionId")
        if (state != SasVerificationTxState.SendingKey && state != SasVerificationTxState.KeySent) {
            Timber.e("## received key from invalid state $state")
            cancel(CancelCode.UnexpectedMessage)
            return
        }

        otherKey = vKey.key
        // Upon receipt of the m.key.verification.key message from Bob’s device,
        // Alice’s device checks that the commitment property from the Bob’s m.key.verification.accept
        // message is the same as the expected value based on the value of the key property received
        // in Bob’s m.key.verification.key and the content of Alice’s m.key.verification.start message.

        // check commitment
        val concat = vKey.key + startReq!!.toCanonicalJson()
        val otherCommitment = hashUsingAgreedHashMethod(concat) ?: ""

        if (accepted!!.commitment.equals(otherCommitment)) {
            getSAS().setTheirPublicKey(otherKey)
            // (Note: In all of the following HKDF is as defined in RFC 5869, and uses the previously agreed-on hash function as the hash function,
            // the shared secret as the input keying material, no salt, and with the input parameter set to the concatenation of:
            // - the string “MATRIX_KEY_VERIFICATION_SAS”,
            // - the Matrix ID of the user who sent the m.key.verification.start message,
            // - the device ID of the device that sent the m.key.verification.start message,
            // - the Matrix ID of the user who sent the m.key.verification.accept message,
            // - he device ID of the device that sent the m.key.verification.accept message
            // - the transaction ID.
            val sasInfo = "MATRIX_KEY_VERIFICATION_SAS" +
                    "${credentials.userId}${credentials.deviceId}" +
                    "$otherUserId$otherDeviceId" +
                    transactionId
            // decimal: generate five bytes by using HKDF.
            // emoji: generate six bytes by using HKDF.
            shortCodeBytes = getSAS().generateShortCode(sasInfo, 6)
            state = SasVerificationTxState.ShortCodeReady
        } else {
            // bad commitement
            cancel(CancelCode.MismatchedCommitment)
        }
    }

    override fun onKeyVerificationMac(vKey: VerificationInfoMac) {
        Timber.v("## SAS O: onKeyVerificationMac id:$transactionId")
        if (state != SasVerificationTxState.OnKeyReceived
                && state != SasVerificationTxState.ShortCodeReady
                && state != SasVerificationTxState.ShortCodeAccepted
                && state != SasVerificationTxState.SendingMac
                && state != SasVerificationTxState.MacSent) {
            Timber.e("## SAS O: received key from invalid state $state")
            cancel(CancelCode.UnexpectedMessage)
            return
        }

        theirMac = vKey

        // Do I have my Mac?
        if (myMac != null) {
            // I can check
            verifyMacs()
        }
        // Wait for ShortCode Accepted
    }
}
