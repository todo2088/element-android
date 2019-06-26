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
package im.vector.matrix.android.internal.session.pushers

import im.vector.matrix.android.internal.network.NetworkConstants
import retrofit2.Call
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST


internal interface PushersAPI {

    /**
     * Get the pushers for this user.
     *
     * Ref: https://matrix.org/docs/spec/client_server/r0.4.0.html#get-matrix-client-r0-thirdparty-protocols
     */
    @GET(NetworkConstants.URI_API_PREFIX_PATH_R0 + "pushers")
    fun getPushers(): Call<PushersResponse>

    /**
     * This endpoint allows the creation, modification and deletion of pushers for this user ID.
     * The behaviour of this endpoint varies depending on the values in the JSON body.
     */
    @POST(NetworkConstants.URI_API_PREFIX_PATH_R0 + "pushers/set")
    fun setPusher(@Body jsonPusher: JsonPusher): Call<Unit>

}