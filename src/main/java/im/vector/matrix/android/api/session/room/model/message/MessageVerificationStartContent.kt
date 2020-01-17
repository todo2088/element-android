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
package im.vector.matrix.android.api.session.room.model.message

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import im.vector.matrix.android.api.session.crypto.sas.SasMode
import im.vector.matrix.android.api.session.events.model.toContent
import im.vector.matrix.android.api.session.room.model.relation.RelationDefaultContent
import im.vector.matrix.android.internal.crypto.model.rest.supportedVerificationMethods
import im.vector.matrix.android.internal.crypto.verification.SASVerificationTransaction
import im.vector.matrix.android.internal.crypto.verification.VerificationInfoStart
import im.vector.matrix.android.internal.util.JsonCanonicalizer
import timber.log.Timber

@JsonClass(generateAdapter = true)
internal data class MessageVerificationStartContent(
        @Json(name = "from_device") override val fromDevice: String?,
        @Json(name = "hashes") override val hashes: List<String>?,
        @Json(name = "key_agreement_protocols") override val keyAgreementProtocols: List<String>?,
        @Json(name = "message_authentication_codes") override val messageAuthenticationCodes: List<String>?,
        @Json(name = "short_authentication_string") override val shortAuthenticationStrings: List<String>?,
        @Json(name = "method") override val method: String?,
        @Json(name = "m.relates_to") val relatesTo: RelationDefaultContent?
) : VerificationInfoStart {

    override fun toCanonicalJson(): String? {
        return JsonCanonicalizer.getCanonicalJson(MessageVerificationStartContent::class.java, this)
    }

    override val transactionID: String?
        get() = relatesTo?.eventId

    override fun isValid(): Boolean {
        if (transactionID.isNullOrBlank()
                || fromDevice.isNullOrBlank()
                || method !in supportedVerificationMethods
                || keyAgreementProtocols.isNullOrEmpty()
                || hashes.isNullOrEmpty()
                || !hashes.contains("sha256") || messageAuthenticationCodes.isNullOrEmpty()
                || (!messageAuthenticationCodes.contains(SASVerificationTransaction.SAS_MAC_SHA256)
                        && !messageAuthenticationCodes.contains(SASVerificationTransaction.SAS_MAC_SHA256_LONGKDF))
                || shortAuthenticationStrings.isNullOrEmpty()
                || !shortAuthenticationStrings.contains(SasMode.DECIMAL)) {
            Timber.e("## received invalid verification request")
            return false
        }
        return true
    }

    override fun toEventContent() = toContent()
}
