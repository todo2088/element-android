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

package im.vector.matrix.android.api.session.call

import im.vector.matrix.android.api.session.room.model.call.CallAnswerContent
import im.vector.matrix.android.api.session.room.model.call.CallHangupContent
import im.vector.matrix.android.api.session.room.model.call.CallInviteContent

interface CallsListener {
//    /**
//     * Called when there is an incoming call within the room.
//     * @param peerSignalingClient the incoming call
//     */
//    fun onIncomingCall(peerSignalingClient: PeerSignalingClient)
//
//    /**
//     * An outgoing call is started.
//     *
//     * @param peerSignalingClient the outgoing call
//     */
//    fun onOutgoingCall(peerSignalingClient: PeerSignalingClient)
//
//    /**
//     * Called when a called has been hung up
//     *
//     * @param peerSignalingClient the incoming call
//     */
//    fun onCallHangUp(peerSignalingClient: PeerSignalingClient)

    fun onCallInviteReceived(mxCall: MxCall, callInviteContent: CallInviteContent)

    fun onCallAnswerReceived(callAnswerContent: CallAnswerContent)

    fun onCallHangupReceived(callHangupContent: CallHangupContent)
}
