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

package im.vector.matrix.android.internal.session.room.membership

import android.content.Context
import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.R
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.room.model.*
import im.vector.matrix.android.internal.database.mapper.ContentMapper
import im.vector.matrix.android.internal.database.model.*
import im.vector.matrix.android.internal.database.model.EventEntity
import im.vector.matrix.android.internal.database.model.RoomEntity
import im.vector.matrix.android.internal.database.model.RoomMemberSummaryEntity
import im.vector.matrix.android.internal.database.model.RoomSummaryEntity
import im.vector.matrix.android.internal.database.query.prev
import im.vector.matrix.android.internal.database.query.where
import im.vector.matrix.android.internal.di.UserId
import javax.inject.Inject

/**
 * This class computes room display name
 */
internal class RoomDisplayNameResolver @Inject constructor(private val context: Context,
                                                           private val monarchy: Monarchy,
                                                           @UserId private val userId: String
) {

    /**
     * Compute the room display name
     *
     * @param roomId: the roomId to resolve the name of.
     * @return the room display name
     */
    fun resolve(roomId: String): CharSequence {
        // this algorithm is the one defined in
        // https://github.com/matrix-org/matrix-js-sdk/blob/develop/lib/models/room.js#L617
        // calculateRoomName(room, userId)

        // For Lazy Loaded room, see algorithm here:
        // https://docs.google.com/document/d/11i14UI1cUz-OJ0knD5BFu7fmT6Fo327zvMYqfSAR7xs/edit#heading=h.qif6pkqyjgzn
        var name: CharSequence? = null
        monarchy.doWithRealm { realm ->
            val roomEntity = RoomEntity.where(realm, roomId = roomId).findFirst()
            val roomName = EventEntity.where(realm, roomId, EventType.STATE_ROOM_NAME).prev()
            name = ContentMapper.map(roomName?.content).toModel<RoomNameContent>()?.name
            if (!name.isNullOrEmpty()) {
                return@doWithRealm
            }

            val canonicalAlias = EventEntity.where(realm, roomId, EventType.STATE_ROOM_CANONICAL_ALIAS).prev()
            name = ContentMapper.map(canonicalAlias?.content).toModel<RoomCanonicalAliasContent>()?.canonicalAlias
            if (!name.isNullOrEmpty()) {
                return@doWithRealm
            }

            val aliases = EventEntity.where(realm, roomId, EventType.STATE_ROOM_ALIASES).prev()
            name = ContentMapper.map(aliases?.content).toModel<RoomAliasesContent>()?.aliases?.firstOrNull()
            if (!name.isNullOrEmpty()) {
                return@doWithRealm
            }

            val roomMembers = RoomMemberHelper(realm, roomId)
            val activeMembers = roomMembers.queryActiveRoomMembersEvent().findAll()

            if (roomEntity?.membership == Membership.INVITE) {
                val inviteMeEvent = roomMembers.getLastStateEvent(userId)
                val inviterId = inviteMeEvent?.sender
                name = if (inviterId != null) {
                    activeMembers.where()
                            .equalTo(RoomMemberSummaryEntityFields.USER_ID, inviterId)
                            .findFirst()
                            ?.displayName
                } else {
                    context.getString(R.string.room_displayname_room_invite)
                }
            } else if (roomEntity?.membership == Membership.JOIN) {
                val roomSummary = RoomSummaryEntity.where(realm, roomId).findFirst()
                val otherMembersSubset: List<RoomMemberSummaryEntity> = if (roomSummary?.heroes?.isNotEmpty() == true) {
                    roomSummary.heroes.mapNotNull { userId ->
                        roomMembers.getLastRoomMember(userId)?.takeIf {
                            it.membership == Membership.INVITE || it.membership == Membership.JOIN
                        }
                    }
                } else {
                    activeMembers.where()
                            .notEqualTo(RoomMemberSummaryEntityFields.USER_ID, userId)
                            .limit(3)
                            .findAll()
                            .createSnapshot()
                }
                val otherMembersCount = otherMembersSubset.count()
                name = when (otherMembersCount) {
                    0    -> context.getString(R.string.room_displayname_empty_room)
                    1    -> resolveRoomMemberName(otherMembersSubset[0], roomMembers)
                    2    -> context.getString(R.string.room_displayname_two_members,
                            resolveRoomMemberName(otherMembersSubset[0], roomMembers),
                            resolveRoomMemberName(otherMembersSubset[1], roomMembers)
                    )
                    else -> context.resources.getQuantityString(R.plurals.room_displayname_three_and_more_members,
                            roomMembers.getNumberOfJoinedMembers() - 1,
                            resolveRoomMemberName(otherMembersSubset[0], roomMembers),
                            roomMembers.getNumberOfJoinedMembers() - 1)
                }
            }
            return@doWithRealm
        }
        return name ?: roomId
    }

    private fun resolveRoomMemberName(roomMemberSummary: RoomMemberSummaryEntity?,
                                      roomMemberHelper: RoomMemberHelper): String? {
        if (roomMemberSummary == null) return null
        val isUnique = roomMemberHelper.isUniqueDisplayName(roomMemberSummary.displayName)
        return if (isUnique) {
            roomMemberSummary.displayName
        } else {
            "${roomMemberSummary.displayName} (${roomMemberSummary.userId})"
        }
    }
}
