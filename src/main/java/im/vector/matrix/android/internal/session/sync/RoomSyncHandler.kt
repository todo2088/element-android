package im.vector.matrix.android.internal.session.sync

import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.room.model.MyMembership
import im.vector.matrix.android.internal.database.helper.addAll
import im.vector.matrix.android.internal.database.helper.addOrUpdate
import im.vector.matrix.android.internal.database.helper.addStateEvents
import im.vector.matrix.android.internal.database.helper.lastStateIndex
import im.vector.matrix.android.internal.database.model.ChunkEntity
import im.vector.matrix.android.internal.database.model.RoomEntity
import im.vector.matrix.android.internal.database.model.RoomSummaryEntity
import im.vector.matrix.android.internal.database.query.findLastLiveChunkFromRoom
import im.vector.matrix.android.internal.database.query.where
import im.vector.matrix.android.internal.session.room.timeline.PaginationDirection
import im.vector.matrix.android.internal.session.sync.model.*
import io.realm.Realm
import io.realm.kotlin.createObject


internal class RoomSyncHandler(private val monarchy: Monarchy,
                               private val readReceiptHandler: ReadReceiptHandler) {

    sealed class HandlingStrategy {
        data class JOINED(val data: Map<String, RoomSync>) : HandlingStrategy()
        data class INVITED(val data: Map<String, InvitedRoomSync>) : HandlingStrategy()
        data class LEFT(val data: Map<String, RoomSync>) : HandlingStrategy()
    }

    fun handle(roomsSyncResponse: RoomsSyncResponse) {
        monarchy.runTransactionSync { realm ->
            handleRoomSync(realm, RoomSyncHandler.HandlingStrategy.JOINED(roomsSyncResponse.join))
            handleRoomSync(realm, RoomSyncHandler.HandlingStrategy.INVITED(roomsSyncResponse.invite))
            handleRoomSync(realm, RoomSyncHandler.HandlingStrategy.LEFT(roomsSyncResponse.leave))
        }
    }

    // PRIVATE METHODS *****************************************************************************

    private fun handleRoomSync(realm: Realm, handlingStrategy: HandlingStrategy) {
        val rooms = when (handlingStrategy) {
            is HandlingStrategy.JOINED -> handlingStrategy.data.map { handleJoinedRoom(realm, it.key, it.value) }
            is HandlingStrategy.INVITED -> handlingStrategy.data.map { handleInvitedRoom(realm, it.key, it.value) }
            is HandlingStrategy.LEFT -> handlingStrategy.data.map { handleLeftRoom(it.key, it.value) }
        }
        realm.insertOrUpdate(rooms)
    }

    private fun handleJoinedRoom(realm: Realm,
                                 roomId: String,
                                 roomSync: RoomSync): RoomEntity {

        val roomEntity = RoomEntity.where(realm, roomId).findFirst()
                ?: realm.createObject(roomId)

        if (roomEntity.membership == MyMembership.INVITED) {
            roomEntity.chunks.deleteAllFromRealm()
        }

        roomEntity.membership = MyMembership.JOINED

        val lastChunk = ChunkEntity.findLastLiveChunkFromRoom(realm, roomId)
        val isInitialSync = lastChunk == null
        val lastStateIndex = lastChunk?.lastStateIndex(PaginationDirection.FORWARDS) ?: 0
        val numberOfStateEvents = roomSync.state?.events?.size ?: 0
        val stateIndexOffset = lastStateIndex + numberOfStateEvents

        if (roomSync.state != null && roomSync.state.events.isNotEmpty()) {
            val untimelinedStateIndex = if (isInitialSync) Int.MIN_VALUE else stateIndexOffset
            roomEntity.addStateEvents(roomSync.state.events, stateIndex = untimelinedStateIndex)
        }

        if (roomSync.timeline != null && roomSync.timeline.events.isNotEmpty()) {
            val timelineStateOffset = if (isInitialSync || roomSync.timeline.limited.not()) 0 else stateIndexOffset
            val chunkEntity = handleTimelineEvents(
                    realm,
                    roomId,
                    roomSync.timeline.events,
                    roomSync.timeline.prevToken,
                    roomSync.timeline.limited,
                    timelineStateOffset
            )
            roomEntity.addOrUpdate(chunkEntity)
        }

        if (roomSync.summary != null) {
            handleRoomSummary(realm, roomId, roomSync.summary)
        }

        if (roomSync.ephemeral != null && roomSync.ephemeral.events.isNotEmpty()) {
            handleEphemeral(realm, roomId, roomSync.ephemeral)
        }
        return roomEntity
    }

    private fun handleInvitedRoom(realm: Realm,
                                  roomId: String,
                                  roomSync:
                                  InvitedRoomSync): RoomEntity {
        val roomEntity = RoomEntity()
        roomEntity.roomId = roomId
        roomEntity.membership = MyMembership.INVITED
        if (roomSync.inviteState != null && roomSync.inviteState.events.isNotEmpty()) {
            val chunkEntity = handleTimelineEvents(realm, roomId, roomSync.inviteState.events)
            roomEntity.addOrUpdate(chunkEntity)
        }
        return roomEntity
    }

    // TODO : handle it
    private fun handleLeftRoom(roomId: String,
                               roomSync: RoomSync): RoomEntity {
        return RoomEntity().apply {
            this.roomId = roomId
            this.membership = MyMembership.LEFT
        }
    }

    private fun handleTimelineEvents(realm: Realm,
                                     roomId: String,
                                     eventList: List<Event>,
                                     prevToken: String? = null,
                                     isLimited: Boolean = true,
                                     stateIndexOffset: Int = 0): ChunkEntity {

        val lastChunk = ChunkEntity.findLastLiveChunkFromRoom(realm, roomId)
        val chunkEntity = if (!isLimited && lastChunk != null) {
            lastChunk
        } else {
            realm.createObject<ChunkEntity>().apply { this.prevToken = prevToken }
        }

        lastChunk?.isLast = false
        chunkEntity.isLast = true
        chunkEntity.addAll(roomId, eventList, PaginationDirection.FORWARDS, stateIndexOffset)
        return chunkEntity
    }

    private fun handleRoomSummary(realm: Realm,
                                  roomId: String,
                                  roomSummary: RoomSyncSummary) {

        val roomSummaryEntity = RoomSummaryEntity.where(realm, roomId).findFirst()
                ?: RoomSummaryEntity(roomId)

        if (roomSummary.heroes.isNotEmpty()) {
            roomSummaryEntity.heroes.clear()
            roomSummaryEntity.heroes.addAll(roomSummary.heroes)
        }
        if (roomSummary.invitedMembersCount != null) {
            roomSummaryEntity.invitedMembersCount = roomSummary.invitedMembersCount
        }
        if (roomSummary.joinedMembersCount != null) {
            roomSummaryEntity.joinedMembersCount = roomSummary.joinedMembersCount
        }
        realm.insertOrUpdate(roomSummaryEntity)
    }

    private fun handleEphemeral(realm: Realm,
                                roomId: String,
                                ephemeral: RoomSyncEphemeral) {
        ephemeral.events
                .filter { it.type == EventType.RECEIPT }
                .map { it.content.toModel<ReadReceiptContent>() }
                .flatMap { readReceiptHandler.handle(realm, roomId, it) }
    }
}