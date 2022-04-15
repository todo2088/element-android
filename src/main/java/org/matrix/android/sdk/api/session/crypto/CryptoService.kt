/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.api.session.crypto

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.paging.PagedList
import kotlinx.coroutines.flow.Flow
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.api.auth.UserInteractiveAuthInterceptor
import org.matrix.android.sdk.api.listeners.ProgressListener
import org.matrix.android.sdk.api.session.crypto.crosssigning.CrossSigningService
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysBackupService
import org.matrix.android.sdk.api.session.crypto.keyshare.GossipingRequestListener
import org.matrix.android.sdk.api.session.crypto.verification.VerificationService
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.internal.crypto.IncomingRoomKeyRequest
import org.matrix.android.sdk.internal.crypto.MXEventDecryptionResult
import org.matrix.android.sdk.internal.crypto.NewSessionListener
import org.matrix.android.sdk.internal.crypto.OutgoingRoomKeyRequest
import org.matrix.android.sdk.internal.crypto.crosssigning.DeviceTrustLevel
import org.matrix.android.sdk.internal.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.internal.crypto.model.ImportRoomKeysResult
import org.matrix.android.sdk.internal.crypto.model.MXEncryptEventContentResult
import org.matrix.android.sdk.internal.crypto.model.MXUsersDevicesMap
import org.matrix.android.sdk.internal.crypto.model.event.RoomKeyWithHeldContent
import org.matrix.android.sdk.internal.crypto.model.rest.DeviceInfo
import org.matrix.android.sdk.internal.crypto.model.rest.DevicesListResponse

interface CryptoService {

    fun verificationService(): VerificationService

    fun crossSigningService(): CrossSigningService

    fun keysBackupService(): KeysBackupService

    fun setDeviceName(deviceId: String, deviceName: String, callback: MatrixCallback<Unit>)

    fun deleteDevice(deviceId: String, userInteractiveAuthInterceptor: UserInteractiveAuthInterceptor, callback: MatrixCallback<Unit>)

    fun getCryptoVersion(context: Context, longFormat: Boolean): String

    fun isCryptoEnabled(): Boolean

    fun isRoomBlacklistUnverifiedDevices(roomId: String?): Boolean

    fun setWarnOnUnknownDevices(warn: Boolean)

    fun setDeviceVerification(trustLevel: DeviceTrustLevel, userId: String, deviceId: String)

    suspend fun getUserDevices(userId: String): MutableList<CryptoDeviceInfo>

    suspend fun getMyDevice(): CryptoDeviceInfo

    fun getGlobalBlacklistUnverifiedDevices(): Boolean

    fun setGlobalBlacklistUnverifiedDevices(block: Boolean)

    fun setRoomUnBlacklistUnverifiedDevices(roomId: String)

    fun getDeviceTrackingStatus(userId: String): Int

    suspend fun importRoomKeys(roomKeysAsArray: ByteArray,
                               password: String,
                               progressListener: ProgressListener?): ImportRoomKeysResult

    suspend fun exportRoomKeys(password: String): ByteArray

    fun setRoomBlacklistUnverifiedDevices(roomId: String)

    suspend fun getDeviceInfo(userId: String, deviceId: String?): CryptoDeviceInfo?

    fun reRequestRoomKeyForEvent(event: Event)

    fun addRoomKeysRequestListener(listener: GossipingRequestListener)

    fun removeRoomKeysRequestListener(listener: GossipingRequestListener)

    suspend fun fetchDevicesList(): List<DeviceInfo>

    fun getMyDevicesInfo(): List<DeviceInfo>

    fun getLiveMyDevicesInfo(): LiveData<List<DeviceInfo>>

    fun getDeviceInfo(deviceId: String, callback: MatrixCallback<DeviceInfo>)

    suspend fun inboundGroupSessionsCount(onlyBackedUp: Boolean): Int

    fun isRoomEncrypted(roomId: String): Boolean

    suspend fun encryptEventContent(eventContent: Content,
                            eventType: String,
                            roomId: String): MXEncryptEventContentResult

    fun discardOutboundSession(roomId: String)

    @Throws(MXCryptoError::class)
    suspend fun decryptEvent(event: Event, timeline: String): MXEventDecryptionResult

    fun decryptEventAsync(event: Event, timeline: String, callback: MatrixCallback<MXEventDecryptionResult>)

    fun getEncryptionAlgorithm(roomId: String): String?

    fun shouldEncryptForInvitedMembers(roomId: String): Boolean

    suspend fun downloadKeys(userIds: List<String>, forceDownload: Boolean = false): MXUsersDevicesMap<CryptoDeviceInfo>

    suspend fun getCryptoDeviceInfo(userId: String): List<CryptoDeviceInfo>

    fun getLiveCryptoDeviceInfo(userId: String): Flow<List<CryptoDeviceInfo>>

    fun getLiveCryptoDeviceInfo(userIds: List<String>): Flow<List<CryptoDeviceInfo>>

    fun addNewSessionListener(newSessionListener: NewSessionListener)

    fun removeSessionListener(listener: NewSessionListener)

    fun getOutgoingRoomKeyRequests(): List<OutgoingRoomKeyRequest>
    fun getOutgoingRoomKeyRequestsPaged(): LiveData<PagedList<OutgoingRoomKeyRequest>>

    fun getIncomingRoomKeyRequests(): List<IncomingRoomKeyRequest>
    fun getIncomingRoomKeyRequestsPaged(): LiveData<PagedList<IncomingRoomKeyRequest>>

    fun getGossipingEventsTrail(): LiveData<PagedList<Event>>
    fun getGossipingEvents(): List<Event>

    // For testing shared session
    fun getSharedWithInfo(roomId: String?, sessionId: String): MXUsersDevicesMap<Int>
    fun getWithHeldMegolmSession(roomId: String, sessionId: String): RoomKeyWithHeldContent?

    fun logDbUsageInfo()

    /**
     * Perform any background tasks that can be done before a message is ready to
     * send, in order to speed up sending of the message.
     */
    suspend fun prepareToEncrypt(roomId: String)

    /**
     * When LL all room members might not be loaded when setting up encryption.
     * This is called after room members have been loaded
     * ... not sure if shoud be API
     */
    fun onE2ERoomMemberLoadedFromServer(roomId: String)
}
