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
package im.vector.matrix.android.internal.database.query

import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.api.session.events.model.LocalEcho
import im.vector.matrix.android.internal.database.model.ChunkEntity
import im.vector.matrix.android.internal.database.model.ReadReceiptEntity
import im.vector.matrix.android.internal.session.room.send.LocalEchoEventFactory

internal fun isEventRead(monarchy: Monarchy,
                         userId: String?,
                         roomId: String?,
                         eventId: String?): Boolean {
    if (userId.isNullOrBlank() || roomId.isNullOrBlank() || eventId.isNullOrBlank()) {
        return false
    }
    if (LocalEcho.isLocalEchoId(eventId)) {
        return true
    }
    var isEventRead = false

    monarchy.doWithRealm { realm ->
        val liveChunk = ChunkEntity.findLastLiveChunkFromRoom(realm, roomId) ?: return@doWithRealm
        val eventToCheck = liveChunk.timelineEvents.find(eventId)?.root

        isEventRead = if (eventToCheck?.sender == userId) {
            true
        } else {
            val readReceipt = ReadReceiptEntity.where(realm, roomId, userId).findFirst() ?: return@doWithRealm
            val readReceiptIndex = liveChunk.timelineEvents.find(readReceipt.eventId)?.root?.displayIndex ?: Int.MIN_VALUE
            val eventToCheckIndex = eventToCheck?.displayIndex ?: Int.MAX_VALUE

            eventToCheckIndex <= readReceiptIndex
        }
    }

    return isEventRead
}
