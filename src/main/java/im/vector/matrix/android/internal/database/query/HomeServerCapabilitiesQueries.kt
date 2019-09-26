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

package im.vector.matrix.android.internal.database.query

import im.vector.matrix.android.internal.database.model.HomeServerCapabilitiesEntity
import io.realm.Realm
import io.realm.kotlin.createObject
import io.realm.kotlin.where

/**
 * Get the current HomeServerCapabilitiesEntity, create one if it does not exist
 */
internal fun HomeServerCapabilitiesEntity.Companion.getOrCreate(realm: Realm): HomeServerCapabilitiesEntity {
    var homeServerCapabilitiesEntity = realm.where<HomeServerCapabilitiesEntity>().findFirst()
    if (homeServerCapabilitiesEntity == null) {
        realm.executeTransaction {
            realm.createObject<HomeServerCapabilitiesEntity>()
        }
        homeServerCapabilitiesEntity = realm.where<HomeServerCapabilitiesEntity>().findFirst()!!
    }

    return homeServerCapabilitiesEntity
}
