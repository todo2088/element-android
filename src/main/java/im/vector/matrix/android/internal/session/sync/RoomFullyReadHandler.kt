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

package im.vector.matrix.android.internal.session.sync

import im.vector.matrix.android.internal.database.model.EventEntity
import im.vector.matrix.android.internal.session.room.read.FullyReadContent
import im.vector.matrix.android.internal.database.model.ReadMarkerEntity
import im.vector.matrix.android.internal.database.model.RoomSummaryEntity
import im.vector.matrix.android.internal.database.model.TimelineEventEntity
import im.vector.matrix.android.internal.database.model.TimelineEventEntityFields
import im.vector.matrix.android.internal.database.query.getOrCreate
import im.vector.matrix.android.internal.database.query.where
import io.realm.Realm
import timber.log.Timber
import javax.inject.Inject

internal class RoomFullyReadHandler @Inject constructor() {

    fun handle(realm: Realm, roomId: String, content: FullyReadContent?) {
        if (content == null) {
            return
        }
        Timber.v("Handle for roomId: $roomId eventId: ${content.eventId}")

        RoomSummaryEntity.getOrCreate(realm, roomId).apply {
            readMarkerId = content.eventId
        }
        // Remove the old markers if any
        val oldReadMarkerEvents = TimelineEventEntity
                .where(realm, roomId = roomId, linkFilterMode = EventEntity.LinkFilterMode.BOTH)
                .isNotNull(TimelineEventEntityFields.READ_MARKER.`$`)
                .findAll()

        oldReadMarkerEvents.forEach { it.readMarker = null }
        val readMarkerEntity = ReadMarkerEntity.getOrCreate(realm, roomId).apply {
            this.eventId = content.eventId
        }
        // Attach to timelineEvent if known
        val timelineEventEntities = TimelineEventEntity.where(realm, roomId = roomId, eventId = content.eventId).findAll()
        timelineEventEntities.forEach { it.readMarker = readMarkerEntity }
    }

}