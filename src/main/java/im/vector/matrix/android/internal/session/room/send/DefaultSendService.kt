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

package im.vector.matrix.android.internal.session.room.send

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequest
import androidx.work.Operation
import androidx.work.WorkManager
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.api.session.content.ContentAttachmentData
import im.vector.matrix.android.api.session.crypto.CryptoService
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.isImageMessage
import im.vector.matrix.android.api.session.events.model.isTextMessage
import im.vector.matrix.android.api.session.room.send.SendService
import im.vector.matrix.android.api.session.room.send.SendState
import im.vector.matrix.android.api.session.room.timeline.TimelineEvent
import im.vector.matrix.android.api.util.Cancelable
import im.vector.matrix.android.api.util.CancelableBag
import im.vector.matrix.android.internal.di.SessionId
import im.vector.matrix.android.internal.session.content.UploadContentWorker
import im.vector.matrix.android.internal.session.room.timeline.TimelineSendEventWorkCommon
import im.vector.matrix.android.internal.task.TaskExecutor
import im.vector.matrix.android.internal.util.CancelableWork
import im.vector.matrix.android.internal.worker.AlwaysSuccessfulWorker
import im.vector.matrix.android.internal.worker.WorkManagerUtil
import im.vector.matrix.android.internal.worker.WorkManagerUtil.matrixOneTimeWorkRequestBuilder
import im.vector.matrix.android.internal.worker.WorkerParamsFactory
import im.vector.matrix.android.internal.worker.startChain
import kotlinx.coroutines.launch
import timber.log.Timber
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

private const val UPLOAD_WORK = "UPLOAD_WORK"
private const val BACKOFF_DELAY = 10_000L

