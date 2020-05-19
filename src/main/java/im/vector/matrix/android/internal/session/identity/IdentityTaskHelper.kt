/*
 * Copyright (c) 2020 New Vector Ltd
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

package im.vector.matrix.android.internal.session.identity

import im.vector.matrix.android.api.session.identity.IdentityServiceError
import im.vector.matrix.android.internal.network.executeRequest
import im.vector.matrix.android.internal.session.identity.model.IdentityAccountResponse

internal suspend fun getIdentityApiAndEnsureTerms(identityApiProvider: IdentityApiProvider, userId: String): IdentityAPI {
    val identityAPI = identityApiProvider.identityApi ?: throw IdentityServiceError.NoIdentityServerConfigured

    // Always check that we have access to the service (regarding terms)
    val identityAccountResponse = executeRequest<IdentityAccountResponse>(null) {
        apiCall = identityAPI.getAccount()
    }

    assert(userId == identityAccountResponse.userId)

    return identityAPI
}
