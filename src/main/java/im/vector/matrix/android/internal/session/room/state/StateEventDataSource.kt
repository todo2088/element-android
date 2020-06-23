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

package im.vector.matrix.android.internal.session.room.state

import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.api.query.QueryStringValue
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.util.Optional
import im.vector.matrix.android.api.util.toOptional
import im.vector.matrix.android.internal.database.mapper.asDomain
import im.vector.matrix.android.internal.database.model.CurrentStateEventEntity
import im.vector.matrix.android.internal.database.model.CurrentStateEventEntityFields
import im.vector.matrix.android.internal.di.SessionDatabase
import im.vector.matrix.android.internal.query.process
import io.realm.Realm
import io.realm.RealmQuery
import io.realm.kotlin.where
import javax.inject.Inject

internal class StateEventDataSource @Inject constructor(@SessionDatabase private val monarchy: Monarchy) {

    fun getStateEvent(roomId: String, eventType: String, stateKey: QueryStringValue): Event? {
        return Realm.getInstance(monarchy.realmConfiguration).use { realm ->
            buildStateEventQuery(realm, roomId, setOf(eventType), stateKey).findFirst()?.root?.asDomain()
        }
    }

    fun getStateEventLive(roomId: String, eventType: String, stateKey: QueryStringValue): LiveData<Optional<Event>> {
        val liveData = monarchy.findAllMappedWithChanges(
                { realm -> buildStateEventQuery(realm, roomId, setOf(eventType), stateKey) },
                { it.root?.asDomain() }
        )
        return Transformations.map(liveData) { results ->
            results.firstOrNull().toOptional()
        }
    }

    fun getStateEvents(roomId: String, eventTypes: Set<String>, stateKey: QueryStringValue): List<Event> {
        return Realm.getInstance(monarchy.realmConfiguration).use { realm ->
            buildStateEventQuery(realm, roomId, eventTypes, stateKey)
                    .findAll()
                    .mapNotNull {
                        it.root?.asDomain()
                    }
        }
    }

    fun getStateEventsLive(roomId: String, eventTypes: Set<String>, stateKey: QueryStringValue): LiveData<List<Event>> {
        val liveData = monarchy.findAllMappedWithChanges(
                { realm -> buildStateEventQuery(realm, roomId, eventTypes, stateKey) },
                { it.root?.asDomain() }
        )
        return Transformations.map(liveData) { results ->
            results.filterNotNull()
        }
    }

    private fun buildStateEventQuery(realm: Realm,
                                     roomId: String,
                                     eventTypes: Set<String>,
                                     stateKey: QueryStringValue
    ): RealmQuery<CurrentStateEventEntity> {
        return realm.where<CurrentStateEventEntity>()
                .equalTo(CurrentStateEventEntityFields.ROOM_ID, roomId)
                .`in`(CurrentStateEventEntityFields.TYPE, eventTypes.toTypedArray())
                .process(CurrentStateEventEntityFields.STATE_KEY, stateKey)
    }
}
