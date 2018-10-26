package im.vector.matrix.android.internal.session.room.timeline

import android.arch.lifecycle.LiveData
import android.arch.paging.LivePagedListBuilder
import android.arch.paging.PagedList
import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.api.session.events.interceptor.EnrichedEventInterceptor
import im.vector.matrix.android.api.session.events.model.EnrichedEvent
import im.vector.matrix.android.api.session.room.TimelineHolder
import im.vector.matrix.android.internal.database.mapper.asDomain
import im.vector.matrix.android.internal.database.model.ChunkEntity
import im.vector.matrix.android.internal.database.model.EventEntityFields
import im.vector.matrix.android.internal.database.query.where
import im.vector.matrix.android.internal.session.events.interceptor.MessageEventInterceptor
import io.realm.Sort

class DefaultTimelineHolder(private val roomId: String,
                            private val monarchy: Monarchy,
                            private val boundaryCallback: TimelineBoundaryCallback
) : TimelineHolder {

    private val eventInterceptors = ArrayList<EnrichedEventInterceptor>()

    init {
        eventInterceptors.add(MessageEventInterceptor(monarchy))
    }

    override fun liveTimeline(): LiveData<PagedList<EnrichedEvent>> {
        val realmDataSourceFactory = monarchy.createDataSourceFactory { realm ->
            ChunkEntity.where(realm, roomId)
                    .findAll()
                    .last(null)
                    ?.let {
                        it.events.where().sort(EventEntityFields.ORIGIN_SERVER_TS, Sort.DESCENDING)
                    }
        }
        val domainSourceFactory = realmDataSourceFactory
                .map { it.asDomain() }
                .map { event ->
                    val enrichedEvent = EnrichedEvent(event)
                    eventInterceptors
                            .filter {
                                it.canEnrich(enrichedEvent)
                            }.forEach {
                                it.enrich(roomId, enrichedEvent)
                            }
                    enrichedEvent
                }

        val pagedListConfig = PagedList.Config.Builder()
                .setEnablePlaceholders(false)
                .setPageSize(10)
                .setPrefetchDistance(10)
                .build()

        val livePagedListBuilder = LivePagedListBuilder(domainSourceFactory, pagedListConfig).setBoundaryCallback(boundaryCallback)
        return monarchy.findAllPagedWithChanges(realmDataSourceFactory, livePagedListBuilder)
    }
}