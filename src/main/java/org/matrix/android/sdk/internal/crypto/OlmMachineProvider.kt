/*
 * Copyright 2021 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.crypto

import com.squareup.moshi.Moshi
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.internal.crypto.network.RequestSender
import org.matrix.android.sdk.internal.di.DeviceId
import org.matrix.android.sdk.internal.di.SessionFilesDirectory
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.util.time.Clock
import java.io.File
import javax.inject.Inject

@SessionScope
internal class OlmMachineProvider @Inject constructor(
        @UserId userId: String,
        @DeviceId deviceId: String?,
        @SessionFilesDirectory dataDir: File,
        requestSender: RequestSender,
        coroutineDispatchers: MatrixCoroutineDispatchers,
        moshi: Moshi,
        clock: Clock
) {

    val olmMachine: OlmMachine by lazy {
        OlmMachine(
                user_id = userId,
                device_id = deviceId!!,
                path = dataDir,
                clock = clock,
                requestSender = requestSender,
                coroutineDispatchers = coroutineDispatchers,
                moshi = moshi
        )
    }
}
