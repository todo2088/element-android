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
import im.vector.matrix.android.api.session.call.CallState
import im.vector.matrix.android.api.session.call.CallsListener
import im.vector.matrix.android.api.session.call.MxCall
import im.vector.matrix.android.api.session.call.TurnServerResponse
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.room.model.call.CallAnswerContent
import im.vector.matrix.android.api.session.room.model.call.CallCandidatesContent
import im.vector.matrix.android.api.session.room.model.call.CallHangupContent
import im.vector.matrix.android.api.session.room.model.call.CallInviteContent
import im.vector.matrix.android.api.util.Cancelable
import im.vector.matrix.android.api.util.NoOpCancellable
import im.vector.matrix.android.internal.di.UserId
import im.vector.matrix.android.internal.session.SessionScope
import im.vector.matrix.android.internal.session.call.model.MxCallImpl
import im.vector.matrix.android.internal.session.room.send.LocalEchoEventFactory
import im.vector.matrix.android.internal.session.room.send.RoomEventSender
import im.vector.matrix.android.internal.task.TaskExecutor
import im.vector.matrix.android.internal.task.configureWith
import timber.log.Timber
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

    private var cachedTurnServerResponse: TurnServerResponse? = null

    override fun getTurnServer(callback: MatrixCallback<TurnServerResponse>): Cancelable {
        if (cachedTurnServerResponse != null) {
            cachedTurnServerResponse?.let { callback.onSuccess(it) }
            return NoOpCancellable
        }
        return turnServerTask
                .configureWith(GetTurnServerTask.Params) {
                    this.callback = object : MatrixCallback<TurnServerResponse> {
                        override fun onSuccess(data: TurnServerResponse) {
                            cachedTurnServerResponse = data
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
        Timber.v("## VOIP getCallWithId $callId all calls ${activeCalls.map { it.callId }}")
        return activeCalls.find { it.callId == callId }
    }

    internal fun onCallEvent(event: Event) {
        when (event.getClearType()) {
            EventType.CALL_ANSWER     -> {
                event.getClearContent().toModel<CallAnswerContent>()?.let {
                    if (event.senderId == userId) {
                        // ok it's an answer from me.. is it remote echo or other session
                        val knownCall = getCallWithId(it.callId)
                        if (knownCall == null) {
                            Timber.d("## VOIP onCallEvent ${event.getClearType()} id ${it.callId} send by me")
                        } else if (!knownCall.isOutgoing) {
                            // incoming call
                            // if it was anwsered by this session, the call state would be in Answering(or connected) state
                            if (knownCall.state == CallState.LocalRinging) {
                                // discard current call, it's answered by another of my session
                                onCallManageByOtherSession(it.callId)
                            }
                        }
                        return
                    }

                    onCallAnswer(it)
                }
            }
            EventType.CALL_INVITE     -> {
                if (event.senderId == userId) {
                    // Always ignore local echos of invite
                    return
                }
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

                    if (event.senderId == userId) {
                        // ok it's an answer from me.. is it remote echo or other session
                        val knownCall = getCallWithId(content.callId)
                        if (knownCall == null) {
                            Timber.d("## VOIP onCallEvent ${event.getClearType()} id ${content.callId} send by me")
                        } else if (!knownCall.isOutgoing) {
                            // incoming call
                            if (knownCall.state == CallState.LocalRinging) {
                                // discard current call, it's answered by another of my session
                                onCallManageByOtherSession(content.callId)
                            }
                        }
                        return
                    }

                    onCallHangup(content)
                    activeCalls.removeAll { it.callId == content.callId }
                }
            }
            EventType.CALL_CANDIDATES -> {
                if (event.senderId == userId) {
                    // Always ignore local echos of invite
                    return
                }
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

    private fun onCallManageByOtherSession(callId: String) {
        callListeners.toList().forEach {
            tryThis {
                it.onCallManagedByOtherSession(callId)
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
}
