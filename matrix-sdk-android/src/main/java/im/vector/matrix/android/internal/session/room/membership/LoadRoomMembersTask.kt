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

import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.api.session.room.model.Membership
import im.vector.matrix.android.api.session.room.send.SendState
import im.vector.matrix.android.internal.database.mapper.toEntity
import im.vector.matrix.android.internal.database.model.CurrentStateEventEntity
import im.vector.matrix.android.internal.database.model.EventInsertEntity
import im.vector.matrix.android.internal.database.model.EventInsertType
import im.vector.matrix.android.internal.database.model.RoomEntity
import im.vector.matrix.android.internal.database.query.copyToRealmOrIgnore
import im.vector.matrix.android.internal.database.query.getOrCreate
import im.vector.matrix.android.internal.database.query.where
import im.vector.matrix.android.internal.di.SessionDatabase
import im.vector.matrix.android.internal.network.executeRequest
import im.vector.matrix.android.internal.session.room.RoomAPI
import im.vector.matrix.android.internal.session.room.summary.RoomSummaryUpdater
import im.vector.matrix.android.internal.session.sync.SyncTokenStore
import im.vector.matrix.android.internal.task.Task
import im.vector.matrix.android.internal.util.awaitTransaction
import io.realm.Realm
import io.realm.kotlin.createObject
import org.greenrobot.eventbus.EventBus
import javax.inject.Inject

internal interface LoadRoomMembersTask : Task<LoadRoomMembersTask.Params, Unit> {

    data class Params(
            val roomId: String,
            val excludeMembership: Membership? = null
    )
}

internal class DefaultLoadRoomMembersTask @Inject constructor(
        private val roomAPI: RoomAPI,
        @SessionDatabase private val monarchy: Monarchy,
        private val syncTokenStore: SyncTokenStore,
        private val roomSummaryUpdater: RoomSummaryUpdater,
        private val roomMemberEventHandler: RoomMemberEventHandler,
        private val eventBus: EventBus
) : LoadRoomMembersTask {

    override suspend fun execute(params: LoadRoomMembersTask.Params) {
        if (areAllMembersAlreadyLoaded(params.roomId)) {
            return
        }
        val lastToken = syncTokenStore.getLastToken()
        val response = executeRequest<RoomMembersResponse>(eventBus) {
            apiCall = roomAPI.getMembers(params.roomId, lastToken, null, params.excludeMembership?.value)
        }
        insertInDb(response, params.roomId)
    }

    private suspend fun insertInDb(response: RoomMembersResponse, roomId: String) {
        monarchy.awaitTransaction { realm ->
            // We ignore all the already known members
            val roomEntity = RoomEntity.where(realm, roomId).findFirst()
                    ?: realm.createObject(roomId)
            val now = System.currentTimeMillis()
            for (roomMemberEvent in response.roomMemberEvents) {
                if (roomMemberEvent.eventId == null || roomMemberEvent.stateKey == null) {
                    continue
                }
                val ageLocalTs = roomMemberEvent.unsignedData?.age?.let { now - it }
                val eventEntity = roomMemberEvent.toEntity(roomId, SendState.SYNCED, ageLocalTs).copyToRealmOrIgnore(realm, EventInsertType.PAGINATION)
                CurrentStateEventEntity.getOrCreate(realm, roomId, roomMemberEvent.stateKey, roomMemberEvent.type).apply {
                    eventId = roomMemberEvent.eventId
                    root = eventEntity
                }
                roomMemberEventHandler.handle(realm, roomId, roomMemberEvent)
            }
            roomEntity.areAllMembersLoaded = true
            roomSummaryUpdater.update(realm, roomId, updateMembers = true)
        }
    }

    private fun areAllMembersAlreadyLoaded(roomId: String): Boolean {
        return Realm.getInstance(monarchy.realmConfiguration).use {
            RoomEntity.where(it, roomId).findFirst()?.areAllMembersLoaded ?: false
        }
    }
}
