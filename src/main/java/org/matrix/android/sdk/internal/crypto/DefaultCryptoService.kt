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

package org.matrix.android.sdk.internal.crypto

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.LiveData
import androidx.paging.PagedList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelChildren
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.api.MatrixCoroutineDispatchers
import org.matrix.android.sdk.api.NoOpMatrixCallback
import org.matrix.android.sdk.api.auth.UserInteractiveAuthInterceptor
import org.matrix.android.sdk.api.crypto.MXCryptoConfig
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.listeners.ProgressListener
import org.matrix.android.sdk.api.logger.LoggerTag
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.crypto.crosssigning.CrossSigningService
import org.matrix.android.sdk.api.session.crypto.keyshare.GossipingRequestListener
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomHistoryVisibility
import org.matrix.android.sdk.api.session.room.model.RoomHistoryVisibilityContent
import org.matrix.android.sdk.api.session.room.model.RoomMemberContent
import org.matrix.android.sdk.api.session.sync.model.DeviceListResponse
import org.matrix.android.sdk.api.session.sync.model.DeviceOneTimeKeysCountSyncResponse
import org.matrix.android.sdk.api.session.sync.model.ToDeviceSyncResponse
import org.matrix.android.sdk.internal.crypto.crosssigning.DeviceTrustLevel
import org.matrix.android.sdk.internal.crypto.keysbackup.RustKeyBackupService
import org.matrix.android.sdk.internal.crypto.model.CryptoDeviceInfo
import org.matrix.android.sdk.internal.crypto.model.ImportRoomKeysResult
import org.matrix.android.sdk.internal.crypto.model.MXEncryptEventContentResult
import org.matrix.android.sdk.internal.crypto.model.MXUsersDevicesMap
import org.matrix.android.sdk.internal.crypto.model.event.RoomKeyContent
import org.matrix.android.sdk.internal.crypto.model.event.RoomKeyWithHeldContent
import org.matrix.android.sdk.internal.crypto.model.event.SecretSendEventContent
import org.matrix.android.sdk.internal.crypto.model.rest.DeviceInfo
import org.matrix.android.sdk.internal.crypto.model.rest.ForwardedRoomKeyContent
import org.matrix.android.sdk.internal.crypto.repository.WarnOnUnknownDeviceRepository
import org.matrix.android.sdk.internal.crypto.store.IMXCryptoStore
import org.matrix.android.sdk.internal.crypto.tasks.DeleteDeviceTask
import org.matrix.android.sdk.internal.crypto.tasks.GetDeviceInfoTask
import org.matrix.android.sdk.internal.crypto.tasks.GetDevicesTask
import org.matrix.android.sdk.internal.crypto.tasks.SetDeviceNameTask
import org.matrix.android.sdk.internal.crypto.verification.RustVerificationService
import org.matrix.android.sdk.internal.di.DeviceId
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.SessionScope
import org.matrix.android.sdk.internal.session.StreamEventsManager
import org.matrix.android.sdk.internal.session.room.membership.LoadRoomMembersTask
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.task.TaskThread
import org.matrix.android.sdk.internal.task.configureWith
import org.matrix.android.sdk.internal.task.launchToCallback
import timber.log.Timber
import uniffi.olm.Request
import uniffi.olm.RequestType
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import kotlin.math.max
import kotlin.system.measureTimeMillis

/**
 * A `CryptoService` class instance manages the end-to-end crypto for a session.
 *
 *
 * Messages posted by the user are automatically redirected to CryptoService in order to be encrypted
 * before sending.
 * In the other hand, received events goes through CryptoService for decrypting.
 * CryptoService maintains all necessary keys and their sharing with other devices required for the crypto.
 * Specially, it tracks all room membership changes events in order to do keys updates.
 */

private val loggerTag = LoggerTag("DefaultCryptoService", LoggerTag.CRYPTO)

