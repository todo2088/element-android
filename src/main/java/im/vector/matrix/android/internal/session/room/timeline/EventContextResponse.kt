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

package im.vector.matrix.android.internal.session.room.timeline

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import im.vector.matrix.android.api.session.events.model.Event

@JsonClass(generateAdapter = true)
data class EventContextResponse(
        @Json(name = "event") val event: Event,
        @Json(name = "start") override val start: String? = null,
        @Json(name = "events_before") val eventsBefore: List<Event> = emptyList(),
        @Json(name = "events_after") val eventsAfter: List<Event> = emptyList(),
        @Json(name = "end") override val end: String? = null,
        @Json(name = "state") override val stateEvents: List<Event> = emptyList()
) : TokenChunkEvent {

    override val events: List<Event>
        get() = listOf(event)
}
