/*
 * Copyright (c) 2020 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.matrix.android.internal.session.call

import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.extensions.tryThis
import im.vector.matrix.android.api.session.call.CallSignalingService
import im.vector.matrix.android.api.session.call.CallsListener
import im.vector.matrix.android.api.session.call.MxCall
import im.vector.matrix.android.api.session.call.TurnServer
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.room.model.call.CallAnswerContent
import im.vector.matrix.android.api.session.room.model.call.CallCandidatesContent
import im.vector.matrix.android.api.session.room.model.call.CallHangupContent
import im.vector.matrix.android.api.session.room.model.call.CallInviteContent
import im.vector.matrix.android.api.util.Cancelable
import im.vector.matrix.android.internal.di.UserId
import im.vector.matrix.android.internal.session.SessionScope
import im.vector.matrix.android.internal.session.call.model.MxCallImpl
import im.vector.matrix.android.internal.session.room.send.LocalEchoEventFactory
import im.vector.matrix.android.internal.session.room.send.RoomEventSender
import im.vector.matrix.android.internal.task.TaskExecutor
import im.vector.matrix.android.internal.task.configureWith
import java.util.UUID
import javax.inject.Inject

@SessionScope
internal class DefaultCallSignalingService @Inject constructor(
        @UserId
        private val userId: String,
        private val localEchoEventFactory: LocalEchoEventFactory,
        private val roomEventSender: RoomEventSender,
        private val taskExecutor: TaskExecutor,
        private val turnServerTask: GetTurnServerTask
) : CallSignalingService {

    private val callListeners = mutableSetOf<CallsListener>()

    private val activeCalls = mutableListOf<MxCall>()

    override fun getTurnServer(callback: MatrixCallback<TurnServer>): Cancelable {
        return turnServerTask
                .configureWith(GetTurnServerTask.Params) {
                    this.callback = object : MatrixCallback<TurnServer> {
                        override fun onSuccess(data: TurnServer) {
                            callback.onSuccess(data)
                        }

                        override fun onFailure(failure: Throwable) {
                            callback.onFailure(failure)
                        }
                    }
                }
                .executeBy(taskExecutor)
    }

    override fun createOutgoingCall(roomId: String, otherUserId: String, isVideoCall: Boolean): MxCall {
        return MxCallImpl(
                callId = UUID.randomUUID().toString(),
                isOutgoing = true,
                roomId = roomId,
                userId = userId,
                otherUserId = otherUserId,
                isVideoCall = isVideoCall,
                localEchoEventFactory = localEchoEventFactory,
                roomEventSender = roomEventSender
        ).also {
            activeCalls.add(it)
        }
    }

    override fun addCallListener(listener: CallsListener) {
        callListeners.add(listener)
    }

    override fun removeCallListener(listener: CallsListener) {
        callListeners.remove(listener)
    }

    override fun getCallWithId(callId: String): MxCall? {
        return activeCalls.find { it.callId == callId }
    }

    internal fun onCallEvent(event: Event) {
        // TODO if handled by other of my sessions
        // this test is too simple, should notify upstream
        if (event.senderId == userId) {
            // ignore local echos!
            return
        }
        when (event.getClearType()) {
            EventType.CALL_ANSWER     -> {
                event.getClearContent().toModel<CallAnswerContent>()?.let {
                    onCallAnswer(it)
                }
            }
            EventType.CALL_INVITE     -> {
                event.getClearContent().toModel<CallInviteContent>()?.let { content ->
                    val incomingCall = MxCallImpl(
                            callId = content.callId ?: return@let,
                            isOutgoing = false,
                            roomId = event.roomId ?: return@let,
                            userId = userId,
                            otherUserId = event.senderId ?: return@let,
                            isVideoCall = content.isVideo(),
                            localEchoEventFactory = localEchoEventFactory,
                            roomEventSender = roomEventSender
                    )
                    activeCalls.add(incomingCall)
                    onCallInvite(incomingCall, content)
                }
            }
            EventType.CALL_HANGUP     -> {
                event.getClearContent().toModel<CallHangupContent>()?.let { content ->
                    onCallHangup(content)
                    activeCalls.removeAll { it.callId == content.callId }
                }
            }
            EventType.CALL_CANDIDATES -> {
                event.getClearContent().toModel<CallCandidatesContent>()?.let { content ->
                    activeCalls.firstOrNull { it.callId == content.callId }?.let {
                        onCallIceCandidate(it, content)
                    }
                }
            }
        }
    }

    private fun onCallHangup(hangup: CallHangupContent) {
        callListeners.toList().forEach {
            tryThis {
                it.onCallHangupReceived(hangup)
            }
        }
    }

    private fun onCallAnswer(answer: CallAnswerContent) {
        callListeners.toList().forEach {
            tryThis {
                it.onCallAnswerReceived(answer)
            }
        }
    }

    private fun onCallInvite(incomingCall: MxCall, invite: CallInviteContent) {
        // Ignore the invitation from current user
        if (incomingCall.otherUserId == userId) return

        callListeners.toList().forEach {
            tryThis {
                it.onCallInviteReceived(incomingCall, invite)
            }
        }
    }

    private fun onCallIceCandidate(incomingCall: MxCall, candidates: CallCandidatesContent) {
        callListeners.toList().forEach {
            tryThis {
                it.onCallIceCandidateReceived(incomingCall, candidates)
            }
        }
    }

    companion object {
        const val CALL_TIMEOUT_MS = 120_000
    }

//    internal class PeerSignalingClientFactory @Inject constructor(
//            @UserId private val userId: String,
//            private val localEchoEventFactory: LocalEchoEventFactory,
//            private val sendEventTask: SendEventTask,
//            private val taskExecutor: TaskExecutor,
//            private val cryptoService: CryptoService
//    ) {
//
//        fun create(roomId: String, callId: String): PeerSignalingClient {
//            return RoomPeerSignalingClient(
//                    callID = callId,
//                    roomId = roomId,
//                    userId = userId,
//                    localEchoEventFactory = localEchoEventFactory,
//                    sendEventTask = sendEventTask,
//                    taskExecutor = taskExecutor,
//                    cryptoService = cryptoService
//            )
//        }
//    }
}
