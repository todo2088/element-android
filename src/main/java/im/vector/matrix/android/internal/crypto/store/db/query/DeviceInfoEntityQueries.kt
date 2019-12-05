/*
 * Copyright 2018 New Vector Ltd
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

package im.vector.matrix.android.internal.crypto.store.db.query

import im.vector.matrix.android.internal.crypto.store.db.model.DeviceInfoEntity
import im.vector.matrix.android.internal.crypto.store.db.model.DeviceInfoEntityFields
import im.vector.matrix.android.internal.crypto.store.db.model.createPrimaryKey
import io.realm.Realm
import io.realm.kotlin.createObject
import io.realm.kotlin.where

/**
 * Get or create a device info
 */
internal fun DeviceInfoEntity.Companion.getOrCreate(realm: Realm, userId: String, deviceId: String): DeviceInfoEntity {
    val key = DeviceInfoEntity.createPrimaryKey(userId, deviceId)

    return realm.where<DeviceInfoEntity>()
            .equalTo(DeviceInfoEntityFields.PRIMARY_KEY, key)
            .findFirst()
            ?: realm.createObject<DeviceInfoEntity>(key)
                    .apply {
                        this.deviceId = deviceId
                    }
}
