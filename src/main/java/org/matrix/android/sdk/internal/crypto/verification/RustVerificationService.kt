/*
 * Copyright 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.crypto.verification

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import org.matrix.android.sdk.api.session.crypto.verification.PendingVerificationRequest
import org.matrix.android.sdk.api.session.crypto.verification.VerificationMethod
import org.matrix.android.sdk.api.session.crypto.verification.VerificationService
import org.matrix.android.sdk.api.session.crypto.verification.VerificationTransaction
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.model.message.MessageRelationContent
import org.matrix.android.sdk.api.session.room.model.message.MessageType
import org.matrix.android.sdk.internal.crypto.OlmMachineProvider
import org.matrix.android.sdk.internal.crypto.OwnUserIdentity
import org.matrix.android.sdk.internal.crypto.SasVerification
import org.matrix.android.sdk.internal.crypto.UserIdentity
import org.matrix.android.sdk.internal.crypto.model.rest.VERIFICATION_METHOD_QR_CODE_SCAN
import org.matrix.android.sdk.internal.crypto.model.rest.VERIFICATION_METHOD_QR_CODE_SHOW
import org.matrix.android.sdk.internal.crypto.model.rest.VERIFICATION_METHOD_RECIPROCATE
import org.matrix.android.sdk.internal.crypto.model.rest.toValue
import org.matrix.android.sdk.internal.session.SessionScope
import timber.log.Timber
import javax.inject.Inject

/** A helper class to deserialize to-device `m.key.verification.*` events to fetch the transaction id out */
@JsonClass(generateAdapter = true)
internal data class ToDeviceVerificationEvent(
        @Json(name = "sender") val sender: String?,
        @Json(name = "transaction_id") val transactionId: String
)

/** Helper method to fetch the unique ID of the verification event */
private fun getFlowId(event: Event): String? {
    return if (event.eventId != null) {
        val relatesTo = event.content.toModel<MessageRelationContent>()?.relatesTo
        relatesTo?.eventId
    } else {
        val content = event.getClearContent().toModel<ToDeviceVerificationEvent>() ?: return null
        content.transactionId
    }
}

/** Convert a list of VerificationMethod into a list of strings that can be passed to the Rust side */
internal fun prepareMethods(methods: List<VerificationMethod>): List<String> {
    val stringMethods: MutableList<String> = methods.map { it.toValue() }.toMutableList()

    if (stringMethods.contains(VERIFICATION_METHOD_QR_CODE_SHOW) ||
            stringMethods.contains(VERIFICATION_METHOD_QR_CODE_SCAN)) {
        stringMethods.add(VERIFICATION_METHOD_RECIPROCATE)
    }

    return stringMethods
}

@SessionScope
internal class RustVerificationService @Inject constructor(private val olmMachineProvider: OlmMachineProvider) : VerificationService {

    val olmMachine by lazy {
        olmMachineProvider.olmMachine
    }

    private val dispatcher = UpdateDispatcher(olmMachine.verificationListeners)

    /**
     *
     * All verification related events should be forwarded through this method to
     * the verification service.
     *
     * If the verification event is not encrypted it should be provided to the olmMachine.
     * Otherwise events are at this point already handled by the rust-sdk through the receival
     * of the to-device events and the decryption of room events. In this case this method mainly just
     * fetches the appropriate rust object that will be created or updated by the event and
     * dispatches updates to our listeners.
     */
    internal suspend fun onEvent(roomId: String?, event: Event) {
        if (roomId != null && !event.isEncrypted()) {
            olmMachine.receiveUnencryptedVerificationEvent(roomId, event)
        }
        when (event.getClearType()) {
            EventType.KEY_VERIFICATION_REQUEST -> onRequest(event, fromRoomMessage = false)
            EventType.KEY_VERIFICATION_START   -> onStart(event)
            EventType.KEY_VERIFICATION_READY,
            EventType.KEY_VERIFICATION_ACCEPT,
            EventType.KEY_VERIFICATION_KEY,
            EventType.KEY_VERIFICATION_MAC,
            EventType.KEY_VERIFICATION_CANCEL,
            EventType.KEY_VERIFICATION_DONE    -> onUpdate(event)
            EventType.MESSAGE                  -> onRoomMessage(event)
            else                               -> Unit
        }
    }

