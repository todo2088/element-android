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

package im.vector.matrix.android.internal.crypto.store.db.model

import im.vector.matrix.android.api.extensions.tryThis
import im.vector.matrix.android.internal.crypto.GossipRequestType
import im.vector.matrix.android.internal.crypto.GossipingRequestState
import im.vector.matrix.android.internal.crypto.IncomingRoomKeyRequest
import im.vector.matrix.android.internal.crypto.IncomingSecretShareRequest
import im.vector.matrix.android.internal.crypto.IncomingShareRequestCommon
import im.vector.matrix.android.internal.crypto.model.rest.RoomKeyRequestBody
import io.realm.RealmObject
import io.realm.annotations.Index

internal open class IncomingGossipingRequestEntity(@Index var requestId: String? = "",
                                                   @Index var typeStr: String? = null,
                                                   var otherUserId: String? = null,
                                                   var requestedInfoStr: String? = null,
                                                   var otherDeviceId: String? = null,
                                                   var localCreationTimestamp: Long? = null
) : RealmObject() {

    fun getRequestedSecretName(): String? = if (type == GossipRequestType.SECRET) {
        requestedInfoStr
    } else null

    fun getRequestedKeyInfo(): RoomKeyRequestBody? = if (type == GossipRequestType.KEY) {
        RoomKeyRequestBody.fromJson(requestedInfoStr)
    } else null

    var type: GossipRequestType
        get() {
            return tryThis { typeStr?.let { GossipRequestType.valueOf(it) } } ?: GossipRequestType.KEY
        }
        set(value) {
            typeStr = value.name
        }

    private var requestStateStr: String = GossipingRequestState.NONE.name

    var requestState: GossipingRequestState
        get() {
            return tryThis { GossipingRequestState.valueOf(requestStateStr) }
                    ?: GossipingRequestState.NONE
        }
        set(value) {
            requestStateStr = value.name
        }

    companion object

    fun toIncomingGossipingRequest(): IncomingShareRequestCommon {
        return when (type) {
            GossipRequestType.KEY    -> {
                IncomingRoomKeyRequest(
                        requestBody = getRequestedKeyInfo(),
                        deviceId = otherDeviceId,
                        userId = otherUserId,
                        requestId = requestId,
                        state = requestState,
                        localCreationTimestamp = localCreationTimestamp
                )
            }
            GossipRequestType.SECRET -> {
                IncomingSecretShareRequest(
                        secretName = getRequestedSecretName(),
                        deviceId = otherDeviceId,
                        userId = otherUserId,
                        requestId = requestId,
                        localCreationTimestamp = localCreationTimestamp
                )
            }
        }
    }
}
