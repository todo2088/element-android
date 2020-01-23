/*
 * Copyright 2020 New Vector Ltd
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

import im.vector.matrix.android.api.failure.Failure
import im.vector.matrix.android.internal.network.executeRequest
import im.vector.matrix.android.internal.task.TaskExecutor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import javax.inject.Inject

internal class HomeServerPinger @Inject constructor(private val taskExecutor: TaskExecutor,
                                                    private val capabilitiesAPI: CapabilitiesAPI) {

    fun canReachHomeServer(callback: (Boolean) -> Unit) {
        taskExecutor.executorScope.launch {
            val canReach = canReachHomeServer()
            callback(canReach)
        }
    }

    suspend fun canReachHomeServer(): Boolean {
        return try {
            executeRequest<Unit>(null) {
                apiCall = capabilitiesAPI.getVersions()
            }
            true
        } catch (throwable: Throwable) {
            if (throwable is Failure.OtherServerError) {
                (throwable.httpCode == 404 || throwable.httpCode == 400)
            } else {
                false
            }
        }
    }
}
