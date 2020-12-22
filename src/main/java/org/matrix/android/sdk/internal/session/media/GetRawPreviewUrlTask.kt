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

package org.matrix.android.sdk.internal.session.media

import org.greenrobot.eventbus.EventBus
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface GetRawPreviewUrlTask : Task<GetRawPreviewUrlTask.Params, JsonDict> {
    data class Params(
            val url: String,
            val timestamp: Long?
    )
}

internal class DefaultGetRawPreviewUrlTask @Inject constructor(
        private val mediaAPI: MediaAPI,
        private val eventBus: EventBus
) : GetRawPreviewUrlTask {

    override suspend fun execute(params: GetRawPreviewUrlTask.Params): JsonDict {
        return executeRequest(eventBus) {
            apiCall = mediaAPI.getPreviewUrlData(params.url, params.timestamp)
        }
    }
}