@SessionScope
internal class DefaultCryptoService @Inject constructor(
        @UserId
        private val userId: String,
        @DeviceId
        private val deviceId: String?,
//        @SessionId
//        private val sessionId: String,
//        @SessionFilesDirectory
//        private val dataDir: File,
        // the crypto store
        private val cryptoStore: IMXCryptoStore,
        // Set of parameters used to configure/customize the end-to-end crypto.
        private val mxCryptoConfig: MXCryptoConfig,
        // Actions
        private val warnOnUnknownDevicesRepository: WarnOnUnknownDeviceRepository,
        // Tasks
        private val deleteDeviceTask: DeleteDeviceTask,
        private val getDevicesTask: GetDevicesTask,
        private val getDeviceInfoTask: GetDeviceInfoTask,
        private val setDeviceNameTask: SetDeviceNameTask,
        private val loadRoomMembersTask: LoadRoomMembersTask,
        private val cryptoSessionInfoProvider: CryptoSessionInfoProvider,
        private val coroutineDispatchers: MatrixCoroutineDispatchers,
        private val taskExecutor: TaskExecutor,
        private val cryptoCoroutineScope: CoroutineScope,
        private val requestSender: RequestSender,
        private val crossSigningService: CrossSigningService,
        private val verificationService: RustVerificationService,
        private val keysBackupService: RustKeyBackupService,
        private val megolmSessionImportManager: MegolmSessionImportManager,
        private val olmMachineProvider: OlmMachineProvider,
        private val liveEventManager: dagger.Lazy<StreamEventsManager>
) : CryptoService {

    private val isStarting = AtomicBoolean(false)
    private val isStarted = AtomicBoolean(false)

    private val olmMachine by lazy { olmMachineProvider.olmMachine }

    // The verification service.
//    private var verificationService: RustVerificationService? = null

//    private val deviceObserver: DeviceUpdateObserver = DeviceUpdateObserver()

    // Locks for some of our operations
    private val keyClaimLock: Mutex = Mutex()
    private val outgoingRequestsLock: Mutex = Mutex()
    private val roomKeyShareLocks: ConcurrentHashMap<String, Mutex> = ConcurrentHashMap()

    fun onStateEvent(roomId: String, event: Event) {
        when (event.type) {
            EventType.STATE_ROOM_ENCRYPTION         -> onRoomEncryptionEvent(roomId, event)
            EventType.STATE_ROOM_MEMBER             -> onRoomMembershipEvent(roomId, event)
            EventType.STATE_ROOM_HISTORY_VISIBILITY -> onRoomHistoryVisibilityEvent(roomId, event)
        }
    }

    fun onLiveEvent(roomId: String, event: Event) {
        if (event.isStateEvent()) {
            when (event.getClearType()) {
                EventType.STATE_ROOM_ENCRYPTION         -> onRoomEncryptionEvent(roomId, event)
                EventType.STATE_ROOM_MEMBER             -> onRoomMembershipEvent(roomId, event)
                EventType.STATE_ROOM_HISTORY_VISIBILITY -> onRoomHistoryVisibilityEvent(roomId, event)
            }
        } else {
            cryptoCoroutineScope.launch {
                verificationService.onEvent(roomId, event)
            }
        }
    }

    private val gossipingBuffer = mutableListOf<Event>()

    override fun setDeviceName(deviceId: String, deviceName: String, callback: MatrixCallback<Unit>) {
        setDeviceNameTask
                .configureWith(SetDeviceNameTask.Params(deviceId, deviceName)) {
                    this.executionThread = TaskThread.CRYPTO
                    this.callback = object : MatrixCallback<Unit> {
                        override fun onSuccess(data: Unit) {
                            // bg refresh of crypto device
                            cryptoCoroutineScope.launch {
                                try {
                                    downloadKeys(listOf(userId), true)
                                } catch (failure: Throwable) {
                                    Timber.tag(loggerTag.value).w(failure, "setDeviceName: Failed to refresh of crypto device")
                                }
                            }
                            callback.onSuccess(data)
                        }

                        override fun onFailure(failure: Throwable) {
                            callback.onFailure(failure)
                        }
                    }
                }
                .executeBy(taskExecutor)
    }

    override fun deleteDevice(deviceId: String, userInteractiveAuthInterceptor: UserInteractiveAuthInterceptor, callback: MatrixCallback<Unit>) {
        deleteDeviceTask
                .configureWith(DeleteDeviceTask.Params(deviceId, userInteractiveAuthInterceptor, null)) {
                    this.executionThread = TaskThread.CRYPTO
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    override fun getCryptoVersion(context: Context, longFormat: Boolean): String {
        // TODO we should provide olm and rust-sdk version from the rust-sdk
        return if (longFormat) "Rust SDK 0.3" else "0.3"
    }

    override suspend fun getMyDevice(): CryptoDeviceInfo {
        return olmMachine.ownDevice()
    }

    override suspend fun fetchDevicesList(): List<DeviceInfo> {
        val devicesList = tryOrNull {
            getDevicesTask.execute(Unit).devices
        }.orEmpty()
        cryptoStore.saveMyDevicesInfo(devicesList)
        return devicesList
    }

    override fun getLiveMyDevicesInfo(): LiveData<List<DeviceInfo>> {
        return cryptoStore.getLiveMyDevicesInfo()
    }

    override fun getMyDevicesInfo(): List<DeviceInfo> {
        return cryptoStore.getMyDevicesInfo()
    }

    override fun getDeviceInfo(deviceId: String, callback: MatrixCallback<DeviceInfo>) {
        getDeviceInfoTask
                .configureWith(GetDeviceInfoTask.Params(deviceId)) {
                    this.executionThread = TaskThread.CRYPTO
                    this.callback = callback
                }
                .executeBy(taskExecutor)
    }

    override suspend fun inboundGroupSessionsCount(onlyBackedUp: Boolean): Int {
        return if (onlyBackedUp) {
            keysBackupService.getTotalNumbersOfBackedUpKeys()
        } else {
            keysBackupService.getTotalNumbersOfKeys()
        }
        // return cryptoStore.inboundGroupSessionsCount(onlyBackedUp)
    }

    /**
     * Provides the tracking status
     *
     * @param userId the user id
     * @return the tracking status
     */
    override fun getDeviceTrackingStatus(userId: String): Int {
        return if (olmMachine.isUserTracked(userId)) {
            3
        } else {
            -1
        }
    }

    /**
     * Tell if the MXCrypto is started
     *
     * @return true if the crypto is started
     */
    fun isStarted(): Boolean {
        return isStarted.get()
    }

    /**
     * Start the crypto module.
     * Device keys will be uploaded, then one time keys if there are not enough on the homeserver
     * and, then, if this is the first time, this new device will be announced to all other users
     * devices.
     *
     */
    fun start() {
        internalStart()
        cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
            // Just update
            fetchDevicesList()
            cryptoStore.tidyUpDataBase()
        }
    }

    fun ensureDevice() {
        cryptoCoroutineScope.launchToCallback(coroutineDispatchers.crypto, NoOpMatrixCallback()) {
            // Open the store
            cryptoStore.open()

            // this can throw if no backup
            /*
            TODO
            tryOrNull {
                keysBackupService.checkAndStartKeysBackup()
            }
            */
        }
    }

    private fun internalStart() {
        if (isStarted.get() || isStarting.get()) {
            return
        }
        isStarting.set(true)

        try {
            setRustLogger()
            Timber.tag(loggerTag.value).v(
                    "## CRYPTO | Successfully started up an Olm machine for " +
                            "$userId, $deviceId, identity keys: ${this.olmMachine.identityKeys()}")
        } catch (throwable: Throwable) {
            Timber.tag(loggerTag.value).v("Failed create an Olm machine: $throwable")
        }

        // We try to enable key backups, if the backup version on the server is trusted,
        // we're gonna continue backing up.
        cryptoCoroutineScope.launch {
            tryOrNull {
                keysBackupService.checkAndStartKeysBackup()
            }
        }

        // Open the store
        cryptoStore.open()

        isStarting.set(false)
        isStarted.set(true)
    }

    /**
     * Close the crypto
     */
    fun close() {
        cryptoCoroutineScope.coroutineContext.cancelChildren(CancellationException("Closing crypto module"))
        cryptoCoroutineScope.launch {
            withContext(coroutineDispatchers.crypto + NonCancellable) {
                cryptoStore.close()
            }
        }
    }

    // Always enabled on Matrix Android SDK2
    override fun isCryptoEnabled() = true

    /**
     * @return the Keys backup Service
     */
    override fun keysBackupService() = keysBackupService

    /**
     * @return the VerificationService
     */
    override fun verificationService() = verificationService

    override fun crossSigningService() = crossSigningService

    /**
     * A sync response has been received
     */
    suspend fun onSyncCompleted() {
        if (isStarted()) {
            sendOutgoingRequests()
            // This isn't a copy paste error. Sending the outgoing requests may
            // claim one-time keys and establish 1-to-1 Olm sessions with devices, while some
            // outgoing requests are waiting for an Olm session to be established (e.g. forwarding
            // room keys or sharing secrets).

            // The second call sends out those requests that are waiting for the
            // keys claim request to be sent out.
            // This could be omitted but then devices might be waiting for the next
            sendOutgoingRequests()

            keysBackupService.maybeBackupKeys()
        }

        cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
            tryOrNull {
                gossipingBuffer.toList().let {
                    cryptoStore.saveGossipingEvents(it)
                }
                gossipingBuffer.clear()
            }
        }
    }

    /**
     * Provides the device information for a user id and a device Id
     *
     * @param userId   the user id
     * @param deviceId the device id
     */
    override suspend fun getDeviceInfo(userId: String, deviceId: String?): CryptoDeviceInfo? {
        if (userId.isEmpty() || deviceId.isNullOrEmpty()) return null
        return olmMachine.getCryptoDeviceInfo(userId, deviceId)
    }

    override suspend fun getCryptoDeviceInfo(userId: String): List<CryptoDeviceInfo> {
        return olmMachine.getCryptoDeviceInfo(userId)
    }

    override fun getLiveCryptoDeviceInfo(userId: String): Flow<List<CryptoDeviceInfo>> {
        return getLiveCryptoDeviceInfo(listOf(userId))
    }

    override fun getLiveCryptoDeviceInfo(userIds: List<String>): Flow<List<CryptoDeviceInfo>> {
        return olmMachine.getLiveDevices(userIds)
    }

    /**
     * Update the blocked/verified state of the given device.
     *
     * @param trustLevel the new trust level
     * @param userId     the owner of the device
     * @param deviceId   the unique identifier for the device.
     */
    override fun setDeviceVerification(trustLevel: DeviceTrustLevel, userId: String, deviceId: String) {
        // TODO
    }

    /**
     * Configure a room to use encryption.
     *
     * @param roomId             the room id to enable encryption in.
     * @param algorithm          the encryption config for the room.
     * @param membersId          list of members to start tracking their devices
     * @return true if the operation succeeds.
     */
    private suspend fun setEncryptionInRoom(roomId: String,
                                            algorithm: String?,
                                            membersId: List<String>): Boolean {
        // If we already have encryption in this room, we should ignore this event
        // (for now at least. Maybe we should alert the user somehow?)
        val existingAlgorithm = cryptoStore.getRoomAlgorithm(roomId)

        if (!existingAlgorithm.isNullOrEmpty() && existingAlgorithm != algorithm) {
            Timber.tag(loggerTag.value).e("setEncryptionInRoom() : Ignoring m.room.encryption event which requests a change of config in $roomId")
            return false
        }

        // TODO CHECK WITH VALERE
        cryptoStore.storeRoomAlgorithm(roomId, algorithm)

        if (algorithm != MXCRYPTO_ALGORITHM_MEGOLM) {
            Timber.tag(loggerTag.value).e("## CRYPTO | setEncryptionInRoom() : Unable to encrypt room $roomId with $algorithm")
            return false
        }

        // if encryption was not previously enabled in this room, we will have been
        // ignoring new device events for these users so far. We may well have
        // up-to-date lists for some users, for instance if we were sharing other
        // e2e rooms with them, so there is room for optimisation here, but for now
        // we just invalidate everyone in the room.
        if (null == existingAlgorithm) {
            Timber.tag(loggerTag.value).d("Enabling encryption in $roomId for the first time; invalidating device lists for all users therein")

            val userIds = ArrayList(membersId)
            olmMachine.updateTrackedUsers(userIds)
        }

        return true
    }

    /**
     * Tells if a room is encrypted with MXCRYPTO_ALGORITHM_MEGOLM
     *
     * @param roomId the room id
     * @return true if the room is encrypted with algorithm MXCRYPTO_ALGORITHM_MEGOLM
     */
    override fun isRoomEncrypted(roomId: String): Boolean {
        return cryptoSessionInfoProvider.isRoomEncrypted(roomId)
    }

    /**
     * @return the stored device keys for a user.
     */
    override suspend fun getUserDevices(userId: String): MutableList<CryptoDeviceInfo> {
        return this.getCryptoDeviceInfo(userId).toMutableList()
    }

    private fun isEncryptionEnabledForInvitedUser(): Boolean {
        return mxCryptoConfig.enableEncryptionForInvitedMembers
    }

    override fun getEncryptionAlgorithm(roomId: String): String? {
        return cryptoStore.getRoomAlgorithm(roomId)
    }

    /**
     * Determine whether we should encrypt messages for invited users in this room.
     * <p>
     * Check here whether the invited members are allowed to read messages in the room history
     * from the point they were invited onwards.
     *
     * @return true if we should encrypt messages for invited users.
     */
    override fun shouldEncryptForInvitedMembers(roomId: String): Boolean {
        return cryptoStore.shouldEncryptForInvitedMembers(roomId)
    }

    /**
     * Encrypt an event content according to the configuration of the room.
     *
     * @param eventContent the content of the event.
     * @param eventType    the type of the event.
     * @param roomId       the room identifier the event will be sent.
     */
    override suspend fun encryptEventContent(eventContent: Content,
                                             eventType: String,
                                             roomId: String): MXEncryptEventContentResult {
        // moved to crypto scope to have up to date values
        return withContext(coroutineDispatchers.crypto) {
            val algorithm = getEncryptionAlgorithm(roomId)
            if (algorithm != null) {
                val userIds = getRoomUserIds(roomId)
                val t0 = System.currentTimeMillis()
                Timber.tag(loggerTag.value).v("encryptEventContent() starts")
                measureTimeMillis {
                    preshareRoomKey(roomId, userIds)
                }.also {
                    Timber.d("Shared room key in room $roomId took $it ms")
                }
                val content = encrypt(roomId, eventType, eventContent)
                Timber.tag(loggerTag.value).v("## CRYPTO | encryptEventContent() : succeeds after ${System.currentTimeMillis() - t0} ms")
                MXEncryptEventContentResult(content, EventType.ENCRYPTED)
            } else {
                val reason = String.format(MXCryptoError.UNABLE_TO_ENCRYPT_REASON, MXCryptoError.NO_MORE_ALGORITHM_REASON)
                Timber.tag(loggerTag.value).e("encryptEventContent() : failed $reason")
                throw Failure.CryptoError(MXCryptoError.Base(MXCryptoError.ErrorType.UNABLE_TO_ENCRYPT, reason))
            }
        }
    }

    override fun discardOutboundSession(roomId: String) {
        olmMachine.discardRoomKey(roomId)
    }

    /**
     * Decrypt an event
     *
     * @param event    the raw event.
     * @param timeline the id of the timeline where the event is decrypted. It is used to prevent replay attack.
     * @return the MXEventDecryptionResult data, or throw in case of error
     */
    @Throws(MXCryptoError::class)
    override suspend fun decryptEvent(event: Event, timeline: String): MXEventDecryptionResult {
        return olmMachine.decryptRoomEvent(event)
    }

    /**
     * Decrypt an event asynchronously
     *
     * @param event    the raw event.
     * @param timeline the id of the timeline where the event is decrypted. It is used to prevent replay attack.
     * @param callback the callback to return data or null
     */
    override fun decryptEventAsync(event: Event, timeline: String, callback: MatrixCallback<MXEventDecryptionResult>) {
        // This isn't really used anywhere, maybe just remove it?
        // TODO
    }

    /**
     * Handle an m.room.encryption event.
     *
     * @param event the encryption event.
     */
    private fun onRoomEncryptionEvent(roomId: String, event: Event) {
        if (!event.isStateEvent()) {
            // Ignore
            Timber.tag(loggerTag.value).w("Invalid encryption event")
            return
        }

        // Do not load members here, would defeat lazy loading
        cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
//            val params = LoadRoomMembersTask.Params(roomId)
//            try {
//                loadRoomMembersTask.execute(params)
//            } catch (throwable: Throwable) {
//                Timber.e(throwable, "## CRYPTO | onRoomEncryptionEvent ERROR FAILED TO SETUP CRYPTO ")
//            } finally {
            val userIds = getRoomUserIds(roomId)
            setEncryptionInRoom(roomId, event.content?.get("algorithm")?.toString(), userIds)
//            }
        }
    }

    override fun onE2ERoomMemberLoadedFromServer(roomId: String) {
        cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
            val userIds = getRoomUserIds(roomId)
            // Because of LL we might want to update tracked users
            olmMachine.updateTrackedUsers(userIds)
        }
    }

    private fun getRoomUserIds(roomId: String): List<String> {
        val encryptForInvitedMembers = isEncryptionEnabledForInvitedUser() &&
                shouldEncryptForInvitedMembers(roomId)
        return cryptoSessionInfoProvider.getRoomUserIds(roomId, encryptForInvitedMembers)
    }

    /**
     * Handle a change in the membership state of a member of a room.
     *
     * @param event the membership event causing the change
     */
    private fun onRoomMembershipEvent(roomId: String, event: Event) {
        // We only care about the memberships if this room is encrypted
        if (isRoomEncrypted(roomId)) {
            return
        }

        event.stateKey?.let { userId ->
            val roomMember: RoomMemberContent? = event.content.toModel()
            val membership = roomMember?.membership
            if (membership == Membership.JOIN) {
                // make sure we are tracking the deviceList for this user.
                cryptoCoroutineScope.launch {
                    olmMachine.updateTrackedUsers(listOf(userId))
                }
            } else if (membership == Membership.INVITE &&
                    shouldEncryptForInvitedMembers(roomId) &&
                    isEncryptionEnabledForInvitedUser()) {
                // track the deviceList for this invited user.
                // Caution: there's a big edge case here in that federated servers do not
                // know what other servers are in the room at the time they've been invited.
                // They therefore will not send device updates if a user logs in whilst
                // their state is invite.
                cryptoCoroutineScope.launch {
                    olmMachine.updateTrackedUsers(listOf(userId))
                }
            } else {
                // nop
            }
        }
    }

    private fun onRoomHistoryVisibilityEvent(roomId: String, event: Event) {
        if (!event.isStateEvent()) return
        val eventContent = event.content.toModel<RoomHistoryVisibilityContent>()
        eventContent?.historyVisibility?.let {
            cryptoStore.setShouldEncryptForInvitedMembers(roomId, it != RoomHistoryVisibility.JOINED)
        }
    }

    private fun notifyRoomKeyReceived(
            roomId: String,
            sessionId: String,
    ) {
        megolmSessionImportManager.dispatchNewSession(roomId, sessionId)
    }

    suspend fun receiveSyncChanges(
            toDevice: ToDeviceSyncResponse?,
            deviceChanges: DeviceListResponse?,
            keyCounts: DeviceOneTimeKeysCountSyncResponse?) {
        // Decrypt and handle our to-device events
        val toDeviceEvents = this.olmMachine.receiveSyncChanges(toDevice, deviceChanges, keyCounts)

        // Notify the our listeners about room keys so decryption is retried.
        if (toDeviceEvents.events != null) {
            toDeviceEvents.events.forEach { event ->
                when (event.type) {
                    EventType.ROOM_KEY           -> {
                        val content = event.getClearContent().toModel<RoomKeyContent>() ?: return@forEach

                        val roomId = content.sessionId ?: return@forEach
                        val sessionId = content.sessionId

                        notifyRoomKeyReceived(roomId, sessionId)
                    }
                    EventType.FORWARDED_ROOM_KEY -> {
                        val content = event.getClearContent().toModel<ForwardedRoomKeyContent>() ?: return@forEach

                        val roomId = content.sessionId ?: return@forEach
                        val sessionId = content.sessionId

                        notifyRoomKeyReceived(roomId, sessionId)
                    }
                    EventType.SEND_SECRET        -> {
                        // The rust-sdk will clear this event if it's invalid, this will produce an invalid base64 error
                        // when we try to construct the recovery key.
                        val secretContent = event.getClearContent().toModel<SecretSendEventContent>() ?: return@forEach
                        this.keysBackupService.onSecretKeyGossip(secretContent.secretValue)
                    }
                    else                         -> {
                        this.verificationService.onEvent(null, event)
                    }
                }
                liveEventManager.get().dispatchOnLiveToDevice(event)
            }
        }
    }

    private suspend fun preshareRoomKey(roomId: String, roomMembers: List<String>) {
        claimMissingKeys(roomMembers)
        val keyShareLock = roomKeyShareLocks.getOrPut(roomId) { Mutex() }
        var sharedKey = false
        keyShareLock.withLock {
            coroutineScope {
                olmMachine.shareRoomKey(roomId, roomMembers).map {
                    when (it) {
                        is Request.ToDevice -> {
                            sharedKey = true
                            async {
                                sendToDevice(it)
                            }
                        }
                        else                -> {
                            // This request can only be a to-device request but
                            // we need to handle all our cases and put this
                            // async block for our joinAll to work.
                            async {}
                        }
                    }
                }.joinAll()
            }
        }

        // If we sent out a room key over to-device messages it's likely that we created a new one
        // Try to back the key up
        if (sharedKey) {
            keysBackupService.maybeBackupKeys()
        }
    }

    private suspend fun claimMissingKeys(roomMembers: List<String>) = keyClaimLock.withLock {
        val request = this.olmMachine.getMissingSessions(roomMembers)
        // This request can only be a keys claim request.
        when (request) {
            is Request.KeysClaim -> {
                claimKeys(request)
            }
            else                 -> {
            }
        }
    }

    private suspend fun encrypt(roomId: String, eventType: String, content: Content): Content {
        return olmMachine.encrypt(roomId, eventType, content)
    }

    private suspend fun uploadKeys(request: Request.KeysUpload) {
        try {
            val response = requestSender.uploadKeys(request)
            olmMachine.markRequestAsSent(request.requestId, RequestType.KEYS_UPLOAD, response)
        } catch (throwable: Throwable) {
            Timber.tag(loggerTag.value).e(throwable, "## CRYPTO uploadKeys(): error")
        }
    }

    private suspend fun queryKeys(request: Request.KeysQuery) {
        try {
            val response = requestSender.queryKeys(request)
            olmMachine.markRequestAsSent(request.requestId, RequestType.KEYS_QUERY, response)

            // Update the shields!
            cryptoCoroutineScope.launch {
                cryptoSessionInfoProvider.getRoomsWhereUsersAreParticipating(request.users).forEach { roomId ->
                    val userGroup = cryptoSessionInfoProvider.getUserListForShieldComputation(roomId)
                    val shield = crossSigningService.shieldForGroup(userGroup)
                    cryptoSessionInfoProvider.updateShieldForRoom(roomId, shield)
                }
            }
        } catch (throwable: Throwable) {
            Timber.tag(loggerTag.value).e(throwable, "## CRYPTO doKeyDownloadForUsers(): error")
        }
    }

    private suspend fun sendToDevice(request: Request.ToDevice) {
        try {
            requestSender.sendToDevice(request)
            olmMachine.markRequestAsSent(request.requestId, RequestType.TO_DEVICE, "{}")
        } catch (throwable: Throwable) {
            Timber.tag(loggerTag.value).e(throwable, "## CRYPTO sendToDevice(): error")
        }
    }

    private suspend fun claimKeys(request: Request.KeysClaim) {
        try {
            val response = requestSender.claimKeys(request)
            olmMachine.markRequestAsSent(request.requestId, RequestType.KEYS_CLAIM, response)
        } catch (throwable: Throwable) {
            Timber.tag(loggerTag.value).e(throwable, "## CRYPTO claimKeys(): error")
        }
    }

    private suspend fun signatureUpload(request: Request.SignatureUpload) {
        try {
            val response = requestSender.sendSignatureUpload(request)
            olmMachine.markRequestAsSent(request.requestId, RequestType.SIGNATURE_UPLOAD, response)
        } catch (throwable: Throwable) {
            Timber.tag(loggerTag.value).e(throwable, "## CRYPTO signatureUpload(): error")
        }
    }

    private suspend fun sendRoomMessage(request: Request.RoomMessage) {
        try {
            Timber.v("SendRoomMessage: $request")
            val response = requestSender.sendRoomMessage(request)
            olmMachine.markRequestAsSent(request.requestId, RequestType.ROOM_MESSAGE, response)
        } catch (throwable: Throwable) {
            Timber.tag(loggerTag.value).e(throwable, "## CRYPTO sendRoomMessage(): error")
        }
    }

    private suspend fun sendOutgoingRequests() {
        outgoingRequestsLock.withLock {
            coroutineScope {
                Timber.v("OutgoingRequests: ${olmMachine.outgoingRequests()}")
                olmMachine.outgoingRequests().map {
                    when (it) {
                        is Request.KeysUpload      -> {
                            async {
                                uploadKeys(it)
                            }
                        }
                        is Request.KeysQuery       -> {
                            async {
                                queryKeys(it)
                            }
                        }
                        is Request.ToDevice        -> {
                            async {
                                sendToDevice(it)
                            }
                        }
                        is Request.KeysClaim       -> {
                            async {
                                claimKeys(it)
                            }
                        }
                        is Request.RoomMessage     -> {
                            async {
                                sendRoomMessage(it)
                            }
                        }
                        is Request.SignatureUpload -> {
                            async {
                                signatureUpload(it)
                            }
                        }
                        is Request.KeysBackup      -> {
                            async {
                                // The rust-sdk won't ever produce KeysBackup requests here,
                                // those only get explicitly created.
                            }
                        }
                    }
                }.joinAll()
            }
        }
    }

    /**
     * Export the crypto keys
     *
     * @param password the password
     * @return the exported keys
     */
    override suspend fun exportRoomKeys(password: String): ByteArray {
        val iterationCount = max(10000, MXMegolmExportEncryption.DEFAULT_ITERATION_COUNT)
        return olmMachine.exportKeys(password, iterationCount)
    }

    /**
     * Import the room keys
     *
     * @param roomKeysAsArray  the room keys as array.
     * @param password         the password
     * @param progressListener the progress listener
     * @return the result ImportRoomKeysResult
     */
    override suspend fun importRoomKeys(roomKeysAsArray: ByteArray,
                                        password: String,
                                        progressListener: ProgressListener?): ImportRoomKeysResult {
        val result = olmMachine.importKeys(roomKeysAsArray, password, progressListener).also {
            megolmSessionImportManager.dispatchKeyImportResults(it)
        }
        keysBackupService.maybeBackupKeys()

        return result
    }

    /**
     * Update the warn status when some unknown devices are detected.
     *
     * @param warn true to warn when some unknown devices are detected.
     */
    override fun setWarnOnUnknownDevices(warn: Boolean) {
        // TODO this doesn't seem to be used anymore?
        warnOnUnknownDevicesRepository.setWarnOnUnknownDevices(warn)
    }

    /**
     * Set the global override for whether the client should ever send encrypted
     * messages to unverified devices.
     * If false, it can still be overridden per-room.
     * If true, it overrides the per-room settings.
     *
     * @param block    true to unilaterally blacklist all
     */
    override fun setGlobalBlacklistUnverifiedDevices(block: Boolean) {
        cryptoStore.setGlobalBlacklistUnverifiedDevices(block)
    }

    /**
     * Tells whether the client should ever send encrypted messages to unverified devices.
     * The default value is false.
     * This function must be called in the getEncryptingThreadHandler() thread.
     *
     * @return true to unilaterally blacklist all unverified devices.
     */
    override fun getGlobalBlacklistUnverifiedDevices(): Boolean {
        return cryptoStore.getGlobalBlacklistUnverifiedDevices()
    }

    /**
     * Tells whether the client should encrypt messages only for the verified devices
     * in this room.
     * The default value is false.
     *
     * @param roomId the room id
     * @return true if the client should encrypt messages only for the verified devices.
     */
