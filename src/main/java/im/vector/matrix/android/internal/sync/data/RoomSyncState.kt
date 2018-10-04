/*
 * Copyright 2016 OpenMarket Ltd
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
package im.vector.matrix.android.internal.sync.data


import com.squareup.moshi.JsonClass
import im.vector.matrix.android.api.events.Event

// RoomSyncState represents the state updates for a room during server sync v2.
@JsonClass(generateAdapter = true)
data class RoomSyncState(

        /**
         * List of state events (array of Event). The resulting state corresponds to the *start* of the timeline.
         */
        val events: List<Event>? = null)
