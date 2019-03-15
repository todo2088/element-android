/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package im.vector.matrix.android.internal.database.model

import io.realm.RealmObject
import io.realm.RealmResults
import io.realm.annotations.Index
import io.realm.annotations.LinkingObjects
import io.realm.annotations.PrimaryKey
import java.util.*

internal open class EventEntity(@PrimaryKey var localId: String = UUID.randomUUID().toString(),
                                @Index var eventId: String = "",
                                var roomId: String = "",
                                var type: String = "",
                                var content: String? = null,
                                var prevContent: String? = null,
                                var stateKey: String? = null,
                                var originServerTs: Long? = null,
                                var sender: String? = null,
                                var age: Long? = 0,
                                var redacts: String? = null,
                                var stateIndex: Int = 0,
                                var displayIndex: Int = 0,
                                var isUnlinked: Boolean = false
) : RealmObject() {

    enum class LinkFilterMode {
        LINKED_ONLY,
        UNLINKED_ONLY,
        BOTH
    }

    companion object

    @LinkingObjects("events")
    val chunk: RealmResults<ChunkEntity>? = null

    @LinkingObjects("untimelinedStateEvents")
    val room: RealmResults<RoomEntity>? = null

}