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
package im.vector.matrix.android.internal.crypto.verification

import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.api.session.crypto.CryptoService
import im.vector.matrix.android.api.session.crypto.MXCryptoError
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.room.model.message.MessageContent
import im.vector.matrix.android.api.session.room.model.message.MessageType
import im.vector.matrix.android.internal.crypto.algorithms.olm.OlmDecryptionResult
import im.vector.matrix.android.internal.database.RealmLiveEntityObserver
import im.vector.matrix.android.internal.database.mapper.asDomain
import im.vector.matrix.android.internal.database.model.EventEntity
import im.vector.matrix.android.internal.database.query.types
import im.vector.matrix.android.internal.di.SessionDatabase
import im.vector.matrix.android.internal.di.UserId
import im.vector.matrix.android.internal.task.TaskExecutor
import io.realm.OrderedCollectionChangeSet
import io.realm.RealmConfiguration
import io.realm.RealmResults
import timber.log.Timber
import java.util.*
import javax.inject.Inject

internal class VerificationMessageLiveObserver @Inject constructor(@SessionDatabase realmConfiguration: RealmConfiguration,
                                                                   @UserId private val userId: String,
                                                                   private val cryptoService: CryptoService,
                                                                   private val sasVerificationService: DefaultSasVerificationService,
                                                                   private val taskExecutor: TaskExecutor) :
        RealmLiveEntityObserver<EventEntity>(realmConfiguration) {

    override val query = Monarchy.Query<EventEntity> {
        EventEntity.types(it, listOf(
                EventType.KEY_VERIFICATION_START,
                EventType.KEY_VERIFICATION_ACCEPT,
                EventType.KEY_VERIFICATION_KEY,
                EventType.KEY_VERIFICATION_MAC,
                EventType.KEY_VERIFICATION_CANCEL,
                EventType.KEY_VERIFICATION_DONE,
                EventType.MESSAGE,
                EventType.ENCRYPTED)
        )
    }

    override fun onChange(results: RealmResults<EventEntity>, changeSet: OrderedCollectionChangeSet) {
        // TODO do that in a task
        // TODO how to ignore when it's an initial sync?
        val events = changeSet.insertions
                .asSequence()
                .mapNotNull { results[it]?.asDomain() }
                .filterNot {
                    // ignore mines ^^
                    it.senderId == userId
                }
                .toList()

        events.forEach { event ->
            Timber.d("## SAS Verification live observer: received msgId: ${event.eventId}   msgtype: ${event.type} from ${event.senderId}")
            Timber.v("## SAS Verification live observer: received msgId: $event")

            // decrypt if needed?

            if (event.isEncrypted() && event.mxDecryptionResult == null) {
                // TODO use a global event decryptor? attache to session and that listen to new sessionId?
                // for now decrypt sync
                try {
                    val result = cryptoService.decryptEvent(event, event.roomId + UUID.randomUUID().toString())
                    event.mxDecryptionResult = OlmDecryptionResult(
                            payload = result.clearEvent,
                            senderKey = result.senderCurve25519Key,
                            keysClaimed = result.claimedEd25519Key?.let { mapOf("ed25519" to it) },
                            forwardingCurve25519KeyChain = result.forwardingCurve25519KeyChain
                    )
                } catch (e: MXCryptoError) {
                    Timber.e("## SAS Failed to decrypt event: ${event.eventId}")
                }
            }
            Timber.v("## SAS Verification live observer: received msgId: ${event.eventId} type: ${event.getClearType()}")
            when (event.getClearType()) {
                EventType.KEY_VERIFICATION_START,
                EventType.KEY_VERIFICATION_ACCEPT,
                EventType.KEY_VERIFICATION_KEY,
                EventType.KEY_VERIFICATION_MAC,
                EventType.KEY_VERIFICATION_CANCEL,
                EventType.KEY_VERIFICATION_DONE -> {
                    sasVerificationService.onRoomEvent(event)
                }
                EventType.MESSAGE               -> {
                    if (MessageType.MSGTYPE_VERIFICATION_REQUEST == event.getClearContent().toModel<MessageContent>()?.type) {
                        // TODO  If the request is in the future by more than 5 minutes or more than 10 minutes in the past,
                        // the message should be ignored by the receiver.
                        sasVerificationService.onRoomRequestReceived(event)
                    }
                }
            }
        }
    }
}
