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

package im.vector.matrix.android.api.session.accountdata

import androidx.lifecycle.LiveData
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.util.Optional
import im.vector.matrix.android.internal.session.sync.model.accountdata.UserAccountDataEvent

interface AccountDataService {

    fun getAccountData(type: String): UserAccountDataEvent?

    fun getLiveAccountData(type: String): LiveData<Optional<UserAccountDataEvent>>

    fun getAccountData(filterType: List<String>): List<UserAccountDataEvent>

    fun getLiveAccountData(filterType: List<String>): LiveData<List<UserAccountDataEvent>>

    fun updateAccountData(type: String, data: Any, callback: MatrixCallback<Unit>? = null)
}
