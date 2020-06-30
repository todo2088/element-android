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

package im.vector.matrix.android.internal.session.download

import okhttp3.Interceptor
import okhttp3.Response
import javax.inject.Inject

class DownloadProgressInterceptor @Inject constructor(
        private val downloadStateTracker: DefaultContentDownloadStateTracker
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val url = chain.request().url.toUrl()
        val mxcURl = chain.request().header("matrix-sdk:mxc_URL")

        val request = chain.request().newBuilder()
                .removeHeader("matrix-sdk:mxc_URL")
                .build()

        val originalResponse = chain.proceed(request)
        if (!originalResponse.isSuccessful) {
            downloadStateTracker.error(mxcURl ?: url.toExternalForm(), originalResponse.code)
            return originalResponse
        }
        val responseBody = originalResponse.body ?: return originalResponse
        return originalResponse.newBuilder()
                .body(ProgressResponseBody(responseBody, mxcURl ?: url.toExternalForm(), downloadStateTracker))
                .build()
    }
}
