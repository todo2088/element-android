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

package im.vector.matrix.android.internal.database.helper

import im.vector.matrix.android.internal.database.model.TimelineEventEntity
import im.vector.matrix.android.internal.database.model.TimelineEventEntityFields
import im.vector.matrix.android.internal.extensions.assertIsManaged
import io.realm.Realm

internal fun TimelineEventEntity.Companion.nextId(realm: Realm): Long {
    val currentIdNum = realm.where(TimelineEventEntity::class.java).max(TimelineEventEntityFields.LOCAL_ID)
    return if (currentIdNum == null) {
        1
    } else {
        currentIdNum.toLong() + 1
    }
}

internal fun TimelineEventEntity.deleteOnCascade() {
    assertIsManaged()
    root?.deleteFromRealm()
    annotations?.deleteFromRealm()
    readReceipts?.deleteFromRealm()
    deleteFromRealm()
}
