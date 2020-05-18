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

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import dagger.Lazy
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.failure.Failure
import im.vector.matrix.android.api.failure.MatrixError
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.identity.FoundThreePid
import im.vector.matrix.android.api.session.identity.IdentityService
import im.vector.matrix.android.api.session.identity.IdentityServiceError
import im.vector.matrix.android.api.session.identity.IdentityServiceListener
import im.vector.matrix.android.api.session.identity.ThreePid
import im.vector.matrix.android.api.util.Cancelable
import im.vector.matrix.android.api.util.NoOpCancellable
import im.vector.matrix.android.internal.di.AuthenticatedIdentity
import im.vector.matrix.android.internal.di.Unauthenticated
import im.vector.matrix.android.internal.network.RetrofitFactory
import im.vector.matrix.android.internal.session.SessionScope
import im.vector.matrix.android.internal.session.identity.db.IdentityServiceStore
import im.vector.matrix.android.internal.session.identity.todelete.AccountDataDataSource
import im.vector.matrix.android.internal.session.identity.todelete.observeNotNull
import im.vector.matrix.android.internal.session.openid.GetOpenIdTokenTask
import im.vector.matrix.android.internal.session.sync.model.accountdata.IdentityServerContent
import im.vector.matrix.android.internal.session.sync.model.accountdata.UserAccountData
import im.vector.matrix.android.internal.session.user.accountdata.UpdateUserAccountDataTask
import im.vector.matrix.android.internal.task.launchToCallback
import im.vector.matrix.android.internal.util.MatrixCoroutineDispatchers
import kotlinx.coroutines.GlobalScope
import okhttp3.OkHttpClient
import timber.log.Timber
import javax.inject.Inject
import javax.net.ssl.HttpsURLConnection

