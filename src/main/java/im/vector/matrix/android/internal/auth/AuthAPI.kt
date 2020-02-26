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

package im.vector.matrix.android.internal.auth

import im.vector.matrix.android.api.auth.data.Credentials
import im.vector.matrix.android.api.auth.data.Versions
import im.vector.matrix.android.internal.auth.data.LoginFlowResponse
import im.vector.matrix.android.internal.auth.data.PasswordLoginParams
import im.vector.matrix.android.internal.auth.data.RiotConfig
import im.vector.matrix.android.internal.auth.login.ResetPasswordMailConfirmed
import im.vector.matrix.android.internal.auth.registration.AddThreePidRegistrationParams
import im.vector.matrix.android.internal.auth.registration.AddThreePidRegistrationResponse
import im.vector.matrix.android.internal.auth.registration.RegistrationParams
import im.vector.matrix.android.internal.auth.registration.SuccessResult
import im.vector.matrix.android.internal.auth.registration.ValidationCodeBody
import im.vector.matrix.android.internal.network.NetworkConstants
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.Headers
import retrofit2.http.POST
import retrofit2.http.Path
import retrofit2.http.Url

/**
 * The login REST API.
 */
internal interface AuthAPI {

    /**
     * Get a Riot config file
     */
    @GET("config.json")
    fun getRiotConfig(): Call<RiotConfig>

    /**
     * Get the version information of the homeserver
     */
    @GET(NetworkConstants.URI_API_PREFIX_PATH_ + "versions")
    fun versions(): Call<Versions>

    /**
     * Register to the homeserver
     * Ref: https://matrix.org/docs/spec/client_server/latest#account-registration-and-management
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "register")
    fun register(@Body registrationParams: RegistrationParams): Call<Credentials>

    /**
     * Add 3Pid during registration
     * Ref: https://gist.github.com/jryans/839a09bf0c5a70e2f36ed990d50ed928
     * https://github.com/matrix-org/matrix-doc/pull/2290
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "register/{threePid}/requestToken")
    fun add3Pid(@Path("threePid") threePid: String, @Body params: AddThreePidRegistrationParams): Call<AddThreePidRegistrationResponse>

    /**
     * Validate 3pid
     */
    @POST
    fun validate3Pid(@Url url: String, @Body params: ValidationCodeBody): Call<SuccessResult>

    /**
     * Get the supported login flow
     * Ref: https://matrix.org/docs/spec/client_server/latest#get-matrix-client-r0-login
     */
    @GET(NetworkConstants.URI_API_PREFIX_PATH_R0 + "login")
    fun getLoginFlows(): Call<LoginFlowResponse>

    /**
     * Pass params to the server for the current login phase.
     * Set all the timeouts to 1 minute
     *
     * @param loginParams the login parameters
     */
    @Headers("CONNECT_TIMEOUT:60000", "READ_TIMEOUT:60000", "WRITE_TIMEOUT:60000")
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "login")
    fun login(@Body loginParams: PasswordLoginParams): Call<Credentials>

    /**
     * Ask the homeserver to reset the password associated with the provided email.
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "account/password/email/requestToken")
    fun resetPassword(@Body params: AddThreePidRegistrationParams): Call<AddThreePidRegistrationResponse>

    /**
     * Ask the homeserver to reset the password with the provided new password once the email is validated.
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "account/password")
    fun resetPasswordMailConfirmed(@Body params: ResetPasswordMailConfirmed): Call<Unit>
}
