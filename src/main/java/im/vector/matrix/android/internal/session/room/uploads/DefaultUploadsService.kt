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

package im.vector.matrix.android.internal.session.room.uploads

import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.session.room.uploads.GetUploadsResult
import im.vector.matrix.android.api.session.room.uploads.UploadsService
import im.vector.matrix.android.api.util.Cancelable
import im.vector.matrix.android.internal.task.TaskExecutor
import im.vector.matrix.android.internal.task.configureWith

internal class DefaultUploadsService @AssistedInject constructor(
        @Assisted private val roomId: String,
        private val taskExecutor: TaskExecutor,
        private val getUploadsTask: GetUploadsTask
) : UploadsService {

    @AssistedInject.Factory
    interface Factory {
        fun create(roomId: String): UploadsService
    }

    override fun getUploads(numberOfEvents: Int, since: String?, callback: MatrixCallback<GetUploadsResult>): Cancelable {
        return getUploadsTask
                .configureWith(GetUploadsTask.Params(roomId, numberOfEvents, since))
                .executeBy(taskExecutor)
    }
}
