/*
 * Copyright 2019 New Vector Ltd
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

package im.vector.matrix.android.internal.crypto.actions

import im.vector.matrix.android.internal.crypto.MXOlmDevice
import im.vector.matrix.android.internal.crypto.model.MXOlmSessionResult
import im.vector.matrix.android.internal.crypto.model.MXUsersDevicesMap
import im.vector.matrix.android.internal.crypto.store.IMXCryptoStore
import timber.log.Timber
import javax.inject.Inject

internal class EnsureOlmSessionsForUsersAction @Inject constructor(private val olmDevice: MXOlmDevice,
                                                                   private val cryptoStore: IMXCryptoStore,
                                                                   private val ensureOlmSessionsForDevicesAction: EnsureOlmSessionsForDevicesAction) {

    /**
     * Try to make sure we have established olm sessions for the given users.
     * @param users    a list of user ids.
     */
    suspend fun handle(users: List<String>): MXUsersDevicesMap<MXOlmSessionResult> {
        Timber.v("## ensureOlmSessionsForUsers() : ensureOlmSessionsForUsers $users")
        val devicesByUser = users.associateWith { userId ->
            val devices = cryptoStore.getUserDevices(userId)?.values ?: emptyList()

            devices.filter {
                // Don't bother setting up session to ourself
                it.identityKey() != olmDevice.deviceCurve25519Key
                        // Don't bother setting up sessions with blocked users
                        && !(it.trustLevel?.isVerified() ?: false)
            }
        }
        return ensureOlmSessionsForDevicesAction.handle(devicesByUser)
    }
}
