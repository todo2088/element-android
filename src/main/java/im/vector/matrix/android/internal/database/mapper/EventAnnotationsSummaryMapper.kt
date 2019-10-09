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

package im.vector.matrix.android.internal.database.mapper

import im.vector.matrix.android.api.session.room.model.EditAggregatedSummary
import im.vector.matrix.android.api.session.room.model.EventAnnotationsSummary
import im.vector.matrix.android.api.session.room.model.ReactionAggregatedSummary
import im.vector.matrix.android.internal.database.model.EditAggregatedSummaryEntity
import im.vector.matrix.android.internal.database.model.EventAnnotationsSummaryEntity
import im.vector.matrix.android.internal.database.model.ReactionAggregatedSummaryEntity
import io.realm.RealmList

internal object EventAnnotationsSummaryMapper {
    fun map(annotationsSummary: EventAnnotationsSummaryEntity): EventAnnotationsSummary {
        return EventAnnotationsSummary(
                eventId = annotationsSummary.eventId,
                reactionsSummary = annotationsSummary.reactionsSummary.toList().map {
                    ReactionAggregatedSummary(
                            it.key,
                            it.count,
                            it.addedByMe,
                            it.firstTimestamp,
                            it.sourceEvents.toList(),
                            it.sourceLocalEcho.toList()
                    )
                },
                editSummary = annotationsSummary.editSummary?.let {
                    EditAggregatedSummary(
                            ContentMapper.map(it.aggregatedContent),
                            it.sourceEvents.toList(),
                            it.sourceLocalEchoEvents.toList(),
                            it.lastEditTs
                    )
                }
        )
    }

    fun map(annotationsSummary: EventAnnotationsSummary, roomId: String): EventAnnotationsSummaryEntity {
        val eventAnnotationsSummaryEntity = EventAnnotationsSummaryEntity()
        eventAnnotationsSummaryEntity.eventId = annotationsSummary.eventId
        eventAnnotationsSummaryEntity.roomId = roomId
        eventAnnotationsSummaryEntity.editSummary = annotationsSummary.editSummary?.let {
            EditAggregatedSummaryEntity(
                    ContentMapper.map(it.aggregatedContent),
                    RealmList<String>().apply { addAll(it.sourceEvents) },
                    RealmList<String>().apply { addAll(it.localEchos) },
                    it.lastEditTs
            )
        }
        eventAnnotationsSummaryEntity.reactionsSummary = annotationsSummary.reactionsSummary.let {
            RealmList<ReactionAggregatedSummaryEntity>().apply {
                addAll(it.map {
                    ReactionAggregatedSummaryEntity(
                            it.key,
                            it.count,
                            it.addedByMe,
                            it.firstTimestamp,
                            RealmList<String>().apply { addAll(it.sourceEvents) },
                            RealmList<String>().apply { addAll(it.localEchoEvents) }
                    )
                })
            }
        }
        return eventAnnotationsSummaryEntity
    }
}

internal fun EventAnnotationsSummaryEntity.asDomain(): EventAnnotationsSummary {
    return EventAnnotationsSummaryMapper.map(this)
}
