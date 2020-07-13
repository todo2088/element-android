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

package im.vector.matrix.android.internal.session.room.timeline

import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.api.session.events.model.isImageMessage
import im.vector.matrix.android.api.session.events.model.isVideoMessage
import im.vector.matrix.android.api.session.room.timeline.Timeline
import im.vector.matrix.android.api.session.room.timeline.TimelineEvent
import im.vector.matrix.android.api.session.room.timeline.TimelineService
import im.vector.matrix.android.api.session.room.timeline.TimelineSettings
import im.vector.matrix.android.api.util.Optional
import im.vector.matrix.android.api.util.toOptional
import im.vector.matrix.android.internal.crypto.store.db.doWithRealm
import im.vector.matrix.android.internal.database.mapper.ReadReceiptsSummaryMapper
import im.vector.matrix.android.internal.database.mapper.TimelineEventMapper
import im.vector.matrix.android.internal.database.model.TimelineEventEntity
import im.vector.matrix.android.internal.database.model.TimelineEventEntityFields
import im.vector.matrix.android.internal.database.query.where
import im.vector.matrix.android.internal.di.SessionDatabase
import im.vector.matrix.android.internal.task.TaskExecutor
import im.vector.matrix.android.internal.util.fetchCopyMap
import io.realm.Sort
import io.realm.kotlin.where
import org.greenrobot.eventbus.EventBus

internal class DefaultTimelineService @AssistedInject constructor(@Assisted private val roomId: String,
                                                                  @SessionDatabase private val monarchy: Monarchy,
                                                                  private val eventBus: EventBus,
                                                                  private val taskExecutor: TaskExecutor,
                                                                  private val contextOfEventTask: GetContextOfEventTask,
                                                                  private val eventDecryptor: TimelineEventDecryptor,
                                                                  private val paginationTask: PaginationTask,
                                                                  private val fetchTokenAndPaginateTask: FetchTokenAndPaginateTask,
                                                                  private val timelineEventMapper: TimelineEventMapper,
                                                                  private val readReceiptsSummaryMapper: ReadReceiptsSummaryMapper
) : TimelineService {

    @AssistedInject.Factory
    interface Factory {
        fun create(roomId: String): TimelineService
    }

    override fun createTimeline(eventId: String?, settings: TimelineSettings): Timeline {
        return DefaultTimeline(
                roomId = roomId,
                initialEventId = eventId,
                realmConfiguration = monarchy.realmConfiguration,
                taskExecutor = taskExecutor,
                contextOfEventTask = contextOfEventTask,
                paginationTask = paginationTask,
                timelineEventMapper = timelineEventMapper,
                settings = settings,
                hiddenReadReceipts = TimelineHiddenReadReceipts(readReceiptsSummaryMapper, roomId, settings),
                eventBus = eventBus,
                eventDecryptor = eventDecryptor,
                fetchTokenAndPaginateTask = fetchTokenAndPaginateTask
        )
    }

    override fun getTimeLineEvent(eventId: String): TimelineEvent? {
        return monarchy
                .fetchCopyMap({
                    TimelineEventEntity.where(it, roomId = roomId, eventId = eventId).findFirst()
                }, { entity, _ ->
                    timelineEventMapper.map(entity)
                })
    }

    override fun getTimeLineEventLive(eventId: String): LiveData<Optional<TimelineEvent>> {
        val liveData = monarchy.findAllMappedWithChanges(
                { TimelineEventEntity.where(it, roomId = roomId, eventId = eventId) },
                { timelineEventMapper.map(it) }
        )
        return Transformations.map(liveData) { events ->
            events.firstOrNull().toOptional()
        }
    }

    override fun getAttachmentMessages(): List<TimelineEvent> {
        // TODO pretty bad query.. maybe we should denormalize clear type in base?
        return doWithRealm(monarchy.realmConfiguration) { realm ->
            realm.where<TimelineEventEntity>()
                    .equalTo(TimelineEventEntityFields.ROOM_ID, roomId)
                    .sort(TimelineEventEntityFields.DISPLAY_INDEX, Sort.ASCENDING)
                    .findAll()
                    ?.mapNotNull { timelineEventMapper.map(it).takeIf { it.root.isImageMessage() || it.root.isVideoMessage() } }
                    ?: emptyList()
        }
    }
}
