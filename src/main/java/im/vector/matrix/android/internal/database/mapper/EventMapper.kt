package im.vector.matrix.android.internal.database.mapper

import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.UnsignedData
import im.vector.matrix.android.internal.database.model.EventEntity
import im.vector.matrix.android.internal.di.MoshiProvider


internal object EventMapper {

    private val moshi = MoshiProvider.providesMoshi()
    private val adapter = moshi.adapter<Map<String, Any>>(Event.CONTENT_TYPE)

    fun map(event: Event): EventEntity {
        val eventEntity = EventEntity()
        eventEntity.eventId = event.eventId ?: ""
        eventEntity.content = adapter.toJson(event.content)
        val resolvedPrevContent = event.prevContent ?: event.unsignedData?.prevContent
        eventEntity.prevContent = adapter.toJson(resolvedPrevContent)
        eventEntity.stateKey = event.stateKey
        eventEntity.type = event.type
        eventEntity.sender = event.sender
        eventEntity.originServerTs = event.originServerTs
        eventEntity.redacts = event.redacts
        eventEntity.age = event.unsignedData?.age ?: event.originServerTs
        return eventEntity
    }

    fun map(eventEntity: EventEntity): Event {
        return Event(
                type = eventEntity.type,
                eventId = eventEntity.eventId,
                content = adapter.fromJson(eventEntity.content),
                prevContent = adapter.fromJson(eventEntity.prevContent ?: ""),
                originServerTs = eventEntity.originServerTs,
                sender = eventEntity.sender,
                stateKey = eventEntity.stateKey,
                roomId = null,
                unsignedData = UnsignedData(eventEntity.age),
                redacts = eventEntity.redacts
        )
    }
}

internal fun EventEntity.asDomain(): Event {
    return EventMapper.map(this)
}

internal fun Event.asEntity(): EventEntity {
    return EventMapper.map(this)
}