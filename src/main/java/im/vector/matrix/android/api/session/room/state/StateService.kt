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

package im.vector.matrix.android.api.session.room.state

import android.net.Uri
import androidx.lifecycle.LiveData
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.query.QueryStringValue
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.util.Cancelable
import im.vector.matrix.android.api.util.JsonDict
import im.vector.matrix.android.api.util.Optional

interface StateService {

    /**
     * Update the topic of the room
     */
    fun updateTopic(topic: String, callback: MatrixCallback<Unit>): Cancelable

    /**
     * Update the name of the room
     */
    fun updateName(name: String, callback: MatrixCallback<Unit>): Cancelable

    /**
     * Update the avatar of the room
     */
    fun updateAvatar(avatarUri: Uri, fileName: String, callback: MatrixCallback<Unit>): Cancelable

    fun sendStateEvent(eventType: String, stateKey: String?, body: JsonDict, callback: MatrixCallback<Unit>): Cancelable

    fun getStateEvent(eventType: String, stateKey: QueryStringValue = QueryStringValue.NoCondition): Event?

    fun getStateEventLive(eventType: String, stateKey: QueryStringValue = QueryStringValue.NoCondition): LiveData<Optional<Event>>

    fun getStateEvents(eventTypes: Set<String>, stateKey: QueryStringValue = QueryStringValue.NoCondition): List<Event>

    fun getStateEventsLive(eventTypes: Set<String>, stateKey: QueryStringValue = QueryStringValue.NoCondition): LiveData<List<Event>>
}
