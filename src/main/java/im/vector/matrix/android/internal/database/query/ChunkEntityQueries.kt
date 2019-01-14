package im.vector.matrix.android.internal.database.query

import im.vector.matrix.android.internal.database.model.ChunkEntity
import im.vector.matrix.android.internal.database.model.ChunkEntityFields
import im.vector.matrix.android.internal.database.model.RoomEntityFields
import io.realm.Realm
import io.realm.RealmQuery
import io.realm.RealmResults
import io.realm.kotlin.createObject
import io.realm.kotlin.where

internal fun ChunkEntity.Companion.where(realm: Realm, roomId: String): RealmQuery<ChunkEntity> {
    return realm.where<ChunkEntity>()
            .equalTo("${ChunkEntityFields.ROOM}.${RoomEntityFields.ROOM_ID}", roomId)
}

internal fun ChunkEntity.Companion.find(realm: Realm, roomId: String, prevToken: String? = null, nextToken: String? = null): ChunkEntity? {
    val query = where(realm, roomId)
    if (prevToken != null) {
        query.equalTo(ChunkEntityFields.PREV_TOKEN, prevToken)
    }
    if (nextToken != null) {
        query.equalTo(ChunkEntityFields.NEXT_TOKEN, nextToken)
    }
    return query.findFirst()
}

internal fun ChunkEntity.Companion.findLastLiveChunkFromRoom(realm: Realm, roomId: String): ChunkEntity? {
    return where(realm, roomId)
            .equalTo(ChunkEntityFields.IS_LAST, true)
            .findFirst()
}

internal fun ChunkEntity.Companion.findAllIncludingEvents(realm: Realm, eventIds: List<String>): RealmResults<ChunkEntity> {
    return realm.where<ChunkEntity>()
            .`in`(ChunkEntityFields.EVENTS.EVENT_ID, eventIds.toTypedArray())
            .findAll()
}

internal fun ChunkEntity.Companion.findIncludingEvent(realm: Realm, eventId: String): ChunkEntity? {
    return findAllIncludingEvents(realm, listOf(eventId)).firstOrNull()
}

internal fun ChunkEntity.Companion.create(realm: Realm, prevToken: String?, nextToken: String?): ChunkEntity {
    return realm.createObject<ChunkEntity>().apply {
        this.prevToken = prevToken
        this.nextToken = nextToken
    }
}