package im.vector.matrix.android.internal.database.helper

import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.internal.database.mapper.toEntity
import im.vector.matrix.android.internal.database.mapper.updateWith
import im.vector.matrix.android.internal.database.model.ChunkEntity
import im.vector.matrix.android.internal.database.model.RoomEntity


internal fun RoomEntity.deleteOnCascade(chunkEntity: ChunkEntity) {
    chunks.remove(chunkEntity)
    chunkEntity.deleteOnCascade()
}

internal fun RoomEntity.addOrUpdate(chunkEntity: ChunkEntity) {
    chunkEntity.updateDisplayIndexes()
    if (!chunks.contains(chunkEntity)) {
        chunks.add(chunkEntity)
    }
}

internal fun RoomEntity.addStateEvents(stateEvents: List<Event>,
                                       stateIndex: Int = Int.MIN_VALUE,
                                       isUnlinked: Boolean = false) {
    if (!isManaged) {
        throw IllegalStateException("Chunk entity should be managed to use fast contains")
    }
    stateEvents.forEach { event ->
        if (event.eventId == null) {
            return@forEach
        }
        val eventEntity = event.toEntity(roomId)
        eventEntity.updateWith(stateIndex, isUnlinked)
        untimelinedStateEvents.add(eventEntity)
    }
}