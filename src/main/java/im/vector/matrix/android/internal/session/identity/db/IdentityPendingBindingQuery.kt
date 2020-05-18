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

package im.vector.matrix.android.internal.session.identity.db

import im.vector.matrix.android.api.session.identity.ThreePid
import im.vector.matrix.android.api.session.identity.toMedium
import io.realm.Realm
import io.realm.kotlin.createObject
import io.realm.kotlin.where

internal fun IdentityPendingBindingEntity.Companion.get(realm: Realm, threePid: ThreePid): IdentityPendingBindingEntity? {
    return realm.where<IdentityPendingBindingEntity>()
            .equalTo(IdentityPendingBindingEntityFields.THREE_PID_VALUE, threePid.value)
            .equalTo(IdentityPendingBindingEntityFields.MEDIUM, threePid.toMedium())
            .findFirst()
}

internal fun IdentityPendingBindingEntity.Companion.getOrCreate(realm: Realm, threePid: ThreePid): IdentityPendingBindingEntity {
    return get(realm, threePid) ?: realm.createObject()
}

internal fun IdentityPendingBindingEntity.Companion.delete(realm: Realm, threePid: ThreePid) {
    realm.where<IdentityPendingBindingEntity>()
            .equalTo(IdentityPendingBindingEntityFields.THREE_PID_VALUE, threePid.value)
            .equalTo(IdentityPendingBindingEntityFields.MEDIUM, threePid.toMedium())
            .findAll()
            .deleteAllFromRealm()
}

internal fun IdentityPendingBindingEntity.Companion.deleteAll(realm: Realm) {
    realm.where<IdentityPendingBindingEntity>()
            .findAll()
            .deleteAllFromRealm()
}
