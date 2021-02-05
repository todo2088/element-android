/*
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.widgets.helper

import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.room.sender.SenderInfo
import org.matrix.android.sdk.api.session.widgets.model.Widget
import org.matrix.android.sdk.api.session.widgets.model.WidgetContent
import org.matrix.android.sdk.api.session.widgets.model.WidgetType
import org.matrix.android.sdk.internal.database.RealmSessionProvider
import org.matrix.android.sdk.internal.di.UserId
import org.matrix.android.sdk.internal.session.room.membership.RoomMemberHelper
import org.matrix.android.sdk.internal.session.user.UserDataSource
import java.net.URLEncoder
import javax.inject.Inject

internal class WidgetFactory @Inject constructor(private val userDataSource: UserDataSource,
                                                 private val realmSessionProvider: RealmSessionProvider,
                                                 @UserId private val userId: String) {

    fun create(widgetEvent: Event): Widget? {
        val widgetContent = widgetEvent.content.toModel<WidgetContent>()
        if (widgetContent?.url == null) return null
        val widgetId = widgetEvent.stateKey ?: return null
        val type = widgetContent.type ?: return null
        val senderInfo = if (widgetEvent.senderId == null || widgetEvent.roomId == null) {
            null
        } else {
            realmSessionProvider.withRealm {
                val roomMemberHelper = RoomMemberHelper(it, widgetEvent.roomId)
                val roomMemberSummaryEntity = roomMemberHelper.getLastRoomMember(widgetEvent.senderId)
                SenderInfo(
                        userId = widgetEvent.senderId,
                        displayName = roomMemberSummaryEntity?.displayName,
                        isUniqueDisplayName = roomMemberHelper.isUniqueDisplayName(roomMemberSummaryEntity?.displayName),
                        avatarUrl = roomMemberSummaryEntity?.avatarUrl
                )
            }
        }
        val isAddedByMe = widgetEvent.senderId == userId
        val computedUrl = widgetContent.computeURL(widgetEvent.roomId, widgetId)
        return Widget(
                widgetContent = widgetContent,
                event = widgetEvent,
                widgetId = widgetId,
                senderInfo = senderInfo,
                isAddedByMe = isAddedByMe,
                computedUrl = computedUrl,
                type = WidgetType.fromString(type)
        )
    }

    private fun WidgetContent.computeURL(roomId: String?, widgetId: String): String? {
        var computedUrl = url ?: return null
        val myUser = userDataSource.getUser(userId)
        computedUrl = computedUrl
                .replace("\$matrix_user_id", userId)
                .replace("\$matrix_display_name", myUser?.getBestName() ?: userId)
                .replace("\$matrix_avatar_url", myUser?.avatarUrl ?: "")
                .replace("\$matrix_widget_id", widgetId)

        if (roomId != null) {
            computedUrl = computedUrl.replace("\$matrix_room_id", roomId)
        }
        for ((key, value) in data) {
            if (value is String) {
                computedUrl = computedUrl.replace("$$key", URLEncoder.encode(value, "utf-8"))
            }
        }
        return computedUrl
    }
}