internal class DefaultSendService @AssistedInject constructor(
        @Assisted private val roomId: String,
        private val context: Context,
        @SessionId private val sessionId: String,
        private val localEchoEventFactory: LocalEchoEventFactory,
        private val cryptoService: CryptoService,
        private val monarchy: Monarchy,
        private val taskExecutor: TaskExecutor,
        private val localEchoRepository: LocalEchoRepository
) : SendService {

    @AssistedInject.Factory
    interface Factory {
        fun create(roomId: String): SendService
    }

    private val workerFutureListenerExecutor = Executors.newSingleThreadExecutor()

    override fun sendTextMessage(text: CharSequence, msgType: String, autoMarkdown: Boolean): Cancelable {
        val event = localEchoEventFactory.createTextEvent(roomId, msgType, text, autoMarkdown).also {
            createLocalEcho(it)
        }
        return sendEvent(event)
    }

    override fun sendFormattedTextMessage(text: String, formattedText: String, msgType: String): Cancelable {
        val event = localEchoEventFactory.createFormattedTextEvent(roomId, TextContent(text, formattedText), msgType).also {
            createLocalEcho(it)
        }

        return sendEvent(event)
    }

    private fun sendEvent(event: Event): Cancelable {
        // Encrypted room handling
        return if (cryptoService.isRoomEncrypted(roomId)) {
            Timber.v("Send event in encrypted room")
            val encryptWork = createEncryptEventWork(event, true)
            val sendWork = createSendEventWork(event, false)
            TimelineSendEventWorkCommon.postSequentialWorks(context, roomId, encryptWork, sendWork)
            CancelableWork(context, encryptWork.id)
        } else {
            val sendWork = createSendEventWork(event, true)
            TimelineSendEventWorkCommon.postWork(context, roomId, sendWork)
            CancelableWork(context, sendWork.id)
        }
    }

    override fun sendMedias(attachments: List<ContentAttachmentData>): Cancelable {
        return attachments.mapTo(CancelableBag()) {
            sendMedia(it)
        }
    }

    override fun redactEvent(event: Event, reason: String?): Cancelable {
        // TODO manage media/attachements?
        val redactWork = createRedactEventWork(event, reason)
        TimelineSendEventWorkCommon.postWork(context, roomId, redactWork)
        return CancelableWork(context, redactWork.id)
    }

    override fun resendTextMessage(localEcho: TimelineEvent): Cancelable? {
        if (localEcho.root.isTextMessage() && localEcho.root.sendState.hasFailed()) {
            return sendEvent(localEcho.root)
        }
        return null
    }

    override fun resendMediaMessage(localEcho: TimelineEvent): Cancelable? {
        if (localEcho.root.isImageMessage() && localEcho.root.sendState.hasFailed()) {
            // TODO this need a refactoring of attachement sending
//        val clearContent = localEcho.root.getClearContent()
//        val messageContent = clearContent?.toModel<MessageContent>() ?: return null
//        when (messageContent.type) {
//            MessageType.MSGTYPE_IMAGE -> {
//                val imageContent = clearContent.toModel<MessageImageContent>() ?: return null
//                val url = imageContent.url ?: return null
//                if (url.startsWith("mxc://")) {
//                    //TODO
//                } else {
//                    //The image has not yet been sent
//                    val attachmentData = ContentAttachmentData(
//                            size = imageContent.info!!.size.toLong(),
//                            mimeType = imageContent.info.mimeType!!,
//                            width = imageContent.info.width.toLong(),
//                            height = imageContent.info.height.toLong(),
//                            name = imageContent.body,
//                            path = imageContent.url,
//                            type = ContentAttachmentData.Type.IMAGE
//                    )
//                    monarchy.runTransactionSync {
//                        EventEntity.where(it,eventId = localEcho.root.eventId ?: "").findFirst()?.let {
//                            it.sendState = SendState.UNSENT
//                        }
//                    }
//                    return internalSendMedia(localEcho.root,attachmentData)
//                }
//            }
//        }
            return null
        }
        return null
    }

    override fun deleteFailedEcho(localEcho: TimelineEvent) {
        taskExecutor.executorScope.launch {
            localEchoRepository.deleteFailedEcho(roomId, localEcho)
        }
    }

    override fun clearSendingQueue() {
        TimelineSendEventWorkCommon.cancelAllWorks(context, roomId)
        WorkManager.getInstance(context).cancelUniqueWork(buildWorkName(UPLOAD_WORK))

        // Replace the worker chains with a AlwaysSuccessfulWorker, to ensure the queues are well emptied
        matrixOneTimeWorkRequestBuilder<AlwaysSuccessfulWorker>()
                .build().let {
                    TimelineSendEventWorkCommon.postWork(context, roomId, it, ExistingWorkPolicy.REPLACE)

                    // need to clear also image sending queue
                    WorkManager.getInstance(context)
                            .beginUniqueWork(buildWorkName(UPLOAD_WORK), ExistingWorkPolicy.REPLACE, it)
                            .enqueue()
                }
        taskExecutor.executorScope.launch {
            localEchoRepository.clearSendingQueue(roomId)
        }
    }

    override fun resendAllFailedMessages() {
        taskExecutor.executorScope.launch {
            val eventsToResend = localEchoRepository.getAllFailedEventsToResend(roomId)
            eventsToResend.forEach {
                sendEvent(it)
            }
            localEchoRepository.updateSendState(roomId, eventsToResend.mapNotNull { it.eventId }, SendState.UNSENT)
        }
    }

    override fun sendMedia(attachment: ContentAttachmentData): Cancelable {
        // Create an event with the media file path
        val event = localEchoEventFactory.createMediaEvent(roomId, attachment).also {
            createLocalEcho(it)
        }
        return internalSendMedia(event, attachment)
    }

    private fun internalSendMedia(localEcho: Event, attachment: ContentAttachmentData): CancelableWork {
        val isRoomEncrypted = cryptoService.isRoomEncrypted(roomId)

        val uploadWork = createUploadMediaWork(localEcho, attachment, isRoomEncrypted, startChain = true)
        val sendWork = createSendEventWork(localEcho, false)

        if (isRoomEncrypted) {
            val encryptWork = createEncryptEventWork(localEcho, false /*not start of chain, take input error*/)

            val op: Operation = WorkManager.getInstance(context)
                    .beginUniqueWork(buildWorkName(UPLOAD_WORK), ExistingWorkPolicy.APPEND, uploadWork)
                    .then(encryptWork)
                    .then(sendWork)
                    .enqueue()
            op.result.addListener(Runnable {
                if (op.result.isCancelled) {
                    Timber.e("CHAIN WAS CANCELLED")
                } else if (op.state.value is Operation.State.FAILURE) {
                    Timber.e("CHAIN DID FAIL")
                }
            }, workerFutureListenerExecutor)
        } else {
            WorkManager.getInstance(context)
                    .beginUniqueWork(buildWorkName(UPLOAD_WORK), ExistingWorkPolicy.APPEND, uploadWork)
                    .then(sendWork)
                    .enqueue()
        }

        return CancelableWork(context, sendWork.id)
    }

    private fun createLocalEcho(event: Event) {
        localEchoEventFactory.createLocalEcho(event)
    }

    private fun buildWorkName(identifier: String): String {
        return "${roomId}_$identifier"
    }

    private fun createEncryptEventWork(event: Event, startChain: Boolean): OneTimeWorkRequest {
        // Same parameter
        val params = EncryptEventWorker.Params(sessionId, roomId, event)
        val sendWorkData = WorkerParamsFactory.toData(params)

        return matrixOneTimeWorkRequestBuilder<EncryptEventWorker>()
                .setConstraints(WorkManagerUtil.workConstraints)
                .setInputData(sendWorkData)
                .startChain(startChain)
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_DELAY, TimeUnit.MILLISECONDS)
                .build()
    }

    private fun createSendEventWork(event: Event, startChain: Boolean): OneTimeWorkRequest {
        val sendContentWorkerParams = SendEventWorker.Params(sessionId, roomId, event)
        val sendWorkData = WorkerParamsFactory.toData(sendContentWorkerParams)

        return TimelineSendEventWorkCommon.createWork<SendEventWorker>(sendWorkData, startChain)
    }

    private fun createRedactEventWork(event: Event, reason: String?): OneTimeWorkRequest {
        val redactEvent = localEchoEventFactory.createRedactEvent(roomId, event.eventId!!, reason).also {
            createLocalEcho(it)
        }
        val sendContentWorkerParams = RedactEventWorker.Params(sessionId, redactEvent.eventId!!, roomId, event.eventId, reason)
        val redactWorkData = WorkerParamsFactory.toData(sendContentWorkerParams)
        return TimelineSendEventWorkCommon.createWork<RedactEventWorker>(redactWorkData, true)
    }

    private fun createUploadMediaWork(event: Event,
                                      attachment: ContentAttachmentData,
                                      isRoomEncrypted: Boolean,
                                      startChain: Boolean): OneTimeWorkRequest {
        val uploadMediaWorkerParams = UploadContentWorker.Params(sessionId, roomId, event, attachment, isRoomEncrypted)
        val uploadWorkData = WorkerParamsFactory.toData(uploadMediaWorkerParams)

        return matrixOneTimeWorkRequestBuilder<UploadContentWorker>()
                .setConstraints(WorkManagerUtil.workConstraints)
                .startChain(startChain)
                .setInputData(uploadWorkData)
                .setBackoffCriteria(BackoffPolicy.LINEAR, BACKOFF_DELAY, TimeUnit.MILLISECONDS)
                .build()
    }
}
