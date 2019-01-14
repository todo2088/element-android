package im.vector.matrix.android.internal.session.room.timeline

import android.arch.paging.PagedList
import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.session.events.model.EnrichedEvent
import im.vector.matrix.android.internal.database.model.ChunkEntity
import im.vector.matrix.android.internal.database.query.findAllIncludingEvents
import im.vector.matrix.android.internal.task.TaskExecutor
import im.vector.matrix.android.internal.task.configureWith
import im.vector.matrix.android.internal.util.PagingRequestHelper
import java.util.*

internal class TimelineBoundaryCallback(private val roomId: String,
                                        private val taskExecutor: TaskExecutor,
                                        private val paginationTask: PaginationTask,
                                        private val monarchy: Monarchy,
                                        private val helper: PagingRequestHelper
) : PagedList.BoundaryCallback<EnrichedEvent>() {

    var limit = 30

    override fun onZeroItemsLoaded() {
        // actually, it's not possible
    }

    override fun onItemAtEndLoaded(itemAtEnd: EnrichedEvent) {
        helper.runIfNotRunning(PagingRequestHelper.RequestType.AFTER) {
            runPaginationRequest(it, itemAtEnd, PaginationDirection.BACKWARDS)
        }
    }

    override fun onItemAtFrontLoaded(itemAtFront: EnrichedEvent) {
        helper.runIfNotRunning(PagingRequestHelper.RequestType.BEFORE) {
            runPaginationRequest(it, itemAtFront, PaginationDirection.FORWARDS)
        }
    }

    private fun runPaginationRequest(requestCallback: PagingRequestHelper.Request.Callback,
                                     item: EnrichedEvent,
                                     direction: PaginationDirection) {
        var token: String? = null
        monarchy.doWithRealm { realm ->
            if (item.root.eventId == null) {
                return@doWithRealm
            }
            val chunkEntity = ChunkEntity.findAllIncludingEvents(realm, Collections.singletonList(item.root.eventId)).firstOrNull()
            token = if (direction == PaginationDirection.FORWARDS) chunkEntity?.nextToken else chunkEntity?.prevToken
        }
        val params = PaginationTask.Params(roomId = roomId,
                                           from = token,
                                           direction = direction,
                                           limit = limit)

        paginationTask.configureWith(params)
                .enableRetry()
                .dispatchTo(object : MatrixCallback<TokenChunkEvent> {
                    override fun onSuccess(data: TokenChunkEvent) {
                        requestCallback.recordSuccess()
                    }

                    override fun onFailure(failure: Throwable) {
                        requestCallback.recordFailure(failure)
                    }
                })
                .executeBy(taskExecutor)
    }

}
