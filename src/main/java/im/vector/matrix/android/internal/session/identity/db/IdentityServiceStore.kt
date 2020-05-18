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
import im.vector.matrix.android.internal.session.identity.model.IdentityHashDetailResponse

internal interface IdentityServiceStore {

    fun getIdentityServerDetails(): IdentityServerEntity?

    fun setUrl(url: String?)

    fun setToken(token: String?)

    fun setHashDetails(hashDetailResponse: IdentityHashDetailResponse)

    /**
     * Store details about a current binding
     */
    fun storePendingBinding(threePid: ThreePid,
                            clientSecret: String,
                            sid: String)

    fun getPendingBinding(threePid: ThreePid): IdentityPendingBindingEntity?

    fun deletePendingBinding(threePid: ThreePid)
}
