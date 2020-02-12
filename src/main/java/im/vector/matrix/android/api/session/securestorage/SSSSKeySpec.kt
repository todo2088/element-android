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

import im.vector.matrix.android.api.listeners.ProgressListener
import im.vector.matrix.android.internal.crypto.keysbackup.deriveKey
import im.vector.matrix.android.internal.crypto.keysbackup.util.extractCurveKeyFromRecoveryKey

/** Tag class */
interface SSSSKeySpec

data class Curve25519AesSha2KeySpec(
        val privateKey: ByteArray
) : SSSSKeySpec {

    companion object {

        fun fromPassphrase(passphrase: String, salt: String, iterations: Int, progressListener: ProgressListener?): Curve25519AesSha2KeySpec {
            return Curve25519AesSha2KeySpec(
                    privateKey = deriveKey(
                            passphrase,
                            salt,
                            iterations,
                            progressListener
                    )
            )
        }

        fun fromRecoveryKey(recoveryKey: String): Curve25519AesSha2KeySpec? {
            return extractCurveKeyFromRecoveryKey(recoveryKey)?.let {
                Curve25519AesSha2KeySpec(
                        privateKey = it
                )
            }
        }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false

        other as Curve25519AesSha2KeySpec

        if (!privateKey.contentEquals(other.privateKey)) return false

        return true
    }

    override fun hashCode(): Int {
        return privateKey.contentHashCode()
    }
}

