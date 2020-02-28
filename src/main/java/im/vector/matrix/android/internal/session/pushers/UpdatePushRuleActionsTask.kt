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
package im.vector.matrix.android.internal.session.pushers

import im.vector.matrix.android.api.pushrules.RuleKind
import im.vector.matrix.android.api.pushrules.rest.PushRule
import im.vector.matrix.android.internal.network.executeRequest
import im.vector.matrix.android.internal.task.Task
import org.greenrobot.eventbus.EventBus
import javax.inject.Inject

internal interface UpdatePushRuleActionsTask : Task<UpdatePushRuleActionsTask.Params, Unit> {
    data class Params(
            val kind: RuleKind,
            val oldPushRule: PushRule,
            val newPushRule: PushRule
    )
}

internal class DefaultUpdatePushRuleActionsTask @Inject constructor(
        private val pushRulesApi: PushRulesApi,
        private val eventBus: EventBus
) : UpdatePushRuleActionsTask {

    override suspend fun execute(params: UpdatePushRuleActionsTask.Params) {
        if (params.oldPushRule.enabled != params.newPushRule.enabled) {
            // First change enabled state
            executeRequest<Unit>(eventBus) {
                apiCall = pushRulesApi.updateEnableRuleStatus(params.kind.value, params.newPushRule.ruleId, params.newPushRule.enabled)
            }
        }

        if (params.newPushRule.enabled) {
            // Also ensure the actions are up to date
            val body = mapOf("actions" to params.newPushRule.actions)

            executeRequest<Unit>(eventBus) {
                apiCall = pushRulesApi.updateRuleActions(params.kind.value, params.newPushRule.ruleId, body)
            }
        }
    }
}
