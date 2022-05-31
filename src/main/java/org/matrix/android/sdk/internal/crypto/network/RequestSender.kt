/*
 * Copyright (c) 2022 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.crypto.network

import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import dagger.Lazy
import org.matrix.android.sdk.api.auth.UserInteractiveAuthInterceptor
import org.matrix.android.sdk.api.failure.Failure
import org.matrix.android.sdk.api.failure.MatrixError
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysBackupLastVersionResult
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysVersion
import org.matrix.android.sdk.api.session.crypto.keysbackup.KeysVersionResult
import org.matrix.android.sdk.api.session.crypto.model.MXUsersDevicesMap
import org.matrix.android.sdk.api.session.events.model.Content
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.uia.UiaResult
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.internal.auth.registration.handleUIA
import org.matrix.android.sdk.internal.crypto.keysbackup.model.rest.BackupKeysResult
import org.matrix.android.sdk.internal.crypto.keysbackup.model.rest.CreateKeysBackupVersionBody
import org.matrix.android.sdk.internal.crypto.keysbackup.model.rest.KeysBackupData
import org.matrix.android.sdk.internal.crypto.keysbackup.model.rest.RoomKeysBackupData
import org.matrix.android.sdk.internal.crypto.keysbackup.model.rest.UpdateKeysBackupVersionBody
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.CreateKeysBackupVersionTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.DeleteBackupTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.GetKeysBackupLastVersionTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.GetKeysBackupVersionTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.GetRoomSessionDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.GetRoomSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.GetSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.StoreSessionsDataTask
import org.matrix.android.sdk.internal.crypto.keysbackup.tasks.UpdateKeysBackupVersionTask
import org.matrix.android.sdk.internal.crypto.model.rest.KeysClaimResponse
import org.matrix.android.sdk.internal.crypto.model.rest.KeysQueryResponse
import org.matrix.android.sdk.internal.crypto.model.rest.KeysUploadResponse
import org.matrix.android.sdk.internal.crypto.model.rest.RestKeyInfo
import org.matrix.android.sdk.internal.crypto.model.rest.SignatureUploadResponse
import org.matrix.android.sdk.internal.crypto.tasks.ClaimOneTimeKeysForUsersDeviceTask
import org.matrix.android.sdk.internal.crypto.tasks.DefaultSendVerificationMessageTask
import org.matrix.android.sdk.internal.crypto.tasks.DownloadKeysForUsersTask
import org.matrix.android.sdk.internal.crypto.tasks.SendToDeviceTask
import org.matrix.android.sdk.internal.crypto.tasks.SendVerificationMessageTask
import org.matrix.android.sdk.internal.crypto.tasks.UploadKeysTask
import org.matrix.android.sdk.internal.crypto.tasks.UploadSignaturesTask
import org.matrix.android.sdk.internal.crypto.tasks.UploadSigningKeysTask
import org.matrix.android.sdk.internal.di.MoshiProvider
import org.matrix.android.sdk.internal.network.parsing.CheckNumberType
import org.matrix.android.sdk.internal.session.room.send.SendResponse
import timber.log.Timber
import uniffi.olm.OutgoingVerificationRequest
import uniffi.olm.Request
import uniffi.olm.SignatureUploadRequest
import uniffi.olm.UploadSigningKeysRequest
import javax.inject.Inject

internal class RequestSender @Inject constructor(
        private val sendToDeviceTask: SendToDeviceTask,
        private val oneTimeKeysForUsersDeviceTask: ClaimOneTimeKeysForUsersDeviceTask,
        private val uploadKeysTask: UploadKeysTask,
        private val downloadKeysForUsersTask: DownloadKeysForUsersTask,
        private val signaturesUploadTask: UploadSignaturesTask,
        private val sendVerificationMessageTask: Lazy<DefaultSendVerificationMessageTask>,
        private val uploadSigningKeysTask: UploadSigningKeysTask,
        private val getKeysBackupLastVersionTask: GetKeysBackupLastVersionTask,
        private val getKeysBackupVersionTask: GetKeysBackupVersionTask,
        private val deleteBackupTask: DeleteBackupTask,
        private val createKeysBackupVersionTask: CreateKeysBackupVersionTask,
        private val backupRoomKeysTask: StoreSessionsDataTask,
        private val updateKeysBackupVersionTask: UpdateKeysBackupVersionTask,
        private val getSessionsDataTask: GetSessionsDataTask,
        private val getRoomSessionsDataTask: GetRoomSessionsDataTask,
        private val getRoomSessionDataTask: GetRoomSessionDataTask,
        private val moshi: Moshi,
) {
    companion object {
        const val REQUEST_RETRY_COUNT = 3
    }

    suspend fun claimKeys(request: Request.KeysClaim): String {
        val claimParams = ClaimOneTimeKeysForUsersDeviceTask.Params(request.oneTimeKeys)
        val response = oneTimeKeysForUsersDeviceTask.executeRetry(claimParams, REQUEST_RETRY_COUNT)
        val adapter = MoshiProvider
                .providesMoshi()
                .adapter(KeysClaimResponse::class.java)
        return adapter.toJson(response)!!
    }

    suspend fun queryKeys(request: Request.KeysQuery): String {
        val params = DownloadKeysForUsersTask.Params(request.users, null)
        val response = downloadKeysForUsersTask.executeRetry(params, REQUEST_RETRY_COUNT)
        val adapter = moshi.adapter(KeysQueryResponse::class.java)
        return adapter.toJson(response)!!
    }

    suspend fun uploadKeys(request: Request.KeysUpload): String {
        val body = moshi.adapter<JsonDict>(Map::class.java).fromJson(request.body)!!
        val params = UploadKeysTask.Params(body)

        val response = uploadKeysTask.executeRetry(params, REQUEST_RETRY_COUNT)
        val adapter = moshi.adapter(KeysUploadResponse::class.java)

        return adapter.toJson(response)!!
    }

    suspend fun sendVerificationRequest(request: OutgoingVerificationRequest) {
        when (request) {
            is OutgoingVerificationRequest.InRoom   -> sendRoomMessage(request)
            is OutgoingVerificationRequest.ToDevice -> sendToDevice(request)
        }
    }

    private suspend fun sendRoomMessage(request: OutgoingVerificationRequest.InRoom): SendResponse {
        return sendRoomMessage(request.eventType, request.roomId, request.content, request.requestId)
    }

    suspend fun sendRoomMessage(request: Request.RoomMessage): String {
        val sendResponse = sendRoomMessage(request.eventType, request.roomId, request.content, request.requestId)
        val responseAdapter = moshi.adapter(SendResponse::class.java)
        return responseAdapter.toJson(sendResponse)
    }

    suspend fun sendRoomMessage(eventType: String, roomId: String, content: String, transactionId: String): SendResponse {
        val paramsAdapter = moshi.adapter<Content>(Map::class.java)
        val jsonContent = paramsAdapter.fromJson(content)
        val event = Event(eventType, transactionId, jsonContent, roomId = roomId)
        val params = SendVerificationMessageTask.Params(event)
        return sendVerificationMessageTask.get().executeRetry(params, REQUEST_RETRY_COUNT)
    }

    suspend fun sendSignatureUpload(request: Request.SignatureUpload): String {
        return sendSignatureUpload(request.body)
    }

    suspend fun sendSignatureUpload(request: SignatureUploadRequest): String {
        return sendSignatureUpload(request.body)
    }

    private suspend fun sendSignatureUpload(body: String): String {
        val paramsAdapter = moshi.adapter<Map<String, Map<String, Any>>>(Map::class.java)
        val signatures = paramsAdapter.fromJson(body)!!
        val params = UploadSignaturesTask.Params(signatures)
        val response = signaturesUploadTask.executeRetry(params, REQUEST_RETRY_COUNT)
        val responseAdapter = moshi.adapter(SignatureUploadResponse::class.java)
        return responseAdapter.toJson(response)!!
    }

    suspend fun uploadCrossSigningKeys(
            request: UploadSigningKeysRequest,
            interactiveAuthInterceptor: UserInteractiveAuthInterceptor?
    ) {
        val adapter = moshi.adapter(RestKeyInfo::class.java)
        val masterKey = adapter.fromJson(request.masterKey)!!.toCryptoModel()
        val selfSigningKey = adapter.fromJson(request.selfSigningKey)!!.toCryptoModel()
        val userSigningKey = adapter.fromJson(request.userSigningKey)!!.toCryptoModel()

        val uploadSigningKeysParams = UploadSigningKeysTask.Params(
                masterKey,
                userSigningKey,
                selfSigningKey,
                null
        )

        try {
            uploadSigningKeysTask.execute(uploadSigningKeysParams)
        } catch (failure: Throwable) {
            if (interactiveAuthInterceptor == null ||
                    handleUIA(
                            failure = failure,
                            interceptor = interactiveAuthInterceptor,
                            retryBlock = { authUpdate ->
                                uploadSigningKeysTask.executeRetry(
                                        uploadSigningKeysParams.copy(userAuthParam = authUpdate),
                                        REQUEST_RETRY_COUNT
                                )
                            }
                    ) != UiaResult.SUCCESS
            ) {
                Timber.d("## UIA: propagate failure")
                throw failure
            }
        }
    }

    suspend fun sendToDevice(request: Request.ToDevice) {
        sendToDevice(request.eventType, request.body, request.requestId)
    }

    suspend fun sendToDevice(request: OutgoingVerificationRequest.ToDevice) {
        sendToDevice(request.eventType, request.body, request.requestId)
    }

    suspend fun sendToDevice(eventType: String, body: String, transactionId: String) {
        val adapter = moshi
                .newBuilder()
                .add(CheckNumberType.JSON_ADAPTER_FACTORY)
                .build()
                .adapter<Map<String, HashMap<String, Any>>>(Map::class.java)
        val jsonBody = adapter.fromJson(body)!!

        val userMap = MXUsersDevicesMap<Any>()
        userMap.join(jsonBody)

        val sendToDeviceParams = SendToDeviceTask.Params(eventType, userMap, transactionId)
        sendToDeviceTask.executeRetry(sendToDeviceParams, REQUEST_RETRY_COUNT)
    }

    suspend fun getKeyBackupVersion(version: String): KeysVersionResult? = getKeyBackupVersion {
        getKeysBackupVersionTask.executeRetry(version, 3)
    }

    suspend fun getKeyBackupLastVersion(): KeysBackupLastVersionResult? = getKeyBackupVersion {
        getKeysBackupLastVersionTask.executeRetry(Unit, 3)
    }

    private inline fun <reified T> getKeyBackupVersion(block: () -> T?): T? {
        return try {
            block()
        } catch (failure: Throwable) {
            if (failure is Failure.ServerError &&
                    failure.error.code == MatrixError.M_NOT_FOUND) {
                // Workaround because the homeserver currently returns M_NOT_FOUND when there is no key backup
                null
            } else {
                throw failure
            }
        }
    }

    suspend fun createKeyBackup(body: CreateKeysBackupVersionBody): KeysVersion {
        return createKeysBackupVersionTask.execute(body)
    }

    suspend fun deleteKeyBackup(version: String) {
        val params = DeleteBackupTask.Params(version)
        deleteBackupTask.execute(params)
    }

    suspend fun backupRoomKeys(request: Request.KeysBackup): String {
        val adapter = MoshiProvider
                .providesMoshi()
                .newBuilder()
                .add(CheckNumberType.JSON_ADAPTER_FACTORY)
                .build()
                .adapter<MutableMap<String, RoomKeysBackupData>>(
                        Types.newParameterizedType(
                                Map::class.java,
                                String::class.java,
                                RoomKeysBackupData::class.java
                        ))
        val keys = adapter.fromJson(request.rooms)!!
        val params = StoreSessionsDataTask.Params(request.version, KeysBackupData(keys))
        val response = backupRoomKeysTask.executeRetry(params, REQUEST_RETRY_COUNT)
        val responseAdapter = moshi.adapter(BackupKeysResult::class.java)
        return responseAdapter.toJson(response)!!
    }

    suspend fun updateBackup(keysBackupVersion: KeysVersionResult, body: UpdateKeysBackupVersionBody) {
        val params = UpdateKeysBackupVersionTask.Params(keysBackupVersion.version, body)
        updateKeysBackupVersionTask.executeRetry(params, REQUEST_RETRY_COUNT)
    }

    suspend fun downloadBackedUpKeys(version: String, roomId: String, sessionId: String): KeysBackupData {
        val data = getRoomSessionDataTask.execute(GetRoomSessionDataTask.Params(roomId, sessionId, version))

        return KeysBackupData(mutableMapOf(
                roomId to RoomKeysBackupData(mutableMapOf(
                        sessionId to data
                ))
        ))
    }

    suspend fun downloadBackedUpKeys(version: String, roomId: String): KeysBackupData {
        val data = getRoomSessionsDataTask.execute(GetRoomSessionsDataTask.Params(roomId, version))
        // Convert to KeysBackupData
        return KeysBackupData(mutableMapOf(roomId to data))
    }

    suspend fun downloadBackedUpKeys(version: String): KeysBackupData {
        return getSessionsDataTask.execute(GetSessionsDataTask.Params(version))
    }
}
