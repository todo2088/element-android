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

package im.vector.matrix.android.internal.session.group

import im.vector.matrix.android.api.session.group.Group
import im.vector.matrix.android.internal.session.SessionScope
import im.vector.matrix.android.internal.task.TaskExecutor
import javax.inject.Inject

internal interface GroupFactory {
    fun create(groupId: String): Group
}

@SessionScope
internal class DefaultGroupFactory @Inject constructor(private val getGroupDataTask: GetGroupDataTask,
                                                       private val taskExecutor: TaskExecutor) :
        GroupFactory {

    override fun create(groupId: String): Group {
        return DefaultGroup(
                groupId = groupId,
                taskExecutor = taskExecutor,
                getGroupDataTask = getGroupDataTask
        )
    }
}
