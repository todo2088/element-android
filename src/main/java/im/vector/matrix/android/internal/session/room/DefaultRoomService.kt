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

package im.vector.matrix.android.internal.session.room

import androidx.lifecycle.LiveData
import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.session.room.Room
import im.vector.matrix.android.api.session.room.RoomService
import im.vector.matrix.android.api.session.room.RoomSummaryQueryParams
import im.vector.matrix.android.api.session.room.model.RoomSummary
import im.vector.matrix.android.api.session.room.model.VersioningState
import im.vector.matrix.android.api.session.room.model.create.CreateRoomParams
import im.vector.matrix.android.api.util.Cancelable
import im.vector.matrix.android.api.util.Optional
import im.vector.matrix.android.internal.database.mapper.RoomSummaryMapper
import im.vector.matrix.android.internal.database.model.RoomSummaryEntity
import im.vector.matrix.android.internal.database.model.RoomSummaryEntityFields
import im.vector.matrix.android.internal.database.query.findByAlias
import im.vector.matrix.android.internal.database.query.where
import im.vector.matrix.android.internal.query.process
import im.vector.matrix.android.internal.session.room.alias.GetRoomIdByAliasTask
import im.vector.matrix.android.internal.session.room.create.CreateRoomTask
import im.vector.matrix.android.internal.session.room.membership.joining.JoinRoomTask
import im.vector.matrix.android.internal.session.room.read.MarkAllRoomsReadTask
import im.vector.matrix.android.internal.session.user.accountdata.UpdateBreadcrumbsTask
import im.vector.matrix.android.internal.task.TaskExecutor
import im.vector.matrix.android.internal.task.configureWith
import im.vector.matrix.android.internal.util.fetchCopyMap
import io.realm.Realm
import io.realm.RealmQuery
import javax.inject.Inject

internal class DefaultRoomService @Inject constructor(
        private val monarchy: Monarchy,
        private val roomSummaryMapper: RoomSummaryMapper,
        private val createRoomTask: CreateRoomTask,
        private val joinRoomTask: JoinRoomTask,
        private val markAllRoomsReadTask: MarkAllRoomsReadTask,
        private val updateBreadcrumbsTask: UpdateBreadcrumbsTask,
        private val roomIdByAliasTask: GetRoomIdByAliasTask,
        private val roomGetter: RoomGetter,
        private val taskExecutor: TaskExecutor
) : RoomService {

    override fun createRoom(createRoomParams: CreateRoomParams, callback: MatrixCallback<String>): Cancelable {
        return createRoomTask
                .configureWith(createRoomParams) {
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    override fun getRoom(roomId: String): Room? {
        return roomGetter.getRoom(roomId)
    }

    override fun getExistingDirectRoomWithUser(otherUserId: String): Room? {
        return roomGetter.getDirectRoomWith(otherUserId)
    }

    override fun getRoomSummary(roomIdOrAlias: String): RoomSummary? {
        return monarchy
                .fetchCopyMap({
                    if (roomIdOrAlias.startsWith("!")) {
                        // It's a roomId
                        RoomSummaryEntity.where(it, roomId = roomIdOrAlias).findFirst()
                    } else {
                        // Assume it's a room alias
                        RoomSummaryEntity.findByAlias(it, roomIdOrAlias)
                    }
                }, { entity, _ ->
                    roomSummaryMapper.map(entity)
                })
    }

    override fun getRoomSummaries(queryParams: RoomSummaryQueryParams): List<RoomSummary> {
        return monarchy.fetchAllMappedSync(
                { roomSummariesQuery(it, queryParams) },
                { roomSummaryMapper.map(it) }
        )
    }

    override fun getRoomSummariesLive(queryParams: RoomSummaryQueryParams): LiveData<List<RoomSummary>> {
        return monarchy.findAllMappedWithChanges(
                { roomSummariesQuery(it, queryParams) },
                { roomSummaryMapper.map(it) }
        )
    }

    private fun roomSummariesQuery(realm: Realm, queryParams: RoomSummaryQueryParams): RealmQuery<RoomSummaryEntity> {
        val query = RoomSummaryEntity.where(realm)
        query.process(RoomSummaryEntityFields.DISPLAY_NAME, queryParams.displayName)
        query.process(RoomSummaryEntityFields.CANONICAL_ALIAS, queryParams.canonicalAlias)
        query.process(RoomSummaryEntityFields.MEMBERSHIP_STR, queryParams.memberships)
        query.notEqualTo(RoomSummaryEntityFields.VERSIONING_STATE_STR, VersioningState.UPGRADED_ROOM_JOINED.name)
        return query
    }

    override fun getBreadcrumbs(queryParams: RoomSummaryQueryParams): List<RoomSummary> {
        return monarchy.fetchAllMappedSync(
                { breadcrumbsQuery(it, queryParams) },
                { roomSummaryMapper.map(it) }
        )
    }

    override fun getBreadcrumbsLive(queryParams: RoomSummaryQueryParams): LiveData<List<RoomSummary>> {
        return monarchy.findAllMappedWithChanges(
                { breadcrumbsQuery(it, queryParams) },
                { roomSummaryMapper.map(it) }
        )
    }

    private fun breadcrumbsQuery(realm: Realm, queryParams: RoomSummaryQueryParams): RealmQuery<RoomSummaryEntity> {
        return roomSummariesQuery(realm, queryParams)
                .greaterThan(RoomSummaryEntityFields.BREADCRUMBS_INDEX, RoomSummary.NOT_IN_BREADCRUMBS)
                .sort(RoomSummaryEntityFields.BREADCRUMBS_INDEX)
    }

    override fun onRoomDisplayed(roomId: String): Cancelable {
        return updateBreadcrumbsTask
                .configureWith(UpdateBreadcrumbsTask.Params(roomId))
                .executeBy(taskExecutor)
    }

    override fun joinRoom(roomIdOrAlias: String, reason: String?, viaServers: List<String>, callback: MatrixCallback<Unit>): Cancelable {
        return joinRoomTask
                .configureWith(JoinRoomTask.Params(roomIdOrAlias, reason, viaServers)) {
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    override fun markAllAsRead(roomIds: List<String>, callback: MatrixCallback<Unit>): Cancelable {
        return markAllRoomsReadTask
                .configureWith(MarkAllRoomsReadTask.Params(roomIds)) {
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    override fun getRoomIdByAlias(roomAlias: String, searchOnServer: Boolean, callback: MatrixCallback<Optional<String>>): Cancelable {
        return roomIdByAliasTask
                .configureWith(GetRoomIdByAliasTask.Params(roomAlias, searchOnServer)) {
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }
}
