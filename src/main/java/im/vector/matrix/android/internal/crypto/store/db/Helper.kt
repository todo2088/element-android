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

package im.vector.matrix.android.internal.crypto.store.db

import android.util.Base64
import im.vector.matrix.android.internal.util.CompatUtil
import io.realm.Realm
import io.realm.RealmConfiguration
import io.realm.RealmObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.ObjectInputStream
import java.io.ObjectOutputStream
import java.util.zip.GZIPInputStream

/**
 * Get realm, invoke the action, close realm, and return the result of the action
 */
fun <T> doWithRealm(realmConfiguration: RealmConfiguration, action: (Realm) -> T): T {
    return Realm.getInstance(realmConfiguration).use { realm ->
        action.invoke(realm)
    }
}

/**
 * Get realm, do the query, copy from realm, close realm, and return the copied result
 */
fun <T : RealmObject> doRealmQueryAndCopy(realmConfiguration: RealmConfiguration, action: (Realm) -> T?): T? {
    return Realm.getInstance(realmConfiguration).use { realm ->
        action.invoke(realm)?.let { realm.copyFromRealm(it) }
    }
}

/**
 * Get realm, do the list query, copy from realm, close realm, and return the copied result
 */
fun <T : RealmObject> doRealmQueryAndCopyList(realmConfiguration: RealmConfiguration, action: (Realm) -> Iterable<T>): Iterable<T> {
    return Realm.getInstance(realmConfiguration).use { realm ->
        action.invoke(realm).let { realm.copyFromRealm(it) }
    }
}

/**
 * Get realm instance, invoke the action in a transaction and close realm
 */
fun doRealmTransaction(realmConfiguration: RealmConfiguration, action: (Realm) -> Unit) {
    Realm.getInstance(realmConfiguration).use { realm ->
        realm.executeTransaction { action.invoke(it) }
    }
}
fun doRealmTransactionAsync(realmConfiguration: RealmConfiguration, action: (Realm) -> Unit) {
    Realm.getInstance(realmConfiguration).use { realm ->
        realm.executeTransactionAsync { action.invoke(it) }
    }
}

/**
 * Serialize any Serializable object, zip it and convert to Base64 String
 */
fun serializeForRealm(o: Any?): String? {
    if (o == null) {
        return null
    }

    val baos = ByteArrayOutputStream()
    val gzis = CompatUtil.createGzipOutputStream(baos)
    val out = ObjectOutputStream(gzis)

    out.writeObject(o)
    out.close()

    return Base64.encodeToString(baos.toByteArray(), Base64.DEFAULT)
}

/**
 * Do the opposite of serializeForRealm.
 */
fun <T> deserializeFromRealm(string: String?): T? {
    if (string == null) {
        return null
    }

    val decodedB64 = Base64.decode(string.toByteArray(), Base64.DEFAULT)

    val bais = ByteArrayInputStream(decodedB64)
    val gzis = GZIPInputStream(bais)
    val ois = ObjectInputStream(gzis)

    @Suppress("UNCHECKED_CAST")
    val result = ois.readObject() as T

    ois.close()

    return result
}
