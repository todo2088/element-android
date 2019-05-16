/*
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

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import im.vector.matrix.android.internal.di.MoshiProvider

/**
 * The type of object we use for importing and exporting megolm session data.
 */
@JsonClass(generateAdapter = true)
data class MegolmSessionData(
        /**
         * The algorithm used.
         */
        @Json(name = "algorithm")
        var algorithm: String? = null,

        /**
         * Unique id for the session.
         */
        @Json(name = "session_id")
        var sessionId: String? = null,

        /**
         * Sender's Curve25519 device key.
         */
        @Json(name = "sender_key")
        var senderKey: String? = null,

        /**
         * Room this session is used in.
         */
        @Json(name = "room_id")
        var roomId: String? = null,

        /**
         * Base64'ed key data.
         */
        @Json(name = "session_key")
        var sessionKey: String? = null,

        /**
         * Other keys the sender claims.
         */
        @Json(name = "sender_claimed_keys")
        var senderClaimedKeys: Map<String, String>? = null,

        // This is a shortcut for sender_claimed_keys.get("ed25519")
        // Keep it for compatibility reason.
        @Json(name = "sender_claimed_ed25519_key")
        var senderClaimedEd25519Key: String? = null,

        /**
         * Devices which forwarded this session to us (normally empty).
         */
        @Json(name = "forwarding_curve25519_key_chain")
        var forwardingCurve25519KeyChain: List<String>? = null
) {

    fun toJsonString(): String {
        return MoshiProvider.providesMoshi().adapter(MegolmSessionData::class.java).toJson(this)
    }
}

