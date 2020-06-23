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

package im.vector.matrix.android.internal.database

import com.zhuinden.monarchy.Monarchy
import im.vector.matrix.android.internal.session.SessionLifecycleObserver
import im.vector.matrix.android.internal.util.createBackgroundHandler
import io.realm.OrderedRealmCollectionChangeListener
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmObject
import io.realm.RealmResults
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelChildren
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

internal interface LiveEntityObserver: SessionLifecycleObserver

internal abstract class RealmLiveEntityObserver<T : RealmObject>(protected val realmConfiguration: RealmConfiguration)
    : LiveEntityObserver, OrderedRealmCollectionChangeListener<RealmResults<T>> {

    private companion object {
        val BACKGROUND_HANDLER = createBackgroundHandler("LIVE_ENTITY_BACKGROUND")
    }

    protected val observerScope = CoroutineScope(SupervisorJob())
    protected abstract val query: Monarchy.Query<T>
    private val isStarted = AtomicBoolean(false)
    private val backgroundRealm = AtomicReference<Realm>()
    private lateinit var results: AtomicReference<RealmResults<T>>

    override fun onStart() {
        if (isStarted.compareAndSet(false, true)) {
            BACKGROUND_HANDLER.post {
                val realm = Realm.getInstance(realmConfiguration)
                backgroundRealm.set(realm)
                val queryResults = query.createQuery(realm).findAll()
                queryResults.addChangeListener(this)
                results = AtomicReference(queryResults)
            }
        }
    }

    override fun onStop() {
        if (isStarted.compareAndSet(true, false)) {
            BACKGROUND_HANDLER.post {
                results.getAndSet(null).removeAllChangeListeners()
                backgroundRealm.getAndSet(null).also {
                    it.close()
                }
                observerScope.coroutineContext.cancelChildren()
            }
        }
    }

    override fun onClearCache() {
        observerScope.coroutineContext.cancelChildren()
    }
}
