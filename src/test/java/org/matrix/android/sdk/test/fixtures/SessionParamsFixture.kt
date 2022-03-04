/*
 * Copyright (c) 2022 New Vector Ltd
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

package org.matrix.android.sdk.test.fixtures

import org.matrix.android.sdk.api.auth.data.Credentials
import org.matrix.android.sdk.api.auth.data.HomeServerConnectionConfig
import org.matrix.android.sdk.api.auth.data.SessionParams
import org.matrix.android.sdk.internal.auth.login.LoginType
import org.matrix.android.sdk.test.fixtures.CredentialsFixture.aCredentials

object SessionParamsFixture {
    fun aSessionParams(
            credentials: Credentials = aCredentials(),
            homeServerConnectionConfig: HomeServerConnectionConfig = HomeServerConnectionConfig.Builder().withHomeServerUri("homeserver").build(),
            isTokenValid: Boolean = false,
            loginType: LoginType = LoginType.UNKNOWN,
    ) = SessionParams(
            credentials,
            homeServerConnectionConfig,
            isTokenValid,
            loginType,
    )
}
