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

import im.vector.matrix.android.internal.database.model.ChunkEntity
import im.vector.matrix.android.internal.database.model.EventEntity
import im.vector.matrix.android.internal.database.model.EventEntity.LinkFilterMode.*
import im.vector.matrix.android.internal.database.model.EventEntityFields
import im.vector.matrix.android.internal.database.model.RoomEntity
import im.vector.matrix.android.internal.database.model.TimelineEventEntity
import im.vector.matrix.android.internal.database.model.TimelineEventEntityFields
import io.realm.Realm
import io.realm.RealmList
import io.realm.RealmQuery
import io.realm.Sort
import io.realm.kotlin.where

internal fun TimelineEventEntity.Companion.where(realm: Realm, eventId: String): RealmQuery<TimelineEventEntity> {
    return realm.where<TimelineEventEntity>().equalTo(TimelineEventEntityFields.EVENT_ID, eventId)
}

internal fun TimelineEventEntity.Companion.where(realm: Realm, eventIds: List<String>): RealmQuery<TimelineEventEntity> {
    return realm.where<TimelineEventEntity>().`in`(TimelineEventEntityFields.EVENT_ID, eventIds.toTypedArray())
}


internal fun TimelineEventEntity.Companion.latestEvent(realm: Realm,
                                                       roomId: String,
                                                       includedTypes: List<String> = emptyList(),
                                                       excludedTypes: List<String> = emptyList()): TimelineEventEntity? {

    val roomEntity = RoomEntity.where(realm, roomId).findFirst() ?: return null
    val eventList = if (roomEntity.sendingTimelineEvents.isNotEmpty()) {
        roomEntity.sendingTimelineEvents
    } else {
        ChunkEntity.findLastLiveChunkFromRoom(realm, roomId)?.timelineEvents
    }
    val query = eventList?.where()
    if (includedTypes.isNotEmpty()) {
        query?.`in`(EventEntityFields.TYPE, includedTypes.toTypedArray())
    } else if (excludedTypes.isNotEmpty()) {
        query?.not()?.`in`(EventEntityFields.TYPE, excludedTypes.toTypedArray())
    }
    return query
            ?.sort(TimelineEventEntityFields.ROOT.DISPLAY_INDEX, Sort.DESCENDING)
            ?.findFirst()
}

internal fun RealmList<TimelineEventEntity>.find(eventId: String): TimelineEventEntity? {
    return this.where().equalTo(TimelineEventEntityFields.ROOT.EVENT_ID, eventId).findFirst()
}