// TODO add this info in CryptoRoomEntity?
    override fun isRoomBlacklistUnverifiedDevices(roomId: String?): Boolean {
        return roomId?.let { cryptoStore.getRoomsListBlacklistUnverifiedDevices().contains(it) }
                ?: false
    }

    /**
     * Manages the room black-listing for unverified devices.
     *
     * @param roomId   the room id
     * @param add      true to add the room id to the list, false to remove it.
     */
    private fun setRoomBlacklistUnverifiedDevices(roomId: String, add: Boolean) {
        val roomIds = cryptoStore.getRoomsListBlacklistUnverifiedDevices().toMutableList()

        if (add) {
            if (roomId !in roomIds) {
                roomIds.add(roomId)
            }
        } else {
            roomIds.remove(roomId)
        }

        cryptoStore.setRoomsListBlacklistUnverifiedDevices(roomIds)
    }

    /**
     * Add this room to the ones which don't encrypt messages to unverified devices.
     *
     * @param roomId   the room id
     */
    override fun setRoomBlacklistUnverifiedDevices(roomId: String) {
        setRoomBlacklistUnverifiedDevices(roomId, true)
    }

    /**
     * Remove this room to the ones which don't encrypt messages to unverified devices.
     *
     * @param roomId   the room id
     */
    override fun setRoomUnBlacklistUnverifiedDevices(roomId: String) {
        setRoomBlacklistUnverifiedDevices(roomId, false)
    }

    /**
     * Re request the encryption keys required to decrypt an event.
     *
     * @param event the event to decrypt again.
     */
    override fun reRequestRoomKeyForEvent(event: Event) {
        cryptoCoroutineScope.launch(coroutineDispatchers.crypto) {
            val requestPair = olmMachine.requestRoomKey(event)

            val cancellation = requestPair.cancellation
            val request = requestPair.keyRequest

            when (cancellation) {
                is Request.ToDevice -> {
                    sendToDevice(cancellation)
                }
                else                -> Unit
            }
            when (request) {
                is Request.ToDevice -> {
                    sendToDevice(request)
                }
                else                -> Unit
            }
        }
    }

    /**
     * Add a GossipingRequestListener listener.
     *
     * @param listener listener
     */
    override fun addRoomKeysRequestListener(listener: GossipingRequestListener) {
        // TODO
    }

    /**
     * Add a GossipingRequestListener listener.
     *
     * @param listener listener
     */
    override fun removeRoomKeysRequestListener(listener: GossipingRequestListener) {
        // TODO
    }

    override suspend fun downloadKeys(userIds: List<String>, forceDownload: Boolean): MXUsersDevicesMap<CryptoDeviceInfo> {
        return withContext(coroutineDispatchers.crypto) {
            olmMachine.ensureUserDevicesMap(userIds, forceDownload)
        }
    }

    override fun addNewSessionListener(newSessionListener: NewSessionListener) {
        megolmSessionImportManager.addListener(newSessionListener)
    }

    override fun removeSessionListener(listener: NewSessionListener) {
        megolmSessionImportManager.removeListener(listener)
    }
