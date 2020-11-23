/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room.alias

import org.greenrobot.eventbus.EventBus
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.network.executeRequest
import org.matrix.android.sdk.internal.session.directory.DirectoryAPI
import org.matrix.android.sdk.internal.session.room.alias.RoomAliasAvailabilityChecker.Companion.toFullAlias
import org.matrix.android.sdk.internal.task.Task
import javax.inject.Inject

internal interface AddRoomAliasTask : Task<AddRoomAliasTask.Params, Unit> {
    data class Params(
            val roomId: String,
            val aliasLocalPart: String
    )
}

internal class DefaultAddRoomAliasTask @Inject constructor(
        @UserId private val userId: String,
        private val directoryAPI: DirectoryAPI,
        private val aliasAvailabilityChecker: RoomAliasAvailabilityChecker,
        private val eventBus: EventBus
) : AddRoomAliasTask {

    override suspend fun execute(params: AddRoomAliasTask.Params) {
        aliasAvailabilityChecker.check(params.aliasLocalPart)

        executeRequest<Unit>(eventBus) {
            apiCall = directoryAPI.addRoomAlias(
                    roomAlias = params.aliasLocalPart.toFullAlias(userId),
                    body = AddRoomAliasBody(
                            roomId = params.roomId
                    )
            )
        }
    }
}
