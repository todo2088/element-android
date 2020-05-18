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

package im.vector.matrix.android.api.session.identity

import im.vector.matrix.android.internal.session.profile.ThirdPartyIdentifier

sealed class ThreePid(open val value: String) {
    data class Email(val email: String) : ThreePid(email)
    data class Msisdn(val msisdn: String, val countryCode: String? = null) : ThreePid(msisdn)
}

internal fun ThreePid.toMedium(): String {
    return when (this) {
        is ThreePid.Email  -> ThirdPartyIdentifier.MEDIUM_EMAIL
        is ThreePid.Msisdn -> ThirdPartyIdentifier.MEDIUM_MSISDN
    }
}