/* ==========================================================================================
 * DEBUG INFO
 * ========================================================================================== */

    override fun toString(): String {
        return "DefaultCryptoService of $userId ($deviceId)"
    }

    override fun getOutgoingRoomKeyRequests(): List<OutgoingRoomKeyRequest> {
        return cryptoStore.getOutgoingRoomKeyRequests()
    }

    override fun getOutgoingRoomKeyRequestsPaged(): LiveData<PagedList<OutgoingRoomKeyRequest>> {
        return cryptoStore.getOutgoingRoomKeyRequestsPaged()
    }

    override fun getIncomingRoomKeyRequestsPaged(): LiveData<PagedList<IncomingRoomKeyRequest>> {
        return cryptoStore.getIncomingRoomKeyRequestsPaged()
    }

    override fun getIncomingRoomKeyRequests(): List<IncomingRoomKeyRequest> {
        return cryptoStore.getIncomingRoomKeyRequests()
    }

    override fun getGossipingEventsTrail(): LiveData<PagedList<Event>> {
        return cryptoStore.getGossipingEventsTrail()
    }

    override fun getGossipingEvents(): List<Event> {
        return cryptoStore.getGossipingEvents()
    }

    override fun getSharedWithInfo(roomId: String?, sessionId: String): MXUsersDevicesMap<Int> {
        return cryptoStore.getSharedWithInfo(roomId, sessionId)
    }

    override fun getWithHeldMegolmSession(roomId: String, sessionId: String): RoomKeyWithHeldContent? {
        return cryptoStore.getWithHeldMegolmSession(roomId, sessionId)
    }

    override fun logDbUsageInfo() {
        cryptoStore.logDbUsageInfo()
    }

    override suspend fun prepareToEncrypt(roomId: String) {
        withContext(coroutineDispatchers.crypto) {
            Timber.tag(loggerTag.value).d("prepareToEncrypt() roomId:$roomId Check room members up to date")
            // Ensure to load all room members
            try {
                loadRoomMembersTask.execute(LoadRoomMembersTask.Params(roomId))
            } catch (failure: Throwable) {
                Timber.tag(loggerTag.value).e("prepareToEncrypt() : Failed to load room members")
                throw failure
            }
            val userIds = getRoomUserIds(roomId)

            val algorithm = getEncryptionAlgorithm(roomId)

            if (algorithm == null) {
                val reason = String.format(MXCryptoError.UNABLE_TO_ENCRYPT_REASON, MXCryptoError.NO_MORE_ALGORITHM_REASON)
                Timber.tag(loggerTag.value).e("prepareToEncrypt() : $reason")
                throw IllegalArgumentException("Missing algorithm")
            }
            try {
                preshareRoomKey(roomId, userIds)
            } catch (failure: Throwable) {
                Timber.tag(loggerTag.value).e("prepareToEncrypt() : Failed to PreshareRoomKey")
            }
        }
    }

    /* ==========================================================================================
     * For test only
     * ========================================================================================== */

    @VisibleForTesting
    val cryptoStoreForTesting = cryptoStore

    companion object {
        const val CRYPTO_MIN_FORCE_SESSION_PERIOD_MILLIS = 3_600_000 // one hour
    }
}
