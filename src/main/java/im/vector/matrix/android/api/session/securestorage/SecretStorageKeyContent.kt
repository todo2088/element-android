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

package im.vector.matrix.android.api.session.securestorage

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import im.vector.matrix.android.internal.di.MoshiProvider
import im.vector.matrix.android.internal.util.JsonCanonicalizer

/**
 *
 * The contents of the account data for the key will include an algorithm property, which indicates the encryption algorithm used, as well as a name property,
 * which is a human-readable name.
 * The contents will be signed as signed JSON using the user's master cross-signing key. Other properties depend on the encryption algorithm.
 *
 *
 * "content": {
 *     "algorithm": "m.secret_storage.v1.curve25519-aes-sha2",
 *     "passphrase": {
 *         "algorithm": "m.pbkdf2",
 *         "iterations": 500000,
 *         "salt": "IrswcMWnYieBALCAOMBw9k93xSzlc2su"
 *     },
 *     "pubkey": "qql1q3IvBbwMU97zLnyh9HYW5x/zqTy5eoK1n+9fm1Y",
 *     "signatures": {
 *         "@valere35:matrix.org": {
 *             "ed25519:nOUQYiH9L8uKp5JajqiQyv+Loa3+lsdil7UBverz/Ko": "QtePmwfUL7+SHYRJT/HaTgF7gUFog1E/wtUCt0qc5aB8N+Sz5iCOvQ0KtaFHQ5SJzsBlYH8k7ejoBc0RcnU7BA"
 *         }
 *     }
 * }
 */

data class KeyInfo(
        val id: String,
        val content: SecretStorageKeyContent
)

@JsonClass(generateAdapter = true)
data class SecretStorageKeyContent(
        /** Currently support m.secret_storage.v1.curve25519-aes-sha2 */
        @Json(name = "algorithm") val algorithm: String? = null,
        @Json(name = "name") val name: String? = null,
        @Json(name = "passphrase") val passphrase: SSSSPassphrase? = null,
        @Json(name = "pubkey") val publicKey: String? = null,
        @Json(name = "signatures")
        var signatures: Map<String, Map<String, String>>? = null
) {

    private fun signalableJSONDictionary(): Map<String, Any> {
        val map = HashMap<String, Any>()
        algorithm?.let { map["algorithm"] = it }
        name?.let { map["name"] = it }
        publicKey?.let { map["pubkey"] = it }
        passphrase?.let { ssspp ->
            map["passphrase"] = mapOf(
                    "algorithm" to ssspp.algorithm,
                    "iterations" to ssspp.salt,
                    "salt" to ssspp.salt
            )
        }
        return map
    }

    fun canonicalSignable(): String {
        return JsonCanonicalizer.getCanonicalJson(Map::class.java, signalableJSONDictionary())
    }

    companion object {
        /**
         * Facility method to convert from object which must be comprised of maps, lists,
         * strings, numbers, booleans and nulls.
         */
        fun fromJson(obj: Any?): SecretStorageKeyContent? {
            return MoshiProvider.providesMoshi()
                    .adapter(SecretStorageKeyContent::class.java)
                    .fromJsonValue(obj)
        }
    }
}

@JsonClass(generateAdapter = true)
data class SSSSPassphrase(
        @Json(name = "algorithm") val algorithm: String?,
        @Json(name = "iterations") val iterations: Int,
        @Json(name = "salt") val salt: String?
)

