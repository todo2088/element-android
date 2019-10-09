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
import im.vector.matrix.android.api.session.events.model.UnsignedData

/**
 * Class representing the EventType.STATE_ROOM_MEMBER state event content
 */
@JsonClass(generateAdapter = true)
data class RoomMember(
        @Json(name = "membership") val membership: Membership,
        @Json(name = "displayname") val displayName: String? = null,
        @Json(name = "avatar_url") val avatarUrl: String? = null,
        @Json(name = "is_direct") val isDirect: Boolean = false,
        @Json(name = "third_party_invite") val thirdPartyInvite: Invite? = null,
        @Json(name = "unsigned") val unsignedData: UnsignedData? = null
)
