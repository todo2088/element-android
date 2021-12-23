/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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
package org.matrix.android.sdk.internal.session.room.relation

import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import com.zhuinden.monarchy.Monarchy
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import io.realm.Realm
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.room.model.EventAnnotationsSummary
import org.matrix.android.sdk.api.session.room.model.relation.RelationService
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.util.Cancelable
import org.matrix.android.sdk.api.util.NoOpCancellable
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.api.util.toOptional
import org.matrix.android.sdk.internal.crypto.CryptoSessionInfoProvider
import org.matrix.android.sdk.internal.crypto.DefaultCryptoService
import org.matrix.android.sdk.internal.crypto.MXCRYPTO_ALGORITHM_MEGOLM
import org.matrix.android.sdk.internal.crypto.algorithms.olm.OlmDecryptionResult
import org.matrix.android.sdk.internal.database.helper.addTimelineEvent
import org.matrix.android.sdk.internal.database.helper.updateThreadSummaryIfNeeded
import org.matrix.android.sdk.internal.database.mapper.TimelineEventMapper
import org.matrix.android.sdk.internal.database.mapper.asDomain
import org.matrix.android.sdk.internal.database.mapper.toEntity
import org.matrix.android.sdk.internal.database.model.ChunkEntity
import org.matrix.android.sdk.internal.database.model.CurrentStateEventEntity
import org.matrix.android.sdk.internal.database.model.EventAnnotationsSummaryEntity
import org.matrix.android.sdk.internal.database.model.EventEntity
import org.matrix.android.sdk.internal.database.model.EventInsertType
import org.matrix.android.sdk.internal.database.model.TimelineEventEntity
import org.matrix.android.sdk.internal.database.query.copyToRealmOrIgnore
import org.matrix.android.sdk.internal.database.query.findIncludingEvent
import org.matrix.android.sdk.internal.database.query.findLastForwardChunkOfRoom
import org.matrix.android.sdk.internal.database.query.getOrCreate
import org.matrix.android.sdk.internal.database.query.where
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.room.relation.threads.FetchThreadTimelineTask
import org.matrix.android.sdk.internal.session.room.send.LocalEchoEventFactory
import org.matrix.android.sdk.internal.session.room.send.queue.EventSenderProcessor
import org.matrix.android.sdk.internal.session.room.timeline.PaginationDirection
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.task.configureWith
import org.matrix.android.sdk.internal.util.awaitTransaction
import org.matrix.android.sdk.internal.util.fetchCopyMap
import timber.log.Timber

