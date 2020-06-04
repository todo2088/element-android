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

package im.vector.matrix.android.internal.session.homeserver

import im.vector.matrix.android.internal.auth.version.Versions
import im.vector.matrix.android.internal.network.NetworkConstants
import retrofit2.Call
import retrofit2.http.GET

internal interface CapabilitiesAPI {

    /**
     * Request the homeserver capabilities
     */
    @GET(NetworkConstants.URI_API_PREFIX_PATH_R0 + "capabilities")
    fun getCapabilities(): Call<GetCapabilitiesResult>

    /**
     * Request the upload capabilities
     */
    @GET(NetworkConstants.URI_API_MEDIA_PREFIX_PATH_R0 + "config")
    fun getUploadCapabilities(): Call<GetUploadCapabilitiesResult>

    /**
     * Request the versions
     */
    @GET(NetworkConstants.URI_API_PREFIX_PATH_ + "versions")
    fun getVersions(): Call<Versions>

    /**
     * Ping the homeserver. We do not care about the returned data, so there is no use to parse them
     */
    @GET(NetworkConstants.URI_API_PREFIX_PATH_ + "versions")
    fun ping(): Call<Unit>
}
