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

package im.vector.matrix.android.internal.session.room.read

import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.session.room.read.ReadService
import im.vector.matrix.android.internal.database.model.EventEntity
import im.vector.matrix.android.internal.database.query.latestEvent
import im.vector.matrix.android.internal.task.TaskExecutor
import im.vector.matrix.android.internal.task.configureWith
import im.vector.matrix.android.internal.util.fetchCopied

internal class DefaultReadService(private val roomId: String,
                                  private val monarchy: Monarchy,
                                  private val setReadMarkersTask: SetReadMarkersTask,
                                  private val taskExecutor: TaskExecutor) : ReadService {

    override fun markAllAsRead(callback: MatrixCallback<Unit>) {
        val latestEvent = getLatestEvent()
        val params = SetReadMarkersTask.Params(roomId, fullyReadEventId = latestEvent?.eventId, readReceiptEventId = latestEvent?.eventId)
        setReadMarkersTask.configureWith(params).dispatchTo(callback).executeBy(taskExecutor)
    }

    override fun setReadReceipt(eventId: String, callback: MatrixCallback<Unit>) {
        val params = SetReadMarkersTask.Params(roomId, fullyReadEventId = null, readReceiptEventId = eventId)
        setReadMarkersTask.configureWith(params).dispatchTo(callback).executeBy(taskExecutor)
    }

    override fun setReadMarker(fullyReadEventId: String, callback: MatrixCallback<Unit>) {
        val params = SetReadMarkersTask.Params(roomId, fullyReadEventId = fullyReadEventId, readReceiptEventId = null)
        setReadMarkersTask.configureWith(params).dispatchTo(callback).executeBy(taskExecutor)
    }

    private fun getLatestEvent(): EventEntity? {
        return monarchy.fetchCopied { EventEntity.latestEvent(it, roomId) }
    }


}