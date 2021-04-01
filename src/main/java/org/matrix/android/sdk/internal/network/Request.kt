/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.matrix.android.sdk.internal.network

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.shouldBeRetried
import org.matrix.android.sdk.internal.network.ssl.CertUtil
import retrofit2.HttpException
import timber.log.Timber
import java.io.IOException

/**
 * Execute a request from the requestBlock and handle some of the Exception it could generate
 *
 * @param globalErrorReceiver will be use to notify error such as invalid token error. See [GlobalError]
 * @param isRetryable if set to true, the request will be executed again in case of error, after a delay
 * @param initialDelay the first delay to wait before a request is retried. Will be doubled after each retry
 * @param maxDelay the max delay to wait before a retry
 * @param maxRetryCount the max number of retries
 * @param requestBlock a suspend lambda to perform the network request
 */
internal suspend inline fun <DATA> executeRequest(globalErrorReceiver: GlobalErrorReceiver?,
                                                  isRetryable: Boolean = false,
                                                  initialDelay: Long = 100L,
                                                  maxDelay: Long = 10_000L,
                                                  maxRetryCount: Int = Int.MAX_VALUE,
                                                  noinline requestBlock: suspend () -> DATA): DATA {
    var currentRetryCount = 0
    var currentDelay = initialDelay

    while (true) {
        try {
            return requestBlock()
        } catch (throwable: Throwable) {
            val exception = when (throwable) {
                is KotlinNullPointerException -> IllegalStateException("The request returned a null body")
                is HttpException              -> throwable.toFailure(globalErrorReceiver)
                else                          -> throwable
            }

            // Log some details about the request which has failed. This is less useful than before...
            // Timber.e("Exception when executing request ${apiCall.request().method} ${apiCall.request().url.toString().substringBefore("?")}")
            Timber.e("Exception when executing request")

            // Check if this is a certificateException
            CertUtil.getCertificateException(exception)
                    // TODO Support certificate error once logged
                    // ?.also { unrecognizedCertificateException ->
                    //    // Send the error to the bus, for a global management
                    //    eventBus?.post(GlobalError.CertificateError(unrecognizedCertificateException))
                    // }
                    ?.also { unrecognizedCertificateException -> throw unrecognizedCertificateException }

            if (isRetryable && currentRetryCount++ < maxRetryCount && exception.shouldBeRetried()) {
                delay(currentDelay)
                currentDelay = (currentDelay * 2L).coerceAtMost(maxDelay)
                // Try again (loop)
            } else {
                throw when (exception) {
                    is IOException              -> Failure.NetworkConnection(exception)
                    is Failure.ServerError,
                    is Failure.OtherServerError -> exception
                    is CancellationException    -> Failure.Cancelled(exception)
                    else                        -> Failure.Unknown(exception)
                }
            }
        }
    }
}