internal class DefaultRelationService @AssistedInject constructor(
        @Assisted private val roomId: String,
        private val eventEditor: EventEditor,
        private val eventSenderProcessor: EventSenderProcessor,
        private val eventFactory: LocalEchoEventFactory,
        private val cryptoSessionInfoProvider: CryptoSessionInfoProvider,
        private val cryptoService: DefaultCryptoService,
        private val findReactionEventForUndoTask: FindReactionEventForUndoTask,
        private val fetchEditHistoryTask: FetchEditHistoryTask,
        private val fetchThreadTimelineTask: FetchThreadTimelineTask,
        private val timelineEventMapper: TimelineEventMapper,
        @UserId private val userId: String,
        @SessionDatabase private val monarchy: Monarchy,
        private val taskExecutor: TaskExecutor) :
        RelationService {

    @AssistedFactory
    interface Factory {
        fun create(roomId: String): DefaultRelationService
    }

    override fun sendReaction(targetEventId: String, reaction: String): Cancelable {
        return if (monarchy
                        .fetchCopyMap(
                                { realm ->
                                    TimelineEventEntity.where(realm, roomId, targetEventId).findFirst()
                                },
                                { entity, _ ->
                                    timelineEventMapper.map(entity)
                                })
                        ?.annotations
                        ?.reactionsSummary
                        .orEmpty()
                        .none { it.addedByMe && it.key == reaction }) {
            val event = eventFactory.createReactionEvent(roomId, targetEventId, reaction)
                    .also { saveLocalEcho(it) }
            return eventSenderProcessor.postEvent(event, false /* reaction are not encrypted*/)
        } else {
            Timber.w("Reaction already added")
            NoOpCancellable
        }
    }

    override fun undoReaction(targetEventId: String, reaction: String): Cancelable {
        val params = FindReactionEventForUndoTask.Params(
                roomId,
                targetEventId,
                reaction
        )
        // TODO We should avoid using MatrixCallback internally
        val callback = object : MatrixCallback<FindReactionEventForUndoTask.Result> {
            override fun onSuccess(data: FindReactionEventForUndoTask.Result) {
                if (data.redactEventId == null) {
                    Timber.w("Cannot find reaction to undo (not yet synced?)")
                    // TODO?
                }
                data.redactEventId?.let { toRedact ->
                    val redactEvent = eventFactory.createRedactEvent(roomId, toRedact, null)
                            .also { saveLocalEcho(it) }
                    eventSenderProcessor.postRedaction(redactEvent, null)
                }
            }
        }
        return findReactionEventForUndoTask
                .configureWith(params) {
                    this.retryCount = Int.MAX_VALUE
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    override fun editTextMessage(targetEvent: TimelineEvent,
                                 msgType: String,
                                 newBodyText: CharSequence,
                                 newBodyAutoMarkdown: Boolean,
                                 compatibilityBodyText: String): Cancelable {
        return eventEditor.editTextMessage(targetEvent, msgType, newBodyText, newBodyAutoMarkdown, compatibilityBodyText)
    }

    override fun editReply(replyToEdit: TimelineEvent,
                           originalTimelineEvent: TimelineEvent,
                           newBodyText: String,
                           compatibilityBodyText: String): Cancelable {
        return eventEditor.editReply(replyToEdit, originalTimelineEvent, newBodyText, compatibilityBodyText)
    }

    override suspend fun fetchEditHistory(eventId: String): List<Event> {
        return fetchEditHistoryTask.execute(FetchEditHistoryTask.Params(roomId, eventId))
    }

    override fun replyToMessage(eventReplied: TimelineEvent, replyText: CharSequence, autoMarkdown: Boolean): Cancelable? {
        val event = eventFactory.createReplyTextEvent(
                roomId = roomId,
                eventReplied = eventReplied,
                replyText = replyText,
                autoMarkdown = autoMarkdown)
                ?.also { saveLocalEcho(it) }
                ?: return null

        return eventSenderProcessor.postEvent(event, cryptoSessionInfoProvider.isRoomEncrypted(roomId))
    }

    override fun getEventAnnotationsSummary(eventId: String): EventAnnotationsSummary? {
        return monarchy.fetchCopyMap(
                { EventAnnotationsSummaryEntity.where(it, roomId, eventId).findFirst() },
                { entity, _ ->
                    entity.asDomain()
                }
        )
    }

    override fun getEventAnnotationsSummaryLive(eventId: String): LiveData<Optional<EventAnnotationsSummary>> {
        val liveData = monarchy.findAllMappedWithChanges(
                { EventAnnotationsSummaryEntity.where(it, roomId, eventId) },
                { it.asDomain() }
        )
        return Transformations.map(liveData) { results ->
            results.firstOrNull().toOptional()
        }
    }

    override fun replyInThread(
            rootThreadEventId: String,
            replyInThreadText: CharSequence,
            msgType: String,
            autoMarkdown: Boolean,
            formattedText: String?,
            eventReplied: TimelineEvent?): Cancelable? {
        val event = if (eventReplied != null) {
            eventFactory.createReplyTextEvent(
                    roomId = roomId,
                    eventReplied = eventReplied,
                    replyText = replyInThreadText,
                    autoMarkdown = autoMarkdown,
                    rootThreadEventId = rootThreadEventId)
                    ?.also {
                        saveLocalEcho(it)
                    }
                    ?: return null
        } else {
            eventFactory.createThreadTextEvent(
                    rootThreadEventId = rootThreadEventId,
                    roomId = roomId,
                    text = replyInThreadText.toString(),
                    msgType = msgType,
                    autoMarkdown = autoMarkdown,
                    formattedText = formattedText)
                    .also {
                        saveLocalEcho(it)
                    }
        }
        return eventSenderProcessor.postEvent(event, cryptoSessionInfoProvider.isRoomEncrypted(roomId))
    }

    private fun decryptIfNeeded(event: Event, roomId: String) {
        try {
            // Event from sync does not have roomId, so add it to the event first
            val result = cryptoService.decryptEvent(event.copy(roomId = roomId), "")
            event.mxDecryptionResult = OlmDecryptionResult(
                    payload = result.clearEvent,
                    senderKey = result.senderCurve25519Key,
                    keysClaimed = result.claimedEd25519Key?.let { k -> mapOf("ed25519" to k) },
                    forwardingCurve25519KeyChain = result.forwardingCurve25519KeyChain
            )
        } catch (e: MXCryptoError) {
            if (e is MXCryptoError.Base) {
                event.mCryptoError = e.errorType
                event.mCryptoErrorReason = e.technicalMessage.takeIf { it.isNotEmpty() } ?: e.detailedErrorDescription
            }
        }
    }

    override suspend fun fetchThreadTimeline(rootThreadEventId: String): List<Event> {
        val results = fetchThreadTimelineTask.execute(FetchThreadTimelineTask.Params(roomId, rootThreadEventId))
        var counter = 0
//
//        monarchy
//                .awaitTransaction { realm ->
//                    val chunk = ChunkEntity.findLastForwardChunkOfRoom(realm, roomId)
//
//                    val optimizedThreadSummaryMap = hashMapOf<String, EventEntity>()
//                    for (event in results.reversed()) {
//                        if (event.eventId == null || event.senderId == null || event.type == null) {
//                            continue
//                        }
//
//                        // skip if event already exists
//                        if (EventEntity.where(realm, event.eventId).findFirst() != null) {
//                            counter++
//                            continue
//                        }
//
//                        if (event.isEncrypted()) {
//                            decryptIfNeeded(event, roomId)
//                        }
//
//                        val ageLocalTs = event.unsignedData?.age?.let { System.currentTimeMillis() - it }
//                        val eventEntity = event.toEntity(roomId, SendState.SYNCED, ageLocalTs).copyToRealmOrIgnore(realm, EventInsertType.INCREMENTAL_SYNC)
//                        if (event.stateKey != null) {
//                            CurrentStateEventEntity.getOrCreate(realm, roomId, event.stateKey, event.type).apply {
//                                eventId = event.eventId
//                                root = eventEntity
//                            }
//                        }
//                        chunk?.addTimelineEvent(roomId, eventEntity, PaginationDirection.FORWARDS)
//                        eventEntity.rootThreadEventId?.let {
//                            // This is a thread event
//                            optimizedThreadSummaryMap[it] = eventEntity
//                        } ?: run {
//                            // This is a normal event or a root thread one
//                            optimizedThreadSummaryMap[eventEntity.eventId] = eventEntity
//                        }
//                    }
//
//                    optimizedThreadSummaryMap.updateThreadSummaryIfNeeded(
//                            roomId = roomId,
//                            realm = realm,
//                            currentUserId = userId)
//                }
        Timber.i("----> size: ${results.size} | skipped: $counter | threads: ${results.map{ it.eventId}}")

        return results
    }

    /**
     * Saves the event in database as a local echo.
     * SendState is set to UNSENT and it's added to a the sendingTimelineEvents list of the room.
     * The sendingTimelineEvents is checked on new sync and will remove the local echo if an event with
     * the same transaction id is received (in unsigned data)
     */
    private fun saveLocalEcho(event: Event) {
        eventFactory.createLocalEcho(event)
    }
}
