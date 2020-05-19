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
import im.vector.matrix.android.internal.di.IdentityDatabase
import im.vector.matrix.android.internal.session.SessionScope
import im.vector.matrix.android.internal.session.identity.data.IdentityPendingBinding
import im.vector.matrix.android.internal.session.identity.data.IdentityData
import im.vector.matrix.android.internal.session.identity.data.IdentityStore
import im.vector.matrix.android.internal.session.identity.model.IdentityHashDetailResponse
import io.realm.Realm
import io.realm.RealmConfiguration
import javax.inject.Inject

@SessionScope
internal class RealmIdentityStore @Inject constructor(
        @IdentityDatabase
        private val realmConfiguration: RealmConfiguration
) : IdentityStore {

    override fun getIdentityData(): IdentityData? {
        return Realm.getInstance(realmConfiguration).use { realm ->
            IdentityDataEntity.get(realm)?.let { IdentityMapper.map(it) }
        }
    }

    override fun setUrl(url: String?) {
        Realm.getInstance(realmConfiguration).use {
            it.executeTransaction { realm ->
                IdentityDataEntity.setUrl(realm, url)
            }
        }
    }

    override fun setToken(token: String?) {
        Realm.getInstance(realmConfiguration).use {
            it.executeTransaction { realm ->
                IdentityDataEntity.setToken(realm, token)
            }
        }
    }

    override fun setHashDetails(hashDetailResponse: IdentityHashDetailResponse) {
        Realm.getInstance(realmConfiguration).use {
            it.executeTransaction { realm ->
                IdentityDataEntity.setHashDetails(realm, hashDetailResponse.pepper, hashDetailResponse.algorithms)
            }
        }
    }

    override fun storePendingBinding(threePid: ThreePid, data: IdentityPendingBinding) {
        Realm.getInstance(realmConfiguration).use {
            it.executeTransaction { realm ->
                IdentityPendingBindingEntity.getOrCreate(realm, threePid).let { entity ->
                    entity.clientSecret = data.clientSecret
                    entity.sendAttempt = data.sendAttempt
                    entity.sid = data.sid
                }
            }
        }
    }

    override fun getPendingBinding(threePid: ThreePid): IdentityPendingBinding? {
        return Realm.getInstance(realmConfiguration).use { realm ->
            IdentityPendingBindingEntity.get(realm, threePid)?.let { IdentityMapper.map(it) }
        }
    }

    override fun deletePendingBinding(threePid: ThreePid) {
        Realm.getInstance(realmConfiguration).use {
            it.executeTransaction { realm ->
                IdentityPendingBindingEntity.delete(realm, threePid)
            }
        }
    }
}