    private fun onRoomMessage(event: Event) {
        val messageContent = event.getClearContent()?.toModel<MessageContent>() ?: return
        if (messageContent.msgType == MessageType.MSGTYPE_VERIFICATION_REQUEST) {
            onRequest(event, fromRoomMessage = true)
        }
    }

    /** Dispatch updates after a verification event has been received */
    private fun onUpdate(event: Event) {
        val sender = event.senderId ?: return
        val flowId = getFlowId(event) ?: return

        olmMachine.getVerificationRequest(sender, flowId)?.dispatchRequestUpdated()
        val verification = getExistingTransaction(sender, flowId) ?: return
        dispatcher.dispatchTxUpdated(verification)
    }

    /** Check if the start event created new verification objects and dispatch updates */
    private suspend fun onStart(event: Event) {
        val sender = event.senderId ?: return
        val flowId = getFlowId(event) ?: return

        val verification = getExistingTransaction(sender, flowId) ?: return
        val request = olmMachine.getVerificationRequest(sender, flowId)

        if (request != null && request.isReady()) {
            // If this is a SAS verification originating from a `m.key.verification.request`
            // event, we auto-accept here considering that we either initiated the request or
            // accepted the request. If it's a QR code verification, just dispatch an update.
            if (verification is SasVerification) {
                // accept() will dispatch an update, no need to do it twice.
                Timber.d("## Verification: Auto accepting SAS verification with $sender")
                verification.accept()
            } else {
                dispatcher.dispatchTxUpdated(verification)
            }
        } else {
            // This didn't originate from a request, so tell our listeners that
            // this is a new verification.
            dispatcher.dispatchTxAdded(verification)
            // The IncomingVerificationRequestHandler seems to only listen to updates
            // so let's trigger an update after the addition as well.
            dispatcher.dispatchTxUpdated(verification)
        }
    }

    /** Check if the request event created a nev verification request object and dispatch that it dis so */
    private fun onRequest(event: Event, fromRoomMessage: Boolean) {
        val flowId = if (fromRoomMessage) {
            event.eventId
        } else {
            event.getClearContent().toModel<ToDeviceVerificationEvent>()?.transactionId
        } ?: return
        val sender = event.senderId ?: return
        val request = getExistingVerificationRequest(sender, flowId) ?: return

        dispatcher.dispatchRequestAdded(request)
    }

    override fun addListener(listener: VerificationService.Listener) {
        dispatcher.addListener(listener)
    }

    override fun removeListener(listener: VerificationService.Listener) {
        dispatcher.removeListener(listener)
    }

    override suspend fun markedLocallyAsManuallyVerified(userId: String, deviceID: String) {
        olmMachine.getDevice(userId, deviceID)?.markAsTrusted()
    }

    override fun getExistingTransaction(
            otherUserId: String,
            tid: String,
    ): VerificationTransaction? {
        return olmMachine.getVerification(otherUserId, tid)
    }

    override fun getExistingVerificationRequests(
            otherUserId: String
    ): List<PendingVerificationRequest> {
        return olmMachine.getVerificationRequests(otherUserId).map {
            it.toPendingVerificationRequest()
        }
    }

    override fun getExistingVerificationRequest(
            otherUserId: String,
            tid: String?
    ): PendingVerificationRequest? {
        return if (tid != null) {
            olmMachine.getVerificationRequest(otherUserId, tid)?.toPendingVerificationRequest()
        } else {
            null
        }
    }

