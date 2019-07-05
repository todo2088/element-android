/*
 * Copyright 2016 OpenMarket Ltd
 * Copyright 2017 Vector Creations Ltd
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

package im.vector.matrix.android.api.session.crypto

import im.vector.matrix.android.internal.crypto.model.MXDeviceInfo
import im.vector.matrix.android.internal.crypto.model.MXUsersDevicesMap

/**
 * Represents a crypto error response.
 */
sealed class MXCryptoError : Throwable() {

    // TODO Create data class for all cases, and remove error code
    data class Base(val code: String,
                    val _message: String,
                    /**
                     * Describe the error with more details
                     */
                    val detailedErrorDescription: String? = null) : MXCryptoError()

    data class UnknownDevice(val deviceList: MXUsersDevicesMap<MXDeviceInfo>) : MXCryptoError()

    companion object {
        /**
         * Error codes
         */
        const val ENCRYPTING_NOT_ENABLED_ERROR_CODE = "ENCRYPTING_NOT_ENABLED"
        const val UNABLE_TO_ENCRYPT_ERROR_CODE = "UNABLE_TO_ENCRYPT"
        const val UNABLE_TO_DECRYPT_ERROR_CODE = "UNABLE_TO_DECRYPT"
        const val UNKNOWN_INBOUND_SESSION_ID_ERROR_CODE = "UNKNOWN_INBOUND_SESSION_ID"
        const val INBOUND_SESSION_MISMATCH_ROOM_ID_ERROR_CODE = "INBOUND_SESSION_MISMATCH_ROOM_ID"
        const val MISSING_FIELDS_ERROR_CODE = "MISSING_FIELDS"
        const val BAD_EVENT_FORMAT_ERROR_CODE = "BAD_EVENT_FORMAT_ERROR_CODE"
        const val MISSING_SENDER_KEY_ERROR_CODE = "MISSING_SENDER_KEY_ERROR_CODE"
        const val MISSING_CIPHER_TEXT_ERROR_CODE = "MISSING_CIPHER_TEXT"
        const val BAD_DECRYPTED_FORMAT_ERROR_CODE = "BAD_DECRYPTED_FORMAT_ERROR_CODE"
        const val NOT_INCLUDE_IN_RECIPIENTS_ERROR_CODE = "NOT_INCLUDE_IN_RECIPIENTS"
        const val BAD_RECIPIENT_ERROR_CODE = "BAD_RECIPIENT"
        const val BAD_RECIPIENT_KEY_ERROR_CODE = "BAD_RECIPIENT_KEY"
        const val FORWARDED_MESSAGE_ERROR_CODE = "FORWARDED_MESSAGE"
        const val BAD_ROOM_ERROR_CODE = "BAD_ROOM"
        const val BAD_ENCRYPTED_MESSAGE_ERROR_CODE = "BAD_ENCRYPTED_MESSAGE"
        const val DUPLICATED_MESSAGE_INDEX_ERROR_CODE = "DUPLICATED_MESSAGE_INDEX"
        const val MISSING_PROPERTY_ERROR_CODE = "MISSING_PROPERTY"
        const val OLM_ERROR_CODE = "OLM_ERROR_CODE"
        const val UNKNOWN_DEVICES_CODE = "UNKNOWN_DEVICES_CODE"
        const val UNKNOWN_MESSAGE_INDEX = "UNKNOWN_MESSAGE_INDEX"

        /**
         * short error reasons
         */
        const val UNABLE_TO_DECRYPT = "Unable to decrypt"
        const val UNABLE_TO_ENCRYPT = "Unable to encrypt"

        /**
         * Detailed error reasons
         */
        const val ENCRYPTING_NOT_ENABLED_REASON = "Encryption not enabled"
        const val UNABLE_TO_ENCRYPT_REASON = "Unable to encrypt %s"
        const val UNABLE_TO_DECRYPT_REASON = "Unable to decrypt %1\$s. Algorithm: %2\$s"
        const val OLM_REASON = "OLM error: %1\$s"
        const val DETAILLED_OLM_REASON = "Unable to decrypt %1\$s. OLM error: %2\$s"
        const val UNKNOWN_INBOUND_SESSION_ID_REASON = "Unknown inbound session id"
        const val INBOUND_SESSION_MISMATCH_ROOM_ID_REASON = "Mismatched room_id for inbound group session (expected %1\$s, was %2\$s)"
        const val MISSING_FIELDS_REASON = "Missing fields in input"
        const val BAD_EVENT_FORMAT_TEXT_REASON = "Bad event format"
        const val MISSING_SENDER_KEY_TEXT_REASON = "Missing senderKey"
        const val MISSING_CIPHER_TEXT_REASON = "Missing ciphertext"
        const val BAD_DECRYPTED_FORMAT_TEXT_REASON = "Bad decrypted event format"
        const val NOT_INCLUDED_IN_RECIPIENT_REASON = "Not included in recipients"
        const val BAD_RECIPIENT_REASON = "Message was intended for %1\$s"
        const val BAD_RECIPIENT_KEY_REASON = "Message not intended for this device"
        const val FORWARDED_MESSAGE_REASON = "Message forwarded from %1\$s"
        const val BAD_ROOM_REASON = "Message intended for room %1\$s"
        const val BAD_ENCRYPTED_MESSAGE_REASON = "Bad Encrypted Message"
        const val DUPLICATE_MESSAGE_INDEX_REASON = "Duplicate message index, possible replay attack %1\$s"
        const val ERROR_MISSING_PROPERTY_REASON = "No '%1\$s' property. Cannot prevent unknown-key attack"
        const val UNKNOWN_DEVICES_REASON = "This room contains unknown devices which have not been verified.\n" + "We strongly recommend you verify them before continuing."
        const val NO_MORE_ALGORITHM_REASON = "Room was previously configured to use encryption, but is no longer." + " Perhaps the homeserver is hiding the configuration event."
    }
}