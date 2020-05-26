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

package im.vector.matrix.android.api.session.room.uploads

import im.vector.matrix.android.api.session.events.model.Event
import im.vector.matrix.android.api.session.room.model.message.MessageWithAttachmentContent
import im.vector.matrix.android.api.session.room.sender.SenderInfo

/**
 * Wrapper around on Event.
 * Similar to [im.vector.matrix.android.api.session.room.timeline.TimelineEvent], contains an Event with extra useful data
 */
data class UploadEvent(
        val root: Event,
        val eventId: String,
        val contentWithAttachmentContent: MessageWithAttachmentContent,
        val senderInfo: SenderInfo
)
