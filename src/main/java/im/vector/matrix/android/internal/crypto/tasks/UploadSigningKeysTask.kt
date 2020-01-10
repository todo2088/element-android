/*
 * Copyright 2019 New Vector Ltd
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

package im.vector.matrix.android.internal.crypto.tasks

import im.vector.matrix.android.api.failure.Failure
import im.vector.matrix.android.internal.auth.registration.RegistrationFlowResponse
import im.vector.matrix.android.internal.crypto.api.CryptoApi
import im.vector.matrix.android.internal.crypto.model.rest.CrossSigningKeyInfo
import im.vector.matrix.android.internal.crypto.model.rest.KeysQueryResponse
import im.vector.matrix.android.internal.crypto.model.rest.UploadSigningKeysBody
import im.vector.matrix.android.internal.crypto.model.rest.UserPasswordAuth
import im.vector.matrix.android.internal.di.MoshiProvider
import im.vector.matrix.android.internal.network.executeRequest
import im.vector.matrix.android.internal.task.Task
import org.greenrobot.eventbus.EventBus
import javax.inject.Inject


internal interface UploadSigningKeysTask : Task<UploadSigningKeysTask.Params, KeysQueryResponse> {
    data class Params(
            // the device keys to send.
            val masterKey: CrossSigningKeyInfo,
            // the one-time keys to send.
            val userKey: CrossSigningKeyInfo,
            // the explicit device_id to use for upload (default is to use the same as that used during auth).
            val selfSignedKey: CrossSigningKeyInfo,
            val userPasswordAuth: UserPasswordAuth?
    )
}

internal class DefaultUploadSigningKeysTask @Inject constructor(
        private val cryptoApi: CryptoApi,
        private val eventBus: EventBus
) : UploadSigningKeysTask {
    override suspend fun execute(params: UploadSigningKeysTask.Params): KeysQueryResponse {

        val uploadQuery = UploadSigningKeysBody(
                masterKey = params.masterKey,
                userSigningKey = params.userKey,
                selfSigningKey = params.selfSignedKey,
                auth = params.userPasswordAuth.takeIf { params.userPasswordAuth?.session != null }
        )
        try {
            // Make a first request to start user-interactive authentication
            return executeRequest(eventBus) {
                apiCall = cryptoApi.uploadSigningKeys(uploadQuery)
            }
        } catch (throwable: Throwable) {
            if (throwable is Failure.OtherServerError
                    && throwable.httpCode == 401
                    && params.userPasswordAuth != null
                    /* Avoid infinite loop */
                    && params.userPasswordAuth.session.isNullOrEmpty()
            ) {
                try {
                    MoshiProvider.providesMoshi()
                            .adapter(RegistrationFlowResponse::class.java)
                            .fromJson(throwable.errorBody)
                } catch (e: Exception) {
                    null
                }?.let {
                    // Retry with authentication
                    return executeRequest(eventBus) {
                        apiCall = cryptoApi.uploadSigningKeys(
                                uploadQuery.copy(auth =  params.userPasswordAuth.copy(session = it.session))
                        )
                    }
                }
            }
            // Other error
            throw throwable

        }

    }


}
