/*
 * Copyright (c) 2021 New Vector Ltd
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

import android.os.Handler
import android.os.Looper
import org.matrix.android.sdk.api.session.crypto.verification.PendingVerificationRequest
import org.matrix.android.sdk.api.session.crypto.verification.VerificationService
import org.matrix.android.sdk.api.session.crypto.verification.VerificationTransaction
import timber.log.Timber

/** Class that implements some common methods to dispatch updates for the verification related classes */
internal class UpdateDispatcher(private val listeners: ArrayList<VerificationService.Listener>) {
    private val uiHandler = Handler(Looper.getMainLooper())

    internal fun addListener(listener: VerificationService.Listener) {
        uiHandler.post {
            if (!this.listeners.contains(listener)) {
                this.listeners.add(listener)
            }
        }
    }

    internal fun removeListener(listener: VerificationService.Listener) {
        uiHandler.post { this.listeners.remove(listener) }
    }

    internal fun dispatchTxAdded(tx: VerificationTransaction) {
        uiHandler.post {
            this.listeners.forEach {
                try {
                    it.transactionCreated(tx)
                } catch (e: Throwable) {
                    Timber.e(e, "## Error while notifying listeners")
                }
            }
        }
    }

    internal fun dispatchTxUpdated(tx: VerificationTransaction) {
        uiHandler.post {
            this.listeners.forEach {
                try {
                    it.transactionUpdated(tx)
                } catch (e: Throwable) {
                    Timber.e(e, "## Error while notifying listeners")
                }
            }
        }
    }

    internal fun dispatchRequestAdded(tx: PendingVerificationRequest) {
        Timber.v("## SAS dispatchRequestAdded txId:${tx.transactionId} $tx")
        uiHandler.post {
            this.listeners.forEach {
                try {
                    it.verificationRequestCreated(tx)
                } catch (e: Throwable) {
                    Timber.e(e, "## Error while notifying listeners")
                }
            }
        }
    }
}
