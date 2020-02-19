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

package im.vector.matrix.android.internal.crypto.verification.qrcode

import im.vector.matrix.android.internal.crypto.crosssigning.fromBase64NoPadding
import im.vector.matrix.android.internal.crypto.crosssigning.toBase64NoPadding

// MATRIX
private val prefix = "MATRIX".toByteArray(Charsets.ISO_8859_1)

fun QrCodeDataV2.toEncodedString(): String {
    var result = ByteArray(0)

    // MATRIX
    for (i in prefix.indices) {
        result += prefix[i]
    }

    // Version
    result += 2

    // Mode
    result += when (this) {
        is QrCodeDataV2.VerifyingAnotherUser             -> 0
        is QrCodeDataV2.SelfVerifyingMasterKeyTrusted    -> 1
        is QrCodeDataV2.SelfVerifyingMasterKeyNotTrusted -> 2
    }.toByte()

    // TransactionId length
    val length = transactionId.length
    result += ((length and 0xFF00) shr 8).toByte()
    result += length.toByte()

    // TransactionId
    transactionId.forEach {
        result += it.toByte()
    }

    // Keys
    firstKey.fromBase64NoPadding().forEach {
        result += it
    }
    secondKey.fromBase64NoPadding().forEach {
        result += it
    }

    // Secret
    sharedSecret.fromBase64NoPadding().forEach {
        result += it
    }

    return result.toString(Charsets.ISO_8859_1)
}

fun String.toQrCodeDataV2(): QrCodeDataV2? {
    val byteArray = toByteArray(Charsets.ISO_8859_1)

    // Size should be min 6 + 1 + 1 + 2 + ? + 32 + 32 + ? = 74 + transactionLength + secretLength

    // Check header
    // MATRIX
    if (byteArray.size < 10) return null

    for (i in prefix.indices) {
        if (byteArray[i] != prefix[i]) {
            return null
        }
    }

    var cursor = prefix.size // 6

    // Version
    if (byteArray[cursor] != 2.toByte()) {
        return null
    }
    cursor++

    // Get mode
    val mode = byteArray[cursor].toInt()
    cursor++

    // Get transaction length
    val transactionLength = (byteArray[cursor].toInt() shr 8) + byteArray[cursor + 1].toInt()

    cursor++
    cursor++

    val secretLength = byteArray.size - 74 - transactionLength

    // ensure the secret length is 8 bytes min
    if (secretLength < 8) {
        return null
    }

    val transactionId = byteArray.copyOfRange(cursor, cursor + transactionLength).toString(Charsets.ISO_8859_1)
    cursor += transactionLength
    val key1 = byteArray.copyOfRange(cursor, cursor + 32).toBase64NoPadding()
    cursor += 32
    val key2 = byteArray.copyOfRange(cursor, cursor + 32).toBase64NoPadding()
    cursor += 32
    val secret = byteArray.copyOfRange(cursor, byteArray.size).toBase64NoPadding()

    return when (mode) {
        0    -> QrCodeDataV2.VerifyingAnotherUser(transactionId, key1, key2, secret)
        1    -> QrCodeDataV2.SelfVerifyingMasterKeyTrusted(transactionId, key1, key2, secret)
        2    -> QrCodeDataV2.SelfVerifyingMasterKeyNotTrusted(transactionId, key1, key2, secret)
        else -> null
    }
}
