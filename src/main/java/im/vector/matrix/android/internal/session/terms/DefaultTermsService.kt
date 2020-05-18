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

package im.vector.matrix.android.internal.session.terms

import dagger.Lazy
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.terms.GetTermsResponse
import im.vector.matrix.android.api.session.terms.TermsService
import im.vector.matrix.android.api.util.Cancelable
import im.vector.matrix.android.internal.di.Unauthenticated
import im.vector.matrix.android.internal.network.NetworkConstants
import im.vector.matrix.android.internal.network.RetrofitFactory
import im.vector.matrix.android.internal.network.executeRequest
import im.vector.matrix.android.internal.session.identity.IdentityAuthAPI
import im.vector.matrix.android.internal.session.identity.IdentityRegisterTask
import im.vector.matrix.android.internal.session.identity.todelete.AccountDataDataSource
import im.vector.matrix.android.internal.session.openid.GetOpenIdTokenTask
import im.vector.matrix.android.internal.session.sync.model.accountdata.AcceptedTermsContent
import im.vector.matrix.android.internal.session.sync.model.accountdata.UserAccountData
import im.vector.matrix.android.internal.session.user.accountdata.UpdateUserAccountDataTask
import im.vector.matrix.android.internal.task.launchToCallback
import im.vector.matrix.android.internal.util.MatrixCoroutineDispatchers
import kotlinx.coroutines.GlobalScope
import okhttp3.OkHttpClient
import javax.inject.Inject

internal class DefaultTermsService @Inject constructor(
        @Unauthenticated
        private val unauthenticatedOkHttpClient: Lazy<OkHttpClient>,
        private val accountDataDataSource: AccountDataDataSource,
        private val termsAPI: TermsAPI,
        private val retrofitFactory: RetrofitFactory,
        private val getOpenIdTokenTask: GetOpenIdTokenTask,
        private val identityRegisterTask: IdentityRegisterTask,
        private val updateUserAccountDataTask: UpdateUserAccountDataTask,
        private val coroutineDispatchers: MatrixCoroutineDispatchers
) : TermsService {
    override fun getTerms(serviceType: TermsService.ServiceType,
                          baseUrl: String,
                          callback: MatrixCallback<GetTermsResponse>): Cancelable {
        return GlobalScope.launchToCallback(coroutineDispatchers.main, callback) {
            val sep = if (baseUrl.endsWith("/")) "" else "/"

            val url = when (serviceType) {
                TermsService.ServiceType.IntegrationManager -> "$baseUrl$sep${NetworkConstants.URI_INTEGRATION_MANAGER_PATH}"
                TermsService.ServiceType.IdentityService    -> "$baseUrl$sep${NetworkConstants.URI_IDENTITY_PATH_V2}"
            }

            val termsResponse = executeRequest<TermsResponse>(null) {
                apiCall = termsAPI.getTerms("${url}terms")
            }

            GetTermsResponse(termsResponse, getAlreadyAcceptedTermUrlsFromAccountData())
        }
    }

    override fun agreeToTerms(serviceType: TermsService.ServiceType,
                              baseUrl: String,
                              agreedUrls: List<String>,
                              token: String?,
                              callback: MatrixCallback<Unit>): Cancelable {
        return GlobalScope.launchToCallback(coroutineDispatchers.main, callback) {
            val sep = if (baseUrl.endsWith("/")) "" else "/"

            val url = when (serviceType) {
                TermsService.ServiceType.IntegrationManager -> "$baseUrl$sep${NetworkConstants.URI_INTEGRATION_MANAGER_PATH}"
                TermsService.ServiceType.IdentityService    -> "$baseUrl$sep${NetworkConstants.URI_IDENTITY_PATH_V2}"
            }

            val tokenToUse = token?.takeIf { it.isNotEmpty() } ?: getToken(baseUrl)

            executeRequest<Unit>(null) {
                apiCall = termsAPI.agreeToTerms("${url}terms", AcceptTermsBody(agreedUrls), "Bearer $tokenToUse")
            }

            //client SHOULD update this account data section adding any the URLs
            // of any additional documents that the user agreed to this list.
            //Get current m.accepted_terms append new ones and update account data
            val listOfAcceptedTerms = getAlreadyAcceptedTermUrlsFromAccountData()

            val newList = listOfAcceptedTerms.toMutableSet().apply { addAll(agreedUrls) }.toList()

            updateUserAccountDataTask.execute(UpdateUserAccountDataTask.AcceptedTermsParams(
                    acceptedTermsContent = AcceptedTermsContent(newList)
            ))
        }
    }

    private suspend fun getToken(url: String): String {
        // TODO This is duplicated code see DefaultIdentityService
        val api = retrofitFactory.create(unauthenticatedOkHttpClient, url).create(IdentityAuthAPI::class.java)

        val openIdToken = getOpenIdTokenTask.execute(Unit)
        val token = identityRegisterTask.execute(IdentityRegisterTask.Params(api, openIdToken))

        return token.token
    }

    private fun getAlreadyAcceptedTermUrlsFromAccountData(): Set<String> {
        return accountDataDataSource.getAccountDataEvent(UserAccountData.TYPE_ACCEPTED_TERMS)
                ?.content
                ?.toModel<AcceptedTermsContent>()
                ?.acceptedTerms
                ?.toSet()
                .orEmpty()
    }
}
