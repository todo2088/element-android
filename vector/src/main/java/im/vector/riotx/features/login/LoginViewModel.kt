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

package im.vector.riotx.features.login

import arrow.core.Try
import com.airbnb.mvrx.*
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.auth.Authenticator
import im.vector.matrix.android.api.auth.data.HomeServerConnectionConfig
import im.vector.matrix.android.api.session.Session
import im.vector.matrix.android.api.util.Cancelable
import im.vector.matrix.android.internal.auth.data.InteractiveAuthenticationFlow
import im.vector.matrix.android.internal.auth.data.LoginFlowResponse
import im.vector.riotx.core.di.ActiveSessionHolder
import im.vector.riotx.core.extensions.configureAndStart
import im.vector.riotx.core.platform.VectorViewModel
import im.vector.riotx.features.notifications.PushRuleTriggerListener
import im.vector.riotx.features.session.SessionListener
import timber.log.Timber

class LoginViewModel @AssistedInject constructor(@Assisted initialState: LoginViewState,
                                                 private val authenticator: Authenticator,
                                                 private val activeSessionHolder: ActiveSessionHolder,
                                                 private val pushRuleTriggerListener: PushRuleTriggerListener,
                                                 private val sessionListener: SessionListener)
    : VectorViewModel<LoginViewState, LoginAction>(initialState) {

    @AssistedInject.Factory
    interface Factory {
        fun create(initialState: LoginViewState): LoginViewModel
    }

    companion object : MvRxViewModelFactory<LoginViewModel, LoginViewState> {

        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: LoginViewState): LoginViewModel? {
            val activity: LoginActivity = (viewModelContext as ActivityViewModelContext).activity()
            return activity.loginViewModelFactory.create(state)
        }
    }

    var serverType: ServerType = ServerType.MatrixOrg
        private set

    var signMode: SignMode = SignMode.Unknown
        private set

    private var loginConfig: LoginConfig? = null

    private var homeServerConnectionConfig: HomeServerConnectionConfig? = null
    private var currentTask: Cancelable? = null

    override fun handle(action: LoginAction) {
        when (action) {
            is LoginAction.UpdateServerType -> handleUpdateServerType(action)
            is LoginAction.UpdateSignMode   -> handleUpdateSignMode(action)
            is LoginAction.InitWith         -> handleInitWith(action)
            is LoginAction.UpdateHomeServer -> handleUpdateHomeserver(action)
            is LoginAction.Login            -> handleLogin(action)
            is LoginAction.WebLoginSuccess  -> handleWebLoginSuccess(action)
            is LoginAction.ResetAction      -> handleResetAction(action)
        }
    }

    private fun handleResetAction(action: LoginAction.ResetAction) {
        // Cancel any request
        currentTask?.cancel()
        currentTask = null

        when (action) {
            LoginAction.ResetLogin          -> {
                setState {
                    copy(
                            asyncLoginAction = Uninitialized
                    )
                }
            }
            LoginAction.ResetHomeServerUrl  -> {
                setState {
                    copy(
                            asyncHomeServerLoginFlowRequest = Uninitialized
                    )
                }
            }
            LoginAction.ResetHomeServerType -> {
                serverType = ServerType.MatrixOrg
            }
            LoginAction.ResetSignMode       -> {
                signMode = SignMode.Unknown
                setState {
                    copy(
                            asyncHomeServerLoginFlowRequest = Uninitialized
                    )
                }
            }
        }
    }

    private fun handleUpdateSignMode(action: LoginAction.UpdateSignMode) {
        signMode = action.signMode
    }

    private fun handleUpdateServerType(action: LoginAction.UpdateServerType) {
        serverType = action.serverType
    }

    private fun handleInitWith(action: LoginAction.InitWith) {
        loginConfig = action.loginConfig
    }

    private fun handleLogin(action: LoginAction.Login) {
        val homeServerConnectionConfigFinal = homeServerConnectionConfig

        if (homeServerConnectionConfigFinal == null) {
            setState {
                copy(
                        asyncLoginAction = Fail(Throwable("Bad configuration"))
                )
            }
        } else {
            setState {
                copy(
                        asyncLoginAction = Loading()
                )
            }

            currentTask = authenticator.authenticate(homeServerConnectionConfigFinal, action.login, action.password, object : MatrixCallback<Session> {
                override fun onSuccess(data: Session) {
                    onSessionCreated(data)
                }

                override fun onFailure(failure: Throwable) {
                    // TODO Handled JobCancellationException
                    setState {
                        copy(
                                asyncLoginAction = Fail(failure)
                        )
                    }
                }
            })
        }
    }

    private fun onSessionCreated(session: Session) {
        activeSessionHolder.setActiveSession(session)
        session.configureAndStart(pushRuleTriggerListener, sessionListener)
        setState {
            copy(
                    asyncLoginAction = Success(Unit)
            )
        }
    }

    private fun handleWebLoginSuccess(action: LoginAction.WebLoginSuccess) {
        val homeServerConnectionConfigFinal = homeServerConnectionConfig

        if (homeServerConnectionConfigFinal == null) {
            // Should not happen
            Timber.w("homeServerConnectionConfig is null")
        } else {
            authenticator.createSessionFromSso(action.credentials, homeServerConnectionConfigFinal, object : MatrixCallback<Session> {
                override fun onSuccess(data: Session) {
                    onSessionCreated(data)
                }

                override fun onFailure(failure: Throwable) = setState {
                    copy(asyncLoginAction = Fail(failure))
                }
            })
        }
    }

    private fun handleUpdateHomeserver(action: LoginAction.UpdateHomeServer) = withState { state ->
        var newConfig: HomeServerConnectionConfig? = null
        Try {
            val homeServerUri = action.homeServerUrl
            newConfig = HomeServerConnectionConfig.Builder()
                    .withHomeServerUri(homeServerUri)
                    .build()
        }

        // Do not retry if we already have flows for this config -> causes infinite focus loop
        if (newConfig?.homeServerUri?.toString() == homeServerConnectionConfig?.homeServerUri?.toString()
            && state.asyncHomeServerLoginFlowRequest is Success) return@withState

        currentTask?.cancel()
        currentTask = null
        homeServerConnectionConfig = newConfig

        val homeServerConnectionConfigFinal = homeServerConnectionConfig

        if (homeServerConnectionConfigFinal == null) {
            // This is invalid
            setState {
                copy(
                        asyncHomeServerLoginFlowRequest = Fail(Throwable("Bad format"))
                )
            }
        } else {
            setState {
                copy(
                        asyncHomeServerLoginFlowRequest = Loading()
                )
            }

            currentTask = authenticator.getLoginFlow(homeServerConnectionConfigFinal, object : MatrixCallback<LoginFlowResponse> {
                override fun onFailure(failure: Throwable) {
                    setState {
                        copy(
                                asyncHomeServerLoginFlowRequest = Fail(failure)
                        )
                    }
                }

                override fun onSuccess(data: LoginFlowResponse) {
                    val loginMode = when {
                        // SSO login is taken first
                        data.flows.any { it.type == InteractiveAuthenticationFlow.TYPE_LOGIN_SSO }      -> LoginMode.Sso
                        data.flows.any { it.type == InteractiveAuthenticationFlow.TYPE_LOGIN_PASSWORD } -> LoginMode.Password
                        else                                                                            -> LoginMode.Unsupported(data.flows.mapNotNull { it.type }.toList())
                    }

                    setState {
                        copy(
                                asyncHomeServerLoginFlowRequest = Success(loginMode)
                        )
                    }
                }
            })
        }
    }

    override fun onCleared() {
        super.onCleared()

        currentTask?.cancel()
    }

    fun getInitialHomeServerUrl(): String? {
        return loginConfig?.homeServerUrl
    }

    fun getHomeServerUrl(): String {
        return homeServerConnectionConfig?.homeServerUri?.toString() ?: ""
    }
}
