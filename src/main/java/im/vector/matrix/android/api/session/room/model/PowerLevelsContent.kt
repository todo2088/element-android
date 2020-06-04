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

package im.vector.matrix.android.api.session.room.model

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import im.vector.matrix.android.api.session.room.powerlevels.Role

/**
 * Class representing the EventType.EVENT_TYPE_STATE_ROOM_POWER_LEVELS state event content.
 */
@JsonClass(generateAdapter = true)
data class PowerLevelsContent(
        @Json(name = "ban") val ban: Int = Role.Moderator.value,
        @Json(name = "kick") val kick: Int = Role.Moderator.value,
        @Json(name = "invite") val invite: Int = Role.Moderator.value,
        @Json(name = "redact") val redact: Int = Role.Moderator.value,
        @Json(name = "events_default") val eventsDefault: Int = Role.Default.value,
        @Json(name = "events") val events: MutableMap<String, Int> = HashMap(),
        @Json(name = "users_default") val usersDefault: Int = Role.Default.value,
        @Json(name = "users") val users: MutableMap<String, Int> = HashMap(),
        @Json(name = "state_default") val stateDefault: Int = Role.Moderator.value,
        @Json(name = "notifications") val notifications: Map<String, Any> = HashMap()
)
