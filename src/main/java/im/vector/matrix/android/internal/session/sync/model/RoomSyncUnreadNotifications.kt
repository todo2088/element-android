/*
 *
 *  * Copyright 2019 New Vector Ltd
 *  *
 *  * Licensed under the Apache License, Version 2.0 (the "License");
 *  * you may not use this file except in compliance with the License.
 *  * You may obtain a copy of the License at
 *  *
 *  *     http://www.apache.org/licenses/LICENSE-2.0
 *  *
 *  * Unless required by applicable law or agreed to in writing, software
 *  * distributed under the License is distributed on an "AS IS" BASIS,
 *  * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  * See the License for the specific language governing permissions and
 *  * limitations under the License.
 *
 */

package im.vector.matrix.android.internal.session.sync.model


import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import im.vector.matrix.android.api.session.events.model.Event

/**
 * `MXRoomSyncUnreadNotifications` represents the unread counts for a room.
 */
@JsonClass(generateAdapter = true)
internal data class RoomSyncUnreadNotifications(
        /**
         * List of account data events (array of Event).
         */
        @Json(name = "events") val events: List<Event>? = null,

        /**
         * The number of unread messages that match the push notification rules.
         */
        @Json(name = "notification_count") val notificationCount: Int? = null,

        /**
         * The number of highlighted unread messages (subset of notifications).
         */
        @Json(name = "highlight_count") val highlightCount: Int? = null)