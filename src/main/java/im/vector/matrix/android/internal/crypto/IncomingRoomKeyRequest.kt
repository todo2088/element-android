/*
 * Copyright 2016 OpenMarket Ltd
 * Copyright 2018 New Vector Ltd
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


import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.internal.crypto.model.rest.RoomKeyRequestBody
import im.vector.matrix.android.internal.crypto.model.rest.RoomKeyShareRequest

/**
 * IncomingRoomKeyRequest class defines the incoming room keys request.
 */
open class IncomingRoomKeyRequest {
    /**
     * The user id
     */
    var mUserId: String? = null

    /**
     * The device id
     */
    var mDeviceId: String? = null

    /**
     * The request id
     */
    var mRequestId: String? = null

    /**
     * The request body
     */
    var mRequestBody: RoomKeyRequestBody? = null

    /**
     * The runnable to call to accept to share the keys
     */
    @Transient
    var mShare: Runnable? = null

    /**
     * The runnable to call to ignore the key share request.
     */
    @Transient
    var mIgnore: Runnable? = null

    /**
     * Constructor
     *
     * @param event the event
     */
    constructor(event: Event) {
        mUserId = event.sender

        val roomKeyShareRequest = event.content.toModel<RoomKeyShareRequest>()!!
        mDeviceId = roomKeyShareRequest.requestingDeviceId
        mRequestId = roomKeyShareRequest.requestId
        mRequestBody = if (null != roomKeyShareRequest.body) roomKeyShareRequest.body else RoomKeyRequestBody()
    }

    /**
     * Constructor for object creation from crypto store
     */
    constructor()
}

