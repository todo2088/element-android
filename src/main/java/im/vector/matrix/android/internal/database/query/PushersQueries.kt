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
package im.vector.matrix.android.internal.database.query

import im.vector.matrix.android.api.pushrules.RuleKind
import im.vector.matrix.android.internal.database.model.PushRuleEntity
import im.vector.matrix.android.internal.database.model.PushRuleEntityFields
import im.vector.matrix.android.internal.database.model.PushRulesEntity
import im.vector.matrix.android.internal.database.model.PushRulesEntityFields
import im.vector.matrix.android.internal.database.model.PusherEntity
import im.vector.matrix.android.internal.database.model.PusherEntityFields
import io.realm.Realm
import io.realm.RealmQuery
import io.realm.kotlin.where

internal fun PusherEntity.Companion.where(realm: Realm,
                                          pushKey: String? = null): RealmQuery<PusherEntity> {
    return realm.where<PusherEntity>()
            .apply {
                if (pushKey != null) {
                    equalTo(PusherEntityFields.PUSH_KEY, pushKey)
                }
            }
}

internal fun PushRulesEntity.Companion.where(realm: Realm,
                                             scope: String,
                                             kind: RuleKind): RealmQuery<PushRulesEntity> {
    return realm.where<PushRulesEntity>()
            .equalTo(PushRulesEntityFields.SCOPE, scope)
            .equalTo(PushRulesEntityFields.KIND_STR, kind.name)
}

internal fun PushRuleEntity.Companion.where(realm: Realm,
                                            scope: String,
                                            ruleId: String): RealmQuery<PushRuleEntity> {
    return realm.where<PushRuleEntity>()
            .equalTo("${PushRuleEntityFields.PARENT}.${PushRulesEntityFields.SCOPE}", scope)
            .equalTo(PushRuleEntityFields.RULE_ID, ruleId)
}
