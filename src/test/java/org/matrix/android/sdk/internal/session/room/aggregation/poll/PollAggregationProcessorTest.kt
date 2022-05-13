/*
 * Copyright 2022 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.internal.session.room.aggregation.poll

import org.junit.Test
import org.matrix.android.sdk.api.session.events.model.Event
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toContent
import org.matrix.android.sdk.api.session.room.model.message.MessagePollContent
import org.matrix.android.sdk.api.session.room.model.message.PollAnswer
import org.matrix.android.sdk.api.session.room.model.message.PollCreationInfo
import org.matrix.android.sdk.api.session.room.model.message.PollQuestion
import org.matrix.android.sdk.test.fakes.FakeMonarchy

private const val A_USER_ID_1 = "@user_1:matrix.org"
private const val A_USER_ID_2 = "@user_2:matrix.org"
private const val A_ROOM_ID = "!sUeOGZKsBValPTUMax:matrix.org"

private val A_POLL_CONTENT = MessagePollContent(
        unstablePollCreationInfo = PollCreationInfo(
                question = PollQuestion(
                        unstableQuestion = "What is your favourite coffee?"
                ),
                maxSelections = 1,
                answers = listOf(
                        PollAnswer(
                                id = "5ef5f7b0-c9a1-49cf-a0b3-374729a43e76",
                                unstableAnswer = "Double Espresso"
                        ),
                        PollAnswer(
                                id = "ec1a4db0-46d8-4d7a-9bb6-d80724715938",
                                unstableAnswer = "Macchiato"
                        ),
                        PollAnswer(
                                id = "3677ca8e-061b-40ab-bffe-b22e4e88fcad",
                                unstableAnswer = "Iced Coffee"
                        )
                )
        )
)

private val A_POLL_START_EVENT = Event(
        type = EventType.POLL_START.first(),
        eventId = "\$vApgexcL8Vfh-WxYKsFKCDooo67ttbjm3TiVKXaWijU",
        originServerTs = 1652435922563,
        senderId = A_USER_ID_1,
        roomId = A_ROOM_ID,
        content = A_POLL_CONTENT.toContent()
)

class PollAggregationProcessorTest {

    private val pollAggregationProcessor: PollAggregationProcessor = DefaultPollAggregationProcessor()
    private val monarchy = FakeMonarchy()

    @Test
    fun handlePollStartEvent() {
    }

    @Test
    fun handlePollResponseEvent() {
    }

    @Test
    fun handlePollEndEvent() {
    }
}
