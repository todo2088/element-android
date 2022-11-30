/*
 * Copyright (c) 2019 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.crypto.algorithms.megolm

import dagger.Lazy
import org.matrix.android.sdk.api.crypto.MXCryptoConfig
import org.matrix.android.sdk.internal.crypto.MXOlmDevice
import org.matrix.android.sdk.internal.crypto.OutgoingKeyRequestManager
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.StreamEventsManager
import org.matrix.android.sdk.internal.util.time.Clock
import javax.inject.Inject

internal class MXMegolmDecryptionFactory @Inject constructor(
        private val olmDevice: MXOlmDevice,
        @UserId private val myUserId: String,
        private val outgoingKeyRequestManager: OutgoingKeyRequestManager,
        private val cryptoStore: IMXCryptoStore,
        private val eventsManager: Lazy<StreamEventsManager>,
        private val unrequestedForwardManager: UnRequestedForwardManager,
        private val mxCryptoConfig: MXCryptoConfig,
        private val clock: Clock,
) {

    fun create(): MXMegolmDecryption {
        return MXMegolmDecryption(
                olmDevice = olmDevice,
                myUserId = myUserId,
                outgoingKeyRequestManager = outgoingKeyRequestManager,
                cryptoStore = cryptoStore,
                liveEventManager = eventsManager,
                unrequestedForwardManager = unrequestedForwardManager,
                cryptoConfig = mxCryptoConfig,
                clock = clock,
        )
    }
}
