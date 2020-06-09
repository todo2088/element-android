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

import im.vector.matrix.android.api.auth.data.LoginFlowTypes
import im.vector.matrix.android.api.failure.Failure
import im.vector.matrix.android.api.failure.toRegistrationFlowResponse
import im.vector.matrix.android.internal.crypto.api.CryptoApi
import im.vector.matrix.android.internal.crypto.model.CryptoCrossSigningKey
import im.vector.matrix.android.internal.crypto.model.rest.KeysQueryResponse
import im.vector.matrix.android.internal.crypto.model.rest.UploadSigningKeysBody
import im.vector.matrix.android.internal.crypto.model.rest.UserPasswordAuth
import im.vector.matrix.android.internal.crypto.model.toRest
import im.vector.matrix.android.internal.network.executeRequest
import im.vector.matrix.android.internal.task.Task
import org.greenrobot.eventbus.EventBus
import javax.inject.Inject

internal interface UploadSigningKeysTask : Task<UploadSigningKeysTask.Params, Unit> {
    data class Params(
            // the MSK
            val masterKey: CryptoCrossSigningKey,
            // the USK
            val userKey: CryptoCrossSigningKey,
            // the SSK
            val selfSignedKey: CryptoCrossSigningKey,
            /**
             * - If null:
             *    - no retry will be performed
             * - If not null, it may or may not contain a sessionId:
             *    - If sessionId is null:
             *       - password should not be null: the task will perform a first request to get a sessionId, and then a second one
             *    - If sessionId is not null:
             *       - password should not be null as well, and no retry will be performed
             */
            val userPasswordAuth: UserPasswordAuth?
    )
}

data class UploadSigningKeys(val failures: Map<String, Any>?) : Failure.FeatureFailure()

internal class DefaultUploadSigningKeysTask @Inject constructor(
        private val cryptoApi: CryptoApi,
        private val eventBus: EventBus
) : UploadSigningKeysTask {

    override suspend fun execute(params: UploadSigningKeysTask.Params) {
        val paramsHaveSessionId = params.userPasswordAuth?.session != null

        val uploadQuery = UploadSigningKeysBody(
                masterKey = params.masterKey.toRest(),
                userSigningKey = params.userKey.toRest(),
                selfSigningKey = params.selfSignedKey.toRest(),
                // If sessionId is provided, use the userPasswordAuth
                auth = params.userPasswordAuth.takeIf { paramsHaveSessionId }
        )
        try {
            doRequest(uploadQuery)
        } catch (throwable: Throwable) {
            val registrationFlowResponse = throwable.toRegistrationFlowResponse()
            if (registrationFlowResponse != null
                    && registrationFlowResponse.flows.orEmpty().any { it.stages?.contains(LoginFlowTypes.PASSWORD) == true }
                    && params.userPasswordAuth?.password != null
                    && !paramsHaveSessionId
            ) {
                // Retry with authentication
                doRequest(uploadQuery.copy(auth = params.userPasswordAuth.copy(session = registrationFlowResponse.session)))
            } else {
                // Other error
                throw throwable
            }
        }
    }

    private suspend fun doRequest(uploadQuery: UploadSigningKeysBody) {
        val keysQueryResponse = executeRequest<KeysQueryResponse>(eventBus) {
            apiCall = cryptoApi.uploadSigningKeys(uploadQuery)
        }
        if (keysQueryResponse.failures?.isNotEmpty() == true) {
            throw UploadSigningKeys(keysQueryResponse.failures)
        }
    }
}
