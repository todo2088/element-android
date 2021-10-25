/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.content

import org.matrix.android.sdk.api.MatrixUrls
import org.matrix.android.sdk.api.MatrixUrls.isMxcUrl
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.session.content.ContentUrlResolver
import org.matrix.android.sdk.api.session.contentscanning.ContentScannerService
import org.matrix.android.sdk.internal.crypto.attachments.ElementToDecrypt
import org.matrix.android.sdk.internal.network.NetworkConstants
import org.matrix.android.sdk.internal.util.ensureTrailingSlash
import javax.inject.Inject

internal class DefaultContentUrlResolver @Inject constructor(
        homeServerConnectionConfig: HomeServerConnectionConfig,
        private val scannerService: ContentScannerService
) : ContentUrlResolver {

    private val baseUrl = homeServerConnectionConfig.homeServerUriBase.toString().ensureTrailingSlash()

    override val uploadUrl = baseUrl + NetworkConstants.URI_API_MEDIA_PREFIX_PATH_R0 + "upload"

    override fun resolveFullSize(contentUrl: String?): String? {
        return contentUrl
                // do not allow non-mxc content URLs
                ?.takeIf { it.isMxcUrl() }
                ?.let {
                    resolve(
                            contentUrl = it,
                            prefix = NetworkConstants.URI_API_MEDIA_PREFIX_PATH_R0 + "download/"
                    )
                }
    }

    override fun resolveThumbnail(contentUrl: String?, width: Int, height: Int, method: ContentUrlResolver.ThumbnailMethod): String? {
        return contentUrl
                // do not allow non-mxc content URLs
                ?.takeIf { it.isMxcUrl() }
                ?.let {
                    resolve(
                            contentUrl = it,
                            prefix = NetworkConstants.URI_API_MEDIA_PREFIX_PATH_R0 + "thumbnail/",
                            params = "?width=$width&height=$height&method=${method.value}"
                    )
                }
    }

    override fun resolveForDownload(contentUrl: String?, elementToDecrypt: ElementToDecrypt?): ContentUrlResolver.ResolvedMethod? {
        return resolveFullSize(contentUrl)?.let { ContentUrlResolver.ResolvedMethod.GET(it) }
    }

    private fun resolve(contentUrl: String,
                        prefix: String,
                        params: String = ""): String? {
        var serverAndMediaId = contentUrl.removePrefix(MatrixUrls.MATRIX_CONTENT_URI_SCHEME)
        val fragmentOffset = serverAndMediaId.indexOf("#")
        var fragment = ""
        if (fragmentOffset >= 0) {
            fragment = serverAndMediaId.substring(fragmentOffset)
            serverAndMediaId = serverAndMediaId.substring(0, fragmentOffset)
        }

        return baseUrl + prefix + serverAndMediaId + params + fragment
    }
}