@SessionScope
internal class DefaultIdentityService @Inject constructor(
        private val identityServiceStore: IdentityServiceStore,
        private val getOpenIdTokenTask: GetOpenIdTokenTask,
        private val bulkLookupTask: BulkLookupTask,
        private val identityRegisterTask: IdentityRegisterTask,
        private val identityDisconnectTask: IdentityDisconnectTask,
        @Unauthenticated
        private val unauthenticatedOkHttpClient: Lazy<OkHttpClient>,
        @AuthenticatedIdentity
        private val okHttpClient: Lazy<OkHttpClient>,
        private val retrofitFactory: RetrofitFactory,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val updateUserAccountDataTask: UpdateUserAccountDataTask,
        private val identityApiProvider: IdentityApiProvider,
        private val accountDataDataSource: AccountDataDataSource
) : IdentityService {

    private val lifecycleOwner: LifecycleOwner = LifecycleOwner { lifecycleRegistry }
    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(lifecycleOwner)

    private val listeners = mutableSetOf<IdentityServiceListener>()

    fun start() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        // Observe the account data change
        accountDataDataSource
                .getLiveAccountDataEvent(UserAccountData.TYPE_IDENTITY_SERVER)
                .observeNotNull(lifecycleOwner) {
                    val identityServerContent = it.getOrNull()?.content?.toModel<IdentityServerContent>()
                    if (identityServerContent != null) {
                        notifyIdentityServerUrlChange(identityServerContent.baseUrl)
                    }
                    // TODO Handle the case where the account data is deleted?
                }
    }

    private fun notifyIdentityServerUrlChange(baseUrl: String?) {
        // This is maybe not a real change (local echo of account data we are just setting)
        if (identityServiceStore.get()?.identityServerUrl == baseUrl) {
            Timber.d("Local echo of identity server url change")
        } else {
            // Url has changed, we have to reset our store, update internal configuration and notify listeners
            identityServiceStore.setUrl(baseUrl)
            updateIdentityAPI(baseUrl)
            listeners.toList().forEach { it.onIdentityServerChange() }
        }
    }

    fun stop() {
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    override fun getDefaultIdentityServer(callback: MatrixCallback<String?>): Cancelable {
        // TODO Use Wellknown request
        callback.onSuccess("https://vector.im")
        return NoOpCancellable
    }

    override fun getCurrentIdentityServer(): String? {
        return identityServiceStore.get()?.identityServerUrl
    }

    override fun startBindSession(threePid: ThreePid, nothing: Nothing?, matrixCallback: MatrixCallback<ThreePid>) {
        TODO("Not yet implemented")
    }

    override fun finalizeBindSessionFor3PID(threePid: ThreePid, matrixCallback: MatrixCallback<Unit>) {
        TODO("Not yet implemented")
    }

    override fun submitValidationToken(pid: ThreePid, code: String, matrixCallback: MatrixCallback<Unit>) {
        TODO("Not yet implemented")
    }

    override fun startUnBindSession(threePid: ThreePid, nothing: Nothing?, matrixCallback: MatrixCallback<Pair<Boolean, ThreePid?>>) {
        TODO("Not yet implemented")
    }

    override fun setNewIdentityServer(url: String?, callback: MatrixCallback<Unit>): Cancelable {
        val urlCandidate = url?.let { param ->
            buildString {
                if (!param.startsWith("http")) {
                    append("https://")
                }
                append(param)
            }
        }

        return GlobalScope.launchToCallback(coroutineDispatchers.main, callback) {
            val current = getCurrentIdentityServer()
            when (urlCandidate) {
                current ->
                    // Nothing to do
                    Timber.d("Same URL, nothing to do")
                null    -> {
                    // Disconnect previous one if any
                    identityServiceStore.get()?.let {
                        if (it.identityServerUrl != null && it.token != null) {
                            // Disconnect, ignoring any error
                            runCatching {
                                identityDisconnectTask.execute(Unit)
                            }
                        }
                    }
                    identityServiceStore.setUrl(null)
                    updateAccountData(null)
                }
                else    -> {
                    // Try to get a token
                    getIdentityServerToken(urlCandidate)

                    updateAccountData(urlCandidate)
                }
            }
        }
    }

    private suspend fun updateAccountData(url: String?) {
        updateUserAccountDataTask.execute(UpdateUserAccountDataTask.IdentityParams(
                identityContent = IdentityServerContent(baseUrl = url)
        ))
    }

    override fun lookUp(threePids: List<ThreePid>, callback: MatrixCallback<List<FoundThreePid>>): Cancelable {
        if (threePids.isEmpty()) {
            callback.onSuccess(emptyList())
            return NoOpCancellable
        }

        return GlobalScope.launchToCallback(coroutineDispatchers.main, callback) {
            lookUpInternal(true, threePids)
        }
    }

    private suspend fun lookUpInternal(canRetry: Boolean, threePids: List<ThreePid>): List<FoundThreePid> {
        ensureToken()

        return try {
            bulkLookupTask.execute(BulkLookupTask.Params(threePids))
        } catch (throwable: Throwable) {
            // Refresh token?
            when {
                throwable.isInvalidToken() && canRetry -> {
                    identityServiceStore.setToken(null)
                    lookUpInternal(false, threePids)
                }
                throwable.isTermsNotSigned()           -> throw IdentityServiceError.TermsNotSignedException
                else                                   -> throw throwable
            }
        }
    }

    private suspend fun ensureToken() {
        val entity = identityServiceStore.get() ?: throw IdentityServiceError.NoIdentityServerConfigured
        val url = entity.identityServerUrl ?: throw IdentityServiceError.NoIdentityServerConfigured

        if (entity.token == null) {
            // Try to get a token
            getIdentityServerToken(url)
        }
    }

    private suspend fun getIdentityServerToken(url: String) {
        val api = retrofitFactory.create(unauthenticatedOkHttpClient, url).create(IdentityAuthAPI::class.java)

        val openIdToken = getOpenIdTokenTask.execute(Unit)
        val token = identityRegisterTask.execute(IdentityRegisterTask.Params(api, openIdToken))

        identityServiceStore.setToken(token.token)
    }

    override fun addListener(listener: IdentityServiceListener) {
        listeners.add(listener)
    }

    override fun removeListener(listener: IdentityServiceListener) {
        listeners.remove(listener)
    }

    private fun updateIdentityAPI(url: String?) {
        if (url == null) {
            identityApiProvider.identityApi = null
        } else {
            val retrofit = retrofitFactory.create(okHttpClient, url)
            identityApiProvider.identityApi = retrofit.create(IdentityAPI::class.java)
        }
    }
}

private fun Throwable.isInvalidToken(): Boolean {
    return this is Failure.ServerError
            && this.httpCode == HttpsURLConnection.HTTP_UNAUTHORIZED /* 401 */
}

private fun Throwable.isTermsNotSigned(): Boolean {
    return this is Failure.ServerError
            && httpCode == HttpsURLConnection.HTTP_FORBIDDEN /* 403 */
            && error.code == MatrixError.M_TERMS_NOT_SIGNED
}
