/*
 * Copyright 2016 OpenMarket Ltd
 * Copyright 2017 Vector Creations Ltd
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

package im.vector.matrix.android.internal.crypto

import im.vector.matrix.android.api.util.JsonDict
import java.util.*

/**
 * The result of a (successful) call to decryptEvent.
 */
data class MXEventDecryptionResult(

        /**
         * The plaintext payload for the event (typically containing "type" and "content" fields).
         */
        var clearEvent: JsonDict? = null,

        /**
         * Key owned by the sender of this event.
         * See MXEvent.senderKey.
         */
        var senderCurve25519Key: String? = null,

        /**
         * Ed25519 key claimed by the sender of this event.
         * See MXEvent.claimedEd25519Key.
         */
        var claimedEd25519Key: String? = null,

        /**
         * List of curve25519 keys involved in telling us about the senderCurve25519Key and
         * claimedEd25519Key. See MXEvent.forwardingCurve25519KeyChain.
         */
        var forwardingCurve25519KeyChain: List<String> = ArrayList()
)
