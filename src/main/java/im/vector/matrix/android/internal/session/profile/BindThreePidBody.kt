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
package im.vector.matrix.android.internal.session.profile

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
internal data class BindThreePidBody(
        /**
         * Required. The client secret used in the session with the identity server.
         */
        @Json(name = "client_secret")
        val clientSecret: String,

        /**
         * Required. The identity server to use. (without "https://")
         */
        @Json(name = "id_server")
        var idServer: String,

        /**
         * Required. An access token previously registered with the identity server.
         */
        @Json(name = "id_access_token")
        var idAccessToken: String,

        /**
         * Required. The session identifier given by the identity server.
         */
        @Json(name = "sid")
        var sid: String
)
