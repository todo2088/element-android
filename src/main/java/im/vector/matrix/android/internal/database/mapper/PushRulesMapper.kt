/*
 * Copyright 2019 New Vector Ltd
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
package im.vector.matrix.android.internal.database.mapper

import com.squareup.moshi.Types
import im.vector.matrix.android.api.pushrules.Condition
import im.vector.matrix.android.api.pushrules.rest.PushCondition
import im.vector.matrix.android.api.pushrules.rest.PushRule
import im.vector.matrix.android.internal.database.model.PushRuleEntity
import im.vector.matrix.android.internal.di.MoshiProvider
import io.realm.RealmList
import timber.log.Timber

internal object PushRulesMapper {

    private val moshiActionsAdapter = MoshiProvider.providesMoshi().adapter<List<Any>>(Types.newParameterizedType(List::class.java, Any::class.java))

//    private val listOfAnyAdapter: JsonAdapter<List<Any>> =
//            moshi.adapter<List<Any>>(Types.newParameterizedType(List::class.java, Any::class.java), kotlin.collections.emptySet(), "actions")

    fun mapContentRule(pushrule: PushRuleEntity): PushRule {
        return PushRule(
                actions = fromActionStr(pushrule.actionsStr),
                default = pushrule.default,
                enabled = pushrule.enabled,
                ruleId = pushrule.ruleId,
                conditions = listOf(
                        PushCondition(Condition.Kind.EventMatch.value, "content.body", pushrule.pattern)
                )
        )
    }

    private fun fromActionStr(actionsStr: String?): List<Any> {
        try {
            return actionsStr?.let { moshiActionsAdapter.fromJson(it) }.orEmpty()
        } catch (e: Throwable) {
            Timber.e(e, "## failed to map push rule actions <$actionsStr>")
            return emptyList()
        }
    }

    fun mapRoomRule(pushrule: PushRuleEntity): PushRule {
        return PushRule(
                actions = fromActionStr(pushrule.actionsStr),
                default = pushrule.default,
                enabled = pushrule.enabled,
                ruleId = pushrule.ruleId,
                conditions = listOf(
                        PushCondition(Condition.Kind.EventMatch.value, "room_id", pushrule.ruleId)
                )
        )
    }

    fun mapSenderRule(pushrule: PushRuleEntity): PushRule {
        return PushRule(
                actions = fromActionStr(pushrule.actionsStr),
                default = pushrule.default,
                enabled = pushrule.enabled,
                ruleId = pushrule.ruleId,
                conditions = listOf(
                        PushCondition(Condition.Kind.EventMatch.value, "user_id", pushrule.ruleId)
                )
        )
    }

    fun map(pushrule: PushRuleEntity): PushRule {
        return PushRule(
                actions = fromActionStr(pushrule.actionsStr),
                default = pushrule.default,
                enabled = pushrule.enabled,
                ruleId = pushrule.ruleId,
                conditions = pushrule.conditions?.map { PushConditionMapper.map(it) }
        )
    }

    fun map(pushRule: PushRule): PushRuleEntity {
        return PushRuleEntity(
                actionsStr = moshiActionsAdapter.toJson(pushRule.actions),
                default = pushRule.default ?: false,
                enabled = pushRule.enabled,
                ruleId = pushRule.ruleId,
                pattern = pushRule.pattern,
                conditions = pushRule.conditions?.let {
                    RealmList(*pushRule.conditions.map { PushConditionMapper.map(it) }.toTypedArray())
                } ?: RealmList()
        )
    }
}
