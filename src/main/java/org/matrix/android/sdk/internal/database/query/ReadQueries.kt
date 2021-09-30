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
package org.matrix.android.sdk.internal.database.query

import org.matrix.android.sdk.api.session.events.model.LocalEcho
import org.matrix.android.sdk.internal.database.model.ChunkEntity
import org.matrix.android.sdk.internal.database.model.ReadMarkerEntity
import org.matrix.android.sdk.internal.database.model.ReadReceiptEntity
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import io.realm.Realm
import io.realm.RealmConfiguration

private const val MARK_OLD_EVENT_AS_READ = true
private const val MARK_UNREAD_DUE_TO_FASTLANE = false

internal fun isEventRead(realmConfiguration: RealmConfiguration,
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

    Realm.getInstance(realmConfiguration).use { realm ->
        val liveChunk = ChunkEntity.findLastForwardChunkOfRoom(realm, roomId) ?: return@use
        val eventToCheck = liveChunk.timelineEvents.find(eventId)
        isEventRead = when {
            eventToCheck == null                -> handleMissingEvent(realm, liveChunk, roomId, userId, eventId)
            eventToCheck.root?.sender == userId -> true
            else                                -> {
                val readReceipt = ReadReceiptEntity.where(realm, roomId, userId).findFirst()
                        ?: return@use
                val readReceiptIndex = liveChunk.timelineEvents.find(readReceipt.eventId)?.displayIndex
                        ?: Int.MIN_VALUE
                val eventToCheckIndex = eventToCheck.displayIndex

                eventToCheckIndex <= readReceiptIndex
            }
        }
    }

    return isEventRead
}

private fun handleMissingEvent(realm: Realm, latestChunkEntity: ChunkEntity, roomId: String, userId: String, eventId: String): Boolean {
    return if (realm.doesEventExistInChunkHistory(eventId) && realm.hasReadReceiptInLatestChunk(latestChunkEntity, roomId, userId)) {
        MARK_OLD_EVENT_AS_READ
    } else {
        // This can happen when fast lane events are displayed before the database finishes updating
        MARK_UNREAD_DUE_TO_FASTLANE
    }
}

private fun Realm.doesEventExistInChunkHistory(eventId: String): Boolean {
    return ChunkEntity.findIncludingEvent(this, eventId) != null
}

private fun Realm.hasReadReceiptInLatestChunk(latestChunkEntity: ChunkEntity, roomId: String, userId: String) : Boolean {
    return ReadReceiptEntity.where(this, roomId, userId).findFirst()?.let {
        latestChunkEntity.timelineEvents.find(it.eventId)
    } != null
}

internal fun isReadMarkerMoreRecent(realmConfiguration: RealmConfiguration,
                                    roomId: String?,
                                    eventId: String?): Boolean {
    if (roomId.isNullOrBlank() || eventId.isNullOrBlank()) {
        return false
    }
    return Realm.getInstance(realmConfiguration).use { realm ->
        val eventToCheck = TimelineEventEntity.where(realm, roomId = roomId, eventId = eventId).findFirst()
        val eventToCheckChunk = eventToCheck?.chunk?.firstOrNull()
        val readMarker = ReadMarkerEntity.where(realm, roomId).findFirst() ?: return false
        val readMarkerEvent = TimelineEventEntity.where(realm, roomId = roomId, eventId = readMarker.eventId).findFirst()
        val readMarkerChunk = readMarkerEvent?.chunk?.firstOrNull()
        if (eventToCheckChunk == readMarkerChunk) {
            val readMarkerIndex = readMarkerEvent?.displayIndex ?: Int.MIN_VALUE
            val eventToCheckIndex = eventToCheck?.displayIndex ?: Int.MAX_VALUE
            eventToCheckIndex <= readMarkerIndex
        } else {
            eventToCheckChunk?.isLastForward == false
        }
    }
}
