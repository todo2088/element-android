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
package org.matrix.android.sdk.internal.crypto.tasks

import org.greenrobot.eventbus.EventBus
import org.matrix.android.sdk.api.session.crypto.CryptoService
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.room.send.SendState
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.room.RoomAPI
import org.matrix.android.sdk.internal.session.room.send.LocalEchoRepository
import org.matrix.android.sdk.internal.session.room.send.SendResponse
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface SendEventTask : Task<SendEventTask.Params, String> {
    data class Params(
            val event: Event,
            val encrypt: Boolean,
            val cryptoService: CryptoService?
    )
}

internal class DefaultSendEventTask @Inject constructor(
        private val localEchoRepository: LocalEchoRepository,
        private val encryptEventTask: DefaultEncryptEventTask,
        private val roomAPI: RoomAPI,
        private val eventBus: EventBus) : SendEventTask {

    override suspend fun execute(params: SendEventTask.Params): String {
        try {
            val event = handleEncryption(params)
            val localId = event.eventId!!

            localEchoRepository.updateSendState(localId, params.event.roomId, SendState.SENDING)
            val executeRequest = executeRequest<SendResponse>(eventBus) {
                apiCall = roomAPI.send(
                        localId,
                        roomId = event.roomId ?: "",
                        content = event.content,
                        eventType = event.type
                )
            }
            localEchoRepository.updateSendState(localId, params.event.roomId, SendState.SENT)
            return executeRequest.eventId
        } catch (e: Throwable) {
//            localEchoRepository.updateSendState(params.event.eventId!!, SendState.UNDELIVERED)
            throw e
        }
    }

    @Throws
    private suspend fun handleEncryption(params: SendEventTask.Params): Event {
        if (params.encrypt && !params.event.isEncrypted()) {
            return encryptEventTask.execute(EncryptEventTask.Params(
                    params.event.roomId ?: "",
                    params.event,
                    listOf("m.relates_to"),
                    params.cryptoService!!
            ))
        }
        return params.event
    }
}
