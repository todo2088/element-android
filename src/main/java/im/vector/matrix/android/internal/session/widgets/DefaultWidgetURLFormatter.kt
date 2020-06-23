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

package im.vector.matrix.android.internal.session.widgets

import im.vector.matrix.android.api.MatrixConfiguration
import im.vector.matrix.android.api.session.integrationmanager.IntegrationManagerConfig
import im.vector.matrix.android.api.session.integrationmanager.IntegrationManagerService
import im.vector.matrix.android.api.session.widgets.WidgetURLFormatter
import im.vector.matrix.android.internal.session.SessionLifecycleObserver
import im.vector.matrix.android.internal.session.SessionScope
import im.vector.matrix.android.internal.session.integrationmanager.IntegrationManager
import im.vector.matrix.android.internal.session.widgets.token.GetScalarTokenTask
import java.net.URLEncoder
import javax.inject.Inject

@SessionScope
internal class DefaultWidgetURLFormatter @Inject constructor(private val integrationManager: IntegrationManager,
                                                             private val getScalarTokenTask: GetScalarTokenTask,
                                                             private val matrixConfiguration: MatrixConfiguration
) : IntegrationManagerService.Listener, WidgetURLFormatter, SessionLifecycleObserver {

    private lateinit var currentConfig: IntegrationManagerConfig
    private var whiteListedUrls: List<String> = emptyList()

    override fun onStart() {
        setupWithConfiguration()
        integrationManager.addListener(this)
    }

    override fun onStop() {
        integrationManager.removeListener(this)
    }

    override fun onConfigurationChanged(configs: List<IntegrationManagerConfig>) {
        setupWithConfiguration()
    }

    private fun setupWithConfiguration() {
        val preferredConfig = integrationManager.getPreferredConfig()
        if (!this::currentConfig.isInitialized || preferredConfig != currentConfig) {
            currentConfig = preferredConfig
            whiteListedUrls = if (matrixConfiguration.integrationWidgetUrls.isEmpty()) {
                listOf(preferredConfig.restUrl)
            } else {
                matrixConfiguration.integrationWidgetUrls
            }
        }
    }

    /**
     * Takes care of fetching a scalar token if required and build the final url.
     */
    override suspend fun format(baseUrl: String, params: Map<String, String>, forceFetchScalarToken: Boolean, bypassWhitelist: Boolean): String {
        return if (bypassWhitelist || isWhiteListed(baseUrl)) {
            val taskParams = GetScalarTokenTask.Params(currentConfig.restUrl, forceFetchScalarToken)
            val scalarToken = getScalarTokenTask.execute(taskParams)
            buildString {
                append(baseUrl)
                appendParamToUrl("scalar_token", scalarToken)
                appendParamsToUrl(params)
            }
        } else {
            buildString {
                append(baseUrl)
                appendParamsToUrl(params)
            }
        }
    }

    private fun isWhiteListed(url: String): Boolean {
        val allowed: List<String> = whiteListedUrls
        for (allowedUrl in allowed) {
            if (url.startsWith(allowedUrl)) {
                return true
            }
        }
        return false
    }

    private fun StringBuilder.appendParamsToUrl(params: Map<String, String>): StringBuilder {
        params.forEach { (param, value) ->
            appendParamToUrl(param, value)
        }
        return this
    }

    private fun StringBuilder.appendParamToUrl(param: String, value: String): StringBuilder {
        if (contains("?")) {
            append("&")
        } else {
            append("?")
        }

        append(param)
        append("=")
        append(URLEncoder.encode(value, "utf-8"))

        return this
    }
}
