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

package org.matrix.android.sdk.internal.crypto

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.session.crypto.verification.PendingVerificationRequest
import org.matrix.android.sdk.api.session.crypto.verification.ValidVerificationInfoReady
import org.matrix.android.sdk.api.session.crypto.verification.ValidVerificationInfoRequest
import org.matrix.android.sdk.api.session.crypto.verification.VerificationMethod
import org.matrix.android.sdk.api.session.crypto.verification.safeValueOf
import org.matrix.android.sdk.internal.crypto.model.rest.VERIFICATION_METHOD_QR_CODE_SCAN
import org.matrix.android.sdk.internal.crypto.model.rest.VERIFICATION_METHOD_QR_CODE_SHOW
import org.matrix.android.sdk.internal.crypto.model.rest.VERIFICATION_METHOD_RECIPROCATE
import org.matrix.android.sdk.internal.crypto.model.rest.VERIFICATION_METHOD_SAS
import uniffi.olm.OlmMachine
import uniffi.olm.OutgoingVerificationRequest
import uniffi.olm.StartSasResult
import uniffi.olm.VerificationRequest

internal class VerificationRequest(
        private val machine: OlmMachine,
        private var inner: VerificationRequest
) {
    private fun refreshData() {
        val request = this.machine.getVerificationRequest(this.inner.otherUserId, this.inner.flowId)

        if (request != null) {
            this.inner = request
        }

        return
    }

    fun acceptWithMethods(methods: List<VerificationMethod>): OutgoingVerificationRequest? {
        val stringMethods: MutableList<String> =
                methods
                        .map {
                            when (it) {
                                VerificationMethod.QR_CODE_SCAN -> VERIFICATION_METHOD_QR_CODE_SCAN
                                VerificationMethod.QR_CODE_SHOW -> VERIFICATION_METHOD_QR_CODE_SHOW
                                VerificationMethod.SAS          -> VERIFICATION_METHOD_SAS
                            }
                        }
                        .toMutableList()

        if (stringMethods.contains(VERIFICATION_METHOD_QR_CODE_SHOW) ||
                stringMethods.contains(VERIFICATION_METHOD_QR_CODE_SCAN)) {
            stringMethods.add(VERIFICATION_METHOD_RECIPROCATE)
        }

        return this.machine.acceptVerificationRequest(
                this.inner.otherUserId, this.inner.flowId, stringMethods)
    }

    fun isCanceled(): Boolean {
        refreshData()
        return this.inner.isCancelled
    }

    fun isDone(): Boolean {
        refreshData()
        return this.inner.isDone
    }

    fun isReady(): Boolean {
        refreshData()
        return this.inner.isReady
    }

    suspend fun startSasVerification(): StartSasResult? {
        refreshData()

        return withContext(Dispatchers.IO) {
            machine.startSasVerification(inner.otherUserId, inner.flowId)
        }
    }

    fun toPendingVerificationRequest(): PendingVerificationRequest {
        refreshData()
        val code = this.inner.cancelCode

        val cancelCode =
                if (code != null) {
                    safeValueOf(code)
                } else {
                    null
                }

        val ourMethods = this.inner.ourMethods
        val theirMethods = this.inner.theirMethods
        val otherDeviceId = this.inner.otherDeviceId

        var requestInfo: ValidVerificationInfoRequest? = null
        var readyInfo: ValidVerificationInfoReady? = null

        if (this.inner.weStarted && ourMethods != null) {
            requestInfo =
                    ValidVerificationInfoRequest(
                            this.inner.flowId,
                            this.machine.deviceId(),
                            ourMethods,
                            null,
                    )
        } else if (!this.inner.weStarted && ourMethods != null) {
            readyInfo =
                    ValidVerificationInfoReady(
                            this.inner.flowId,
                            this.machine.deviceId(),
                            ourMethods,
                    )
        }

        if (this.inner.weStarted && theirMethods != null && otherDeviceId != null) {
            readyInfo =
                    ValidVerificationInfoReady(
                            this.inner.flowId,
                            otherDeviceId,
                            theirMethods,
                    )
        } else if (!this.inner.weStarted && theirMethods != null && otherDeviceId != null) {
            requestInfo =
                    ValidVerificationInfoRequest(
                            this.inner.flowId,
                            otherDeviceId,
                            theirMethods,
                            System.currentTimeMillis(),
                    )
        }

        return PendingVerificationRequest(
                // Creation time
                System.currentTimeMillis(),
                // Who initiated the request
                !this.inner.weStarted,
                // Local echo id, what to do here?
                this.inner.flowId,
                // other user
                this.inner.otherUserId,
                // room id
                this.inner.roomId,
                // transaction id
                this.inner.flowId,
                // val requestInfo: ValidVerificationInfoRequest? = null,
                requestInfo,
                // val readyInfo: ValidVerificationInfoReady? = null,
                readyInfo,
                // cancel code if there is one
                cancelCode,
                // are we done/successful
                this.inner.isDone,
                // did another device answer the request
                this.inner.isPassive,
                // devices that should receive the events we send out
                null,
        )
    }
}
