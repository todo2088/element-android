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

import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.session.crypto.crosssigning.CrossSigningService
import im.vector.matrix.android.api.session.crypto.verification.VerificationTransaction
import im.vector.matrix.android.api.session.crypto.verification.VerificationTxState
import im.vector.matrix.android.internal.crypto.actions.SetDeviceVerificationAction
import im.vector.matrix.android.internal.crypto.crosssigning.DeviceTrustLevel
import timber.log.Timber

/**
 * Generic interactive key verification transaction
 */
internal abstract class DefaultVerificationTransaction(
        private val setDeviceVerificationAction: SetDeviceVerificationAction,
        private val crossSigningService: CrossSigningService,
        private val userId: String,
        override val transactionId: String,
        override val otherUserId: String,
        override var otherDeviceId: String? = null,
        override val isIncoming: Boolean) : VerificationTransaction {

    lateinit var transport: VerificationTransport

    interface Listener {
        fun transactionUpdated(tx: VerificationTransaction)
    }

    protected var listeners = ArrayList<Listener>()

    fun addListener(listener: Listener) {
        if (!listeners.contains(listener)) listeners.add(listener)
    }

    fun removeListener(listener: Listener) {
        listeners.remove(listener)
    }

    protected fun trust(canTrustOtherUserMasterKey: Boolean, toVerifyDeviceIds: List<String>) {
        if (canTrustOtherUserMasterKey) {
            // If not me sign his MSK and upload the signature
            if (otherUserId != userId) {
                // we should trust this master key
                // And check verification MSK -> SSK?
                crossSigningService.trustUser(otherUserId, object : MatrixCallback<Unit> {
                    override fun onFailure(failure: Throwable) {
                        Timber.e(failure, "## QR Verification: Failed to trust User $otherUserId")
                    }
                })
            } else {
                // Mark my keys as trusted locally
                crossSigningService.markMyMasterKeyAsTrusted()
            }
        }

        if (otherUserId == userId) {
            // If me it's reasonable to sign and upload the device signature
            // Notice that i might not have the private keys, so may not be able to do it
            crossSigningService.trustDevice(otherDeviceId!!, object : MatrixCallback<Unit> {
                override fun onFailure(failure: Throwable) {
                    Timber.w(failure, "## QR Verification: Failed to sign new device $otherDeviceId")
                }
            })
        }

        // TODO what if the otherDevice is not in this list? and should we
        toVerifyDeviceIds.forEach {
            setDeviceVerified(otherUserId, it)
        }
        transport.done(transactionId)
        state = VerificationTxState.Verified
    }

    private fun setDeviceVerified(userId: String, deviceId: String) {
        // TODO should not override cross sign status
        setDeviceVerificationAction.handle(DeviceTrustLevel(crossSigningVerified = false, locallyVerified = true),
                userId,
                deviceId)
    }
}