    override fun getExistingVerificationRequestInRoom(
            roomId: String,
            tid: String?
    ): PendingVerificationRequest? {
        // This is only used in `RoomDetailViewModel` to resume the verification.
        //
        // Is this actually useful? SAS and QR code verifications ephemeral nature
        // due to the usage of ephemeral secrets. In the case of SAS verification, the
        // ephemeral key can't be stored due to libolm missing support for it, I would
        // argue that the ephemeral secret for QR verifications shouldn't be persisted either.
        //
        // This means that once we transition from a verification request into an actual
        // verification flow (SAS/QR) we won't be able to resume. In other words resumption
        // is only supported before both sides agree to verify.
        //
        // We would either need to remember if the request transitioned into a flow and only
        // support resumption if we didn't, otherwise we would risk getting different emojis
        // or secrets in the QR code, not to mention that the flows could be interrupted in
        // any non-starting state.
        //
        // In any case, we don't support resuming in the rust-sdk, so let's return null here.
        return null
    }

    override suspend fun requestSelfKeyVerification(methods: List<VerificationMethod>): PendingVerificationRequest {
        val verification = when (val identity = olmMachine.getIdentity(olmMachine.userId())) {
            is OwnUserIdentity -> identity.requestVerification(methods)
            is UserIdentity    -> throw IllegalArgumentException("This method doesn't support verification of other users devices")
            null               -> throw IllegalArgumentException("Cross signing has not been bootstrapped for our own user")
        }
        return verification.toPendingVerificationRequest()
    }

    override suspend fun requestKeyVerificationInDMs(
            methods: List<VerificationMethod>,
            otherUserId: String,
            roomId: String,
            localId: String?
    ): PendingVerificationRequest {
        olmMachine.ensureUsersKeys(listOf(otherUserId))
        val verification = when (val identity = olmMachine.getIdentity(otherUserId)) {
            is UserIdentity    -> identity.requestVerification(methods, roomId, localId!!)
            is OwnUserIdentity -> throw IllegalArgumentException("This method doesn't support verification of our own user")
            null               -> throw IllegalArgumentException("The user that we wish to verify doesn't support cross signing")
        }

        return verification.toPendingVerificationRequest()
    }

    override suspend fun readyPendingVerification(
            methods: List<VerificationMethod>,
            otherUserId: String,
            transactionId: String
    ): Boolean {
        val request = olmMachine.getVerificationRequest(otherUserId, transactionId)
        return if (request != null) {
            request.acceptWithMethods(methods)

            if (request.isReady()) {
                val qrcode = request.startQrVerification()

                if (qrcode != null) {
                    dispatcher.dispatchTxAdded(qrcode)
                }

                true
            } else {
                false
            }
        } else {
            false
        }
    }

    override suspend fun beginKeyVerification(
            method: VerificationMethod,
            otherUserId: String,
            transactionId: String
    ): String? {
        return if (method == VerificationMethod.SAS) {
            val request = olmMachine.getVerificationRequest(otherUserId, transactionId)

            val sas = request?.startSasVerification()

            if (sas != null) {
                dispatcher.dispatchTxAdded(sas)
                sas.transactionId
            } else {
                null
            }
        } else {
            throw IllegalArgumentException("Unknown verification method")
        }
    }

    override suspend fun beginDeviceVerification(otherUserId: String, otherDeviceId: String): String? {
        // This starts the short SAS flow, the one that doesn't start with
        // a `m.key.verification.request`, Element web stopped doing this, might
        // be wise do do so as well
        // DeviceListBottomSheetViewModel triggers this, interestingly the method that
        // triggers this is called `manuallyVerify()`
        val otherDevice = olmMachine.getDevice(otherUserId, otherDeviceId)
        val verification = otherDevice?.startVerification()
        return if (verification != null) {
            dispatcher.dispatchTxAdded(verification)
            verification.transactionId
        } else {
            null
        }
    }

    override suspend fun cancelVerificationRequest(request: PendingVerificationRequest) {
        request.transactionId ?: return
        cancelVerificationRequest(request.otherUserId, request.transactionId)
    }

    override suspend fun cancelVerificationRequest(otherUserId: String, transactionId: String) {
        val verificationRequest = olmMachine.getVerificationRequest(otherUserId, transactionId)
        verificationRequest?.cancel()
    }
}
