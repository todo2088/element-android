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

package im.vector.matrix.android.internal.crypto

/**
 * Represents an outgoing room key request
 */
class OutgoingSecretRequest(
        // Secret Name
        var secretName: String?,
        // list of recipients for the request
        override var recipients: List<Map<String, String>>,
        // Unique id for this request. Used for both
        // an id within the request for later pairing with a cancellation, and for
        // the transaction id when sending the to_device messages to our local
        override var requestId: String,
        // current state of this request
        override var state: ShareRequestState) : OutgoingShareRequest {

    // transaction id for the cancellation, if any
    override var cancellationTxnId: String? = null
}
