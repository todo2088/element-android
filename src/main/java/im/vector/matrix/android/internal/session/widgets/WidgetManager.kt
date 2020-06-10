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

package im.vector.matrix.android.internal.session.widgets

import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LiveData
import androidx.lifecycle.Transformations
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.query.QueryStringValue
import im.vector.matrix.android.api.session.events.model.Content
import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.events.model.EventType
import im.vector.matrix.android.api.session.events.model.toModel
import im.vector.matrix.android.api.session.integrationmanager.IntegrationManagerService
import im.vector.matrix.android.api.session.room.model.PowerLevelsContent
import im.vector.matrix.android.api.session.room.powerlevels.PowerLevelsHelper
import im.vector.matrix.android.api.session.widgets.WidgetManagementFailure
import im.vector.matrix.android.api.session.widgets.model.Widget
import im.vector.matrix.android.api.util.Cancelable
import im.vector.matrix.android.internal.di.UserId
import im.vector.matrix.android.internal.session.SessionScope
import im.vector.matrix.android.internal.session.integrationmanager.IntegrationManager
import im.vector.matrix.android.internal.session.room.state.StateEventDataSource
import im.vector.matrix.android.internal.session.sync.model.accountdata.UserAccountData
import im.vector.matrix.android.internal.session.sync.model.accountdata.UserAccountDataEvent
import im.vector.matrix.android.internal.session.user.accountdata.AccountDataDataSource
import im.vector.matrix.android.internal.session.widgets.helper.WidgetFactory
import im.vector.matrix.android.internal.session.widgets.helper.extractWidgetSequence
import im.vector.matrix.android.internal.task.TaskExecutor
import im.vector.matrix.android.internal.task.launchToCallback
import java.util.HashMap
import javax.inject.Inject

