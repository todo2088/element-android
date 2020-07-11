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

package im.vector.matrix.android.internal.database

import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.api.session.crypto.CryptoService
import im.vector.matrix.android.api.session.crypto.MXCryptoError
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.internal.crypto.algorithms.olm.OlmDecryptionResult
import im.vector.matrix.android.internal.database.mapper.asDomain
import im.vector.matrix.android.internal.database.model.EventEntity
import im.vector.matrix.android.internal.database.model.EventInsertEntity
import im.vector.matrix.android.internal.database.query.where
import im.vector.matrix.android.internal.di.SessionDatabase
import im.vector.matrix.android.internal.session.EventInsertLiveProcessor
import io.realm.RealmConfiguration
import io.realm.RealmResults
import kotlinx.coroutines.launch
import timber.log.Timber
import javax.inject.Inject

internal class EventInsertLiveObserver @Inject constructor(@SessionDatabase realmConfiguration: RealmConfiguration,
                                                           private val processors: Set<@JvmSuppressWildcards EventInsertLiveProcessor>,
                                                           private val cryptoService: CryptoService)
    : RealmLiveEntityObserver<EventInsertEntity>(realmConfiguration) {

    override val query = Monarchy.Query<EventInsertEntity> {
        it.where(EventInsertEntity::class.java)
    }

    override fun onChange(results: RealmResults<EventInsertEntity>) {
        if (!results.isLoaded || results.isEmpty()) {
            return
        }
        Timber.v("EventInsertEntity updated with ${results.size} results in db")
        val filteredEvents = results.mapNotNull {
            if (shouldProcess(it)) {
                results.realm.copyFromRealm(it)
            } else {
                null
            }
        }
        Timber.v("There are ${filteredEvents.size} events to process")
        observerScope.launch {
            awaitTransaction(realmConfiguration) { realm ->
                filteredEvents.forEach { eventInsert ->
                    val eventId = eventInsert.eventId
                    val event = EventEntity.where(realm, eventId).findFirst()
                    if (event == null) {
                        Timber.v("Event $eventId not found")
                        return@forEach
                    }
                    val domainEvent = event.asDomain()
                    decryptIfNeeded(domainEvent)
                    processors.filter {
                        it.shouldProcess(eventId, domainEvent.getClearType(), eventInsert.insertType)
                    }.forEach {
                        it.process(realm, domainEvent)
                    }
                }
                realm.where(EventInsertEntity::class.java).findAll().deleteAllFromRealm()
            }
        }
    }

    private fun decryptIfNeeded(event: Event) {
        if (event.isEncrypted() && event.mxDecryptionResult == null) {
            try {
                val result = cryptoService.decryptEvent(event, event.roomId ?: "")
                event.mxDecryptionResult = OlmDecryptionResult(
                        payload = result.clearEvent,
                        senderKey = result.senderCurve25519Key,
                        keysClaimed = result.claimedEd25519Key?.let { k -> mapOf("ed25519" to k) },
                        forwardingCurve25519KeyChain = result.forwardingCurve25519KeyChain
                )
            } catch (e: MXCryptoError) {
                Timber.v("Failed to decrypt event")
                // TODO -> we should keep track of this and retry, or some processing will never be handled
            }
        }
    }

    private fun shouldProcess(eventInsertEntity: EventInsertEntity): Boolean {
        return processors.any {
            it.shouldProcess(eventInsertEntity.eventId, eventInsertEntity.eventType, eventInsertEntity.insertType)
        }
    }
}