@SessionScope
internal class WidgetManager @Inject constructor(private val integrationManager: IntegrationManager,
                                                 private val accountDataDataSource: AccountDataDataSource,
                                                 private val stateEventDataSource: StateEventDataSource,
                                                 private val taskExecutor: TaskExecutor,
                                                 private val createWidgetTask: CreateWidgetTask,
                                                 private val widgetFactory: WidgetFactory,
                                                 @UserId private val userId: String) : IntegrationManagerService.Listener {

    private val lifecycleOwner: LifecycleOwner = LifecycleOwner { lifecycleRegistry }
    private val lifecycleRegistry: LifecycleRegistry = LifecycleRegistry(lifecycleOwner)

    fun start() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        integrationManager.addListener(this)
    }

    fun stop() {
        integrationManager.removeListener(this)
        lifecycleRegistry.currentState = Lifecycle.State.DESTROYED
    }

    fun getRoomWidgetsLive(
            roomId: String,
            widgetId: QueryStringValue = QueryStringValue.NoCondition,
            widgetTypes: Set<String>? = null,
            excludedTypes: Set<String>? = null
    ): LiveData<List<Widget>> {
        // Get all im.vector.modular.widgets state events in the room
        val liveWidgetEvents = stateEventDataSource.getStateEventsLive(
                roomId = roomId,
                eventTypes = setOf(EventType.STATE_ROOM_WIDGET, EventType.STATE_ROOM_WIDGET_LEGACY),
                stateKey = widgetId
        )
        return Transformations.map(liveWidgetEvents) { widgetEvents ->
            widgetEvents.mapEventsToWidgets(widgetTypes, excludedTypes)
        }
    }

    fun getRoomWidgets(
            roomId: String,
            widgetId: QueryStringValue = QueryStringValue.NoCondition,
            widgetTypes: Set<String>? = null,
            excludedTypes: Set<String>? = null
    ): List<Widget> {
        // Get all im.vector.modular.widgets state events in the room
        val widgetEvents: List<Event> = stateEventDataSource.getStateEvents(
                roomId = roomId,
                eventTypes = setOf(EventType.STATE_ROOM_WIDGET, EventType.STATE_ROOM_WIDGET_LEGACY),
                stateKey = widgetId
        )
        return widgetEvents.mapEventsToWidgets(widgetTypes, excludedTypes)
    }

    private fun List<Event>.mapEventsToWidgets(widgetTypes: Set<String>? = null,
                                               excludedTypes: Set<String>? = null): List<Widget> {
        val widgetEvents = this
        // Widget id -> widget
        val widgets: MutableMap<String, Widget> = HashMap()
        // Order widgetEvents with the last event first
        // There can be several im.vector.modular.widgets state events for a same widget but
        // only the last one must be considered.
        val sortedWidgetEvents = widgetEvents.sortedByDescending {
            it.originServerTs
        }
        // Create each widget from its latest im.vector.modular.widgets state event
        for (widgetEvent in sortedWidgetEvents) { // Filter widget types if required
            val widget = widgetFactory.create(widgetEvent) ?: continue
            val widgetType = widget.widgetContent.type ?: continue
            if (widgetTypes != null && !widgetTypes.contains(widgetType)) {
                continue
            }
            if (excludedTypes != null && excludedTypes.contains(widgetType)) {
                continue
            }
            if (!widgets.containsKey(widget.widgetId)) {
                widgets[widget.widgetId] = widget
            }
        }
        return widgets.values.toList()
    }

    fun getUserWidgetsLive(
            widgetTypes: Set<String>? = null,
            excludedTypes: Set<String>? = null
    ): LiveData<List<Widget>> {
        val widgetsAccountData = accountDataDataSource.getLiveAccountDataEvent(UserAccountData.TYPE_WIDGETS)
        return Transformations.map(widgetsAccountData) {
            it.getOrNull()?.mapToWidgets(widgetTypes, excludedTypes) ?: emptyList()
        }
    }

    fun getUserWidgets(
            widgetTypes: Set<String>? = null,
            excludedTypes: Set<String>? = null
    ): List<Widget> {
        val widgetsAccountData = accountDataDataSource.getAccountDataEvent(UserAccountData.TYPE_WIDGETS) ?: return emptyList()
        return widgetsAccountData.mapToWidgets(widgetTypes, excludedTypes)
    }

    private fun UserAccountDataEvent.mapToWidgets(widgetTypes: Set<String>? = null,
                                                  excludedTypes: Set<String>? = null): List<Widget> {
        return extractWidgetSequence(widgetFactory)
                .filter {
                    val widgetType = it.widgetContent.type ?: return@filter false
                    (widgetTypes == null || widgetTypes.contains(widgetType))
                            && (excludedTypes == null || !excludedTypes.contains(widgetType))
                }
                .toList()
    }

    fun createRoomWidget(roomId: String, widgetId: String, content: Content, callback: MatrixCallback<Widget>): Cancelable {
        return taskExecutor.executorScope.launchToCallback(callback = callback) {
            if (!hasPermissionsToHandleWidgets(roomId)) {
                throw WidgetManagementFailure.NotEnoughPower
            }
            val params = CreateWidgetTask.Params(
                    roomId = roomId,
                    widgetId = widgetId,
                    content = content
            )
            createWidgetTask.execute(params)
            try {
                getRoomWidgets(roomId, widgetId = QueryStringValue.Equals(widgetId, QueryStringValue.Case.INSENSITIVE)).first()
            } catch (failure: Throwable) {
                throw WidgetManagementFailure.CreationFailed
            }
        }
    }

    fun destroyRoomWidget(roomId: String, widgetId: String, callback: MatrixCallback<Unit>): Cancelable {
        return taskExecutor.executorScope.launchToCallback(callback = callback) {
            if (!hasPermissionsToHandleWidgets(roomId)) {
                throw WidgetManagementFailure.NotEnoughPower
            }
            val params = CreateWidgetTask.Params(
                    roomId = roomId,
                    widgetId = widgetId,
                    content = emptyMap()
            )
            createWidgetTask.execute(params)
        }
    }

    fun hasPermissionsToHandleWidgets(roomId: String): Boolean {
        val powerLevelsEvent = stateEventDataSource.getStateEvent(
                roomId = roomId,
                eventType = EventType.STATE_ROOM_POWER_LEVELS,
                stateKey = QueryStringValue.NoCondition
        )
        val powerLevelsContent = powerLevelsEvent?.content?.toModel<PowerLevelsContent>() ?: return false
        return PowerLevelsHelper(powerLevelsContent).isUserAllowedToSend(userId, true, null)
    }
}
