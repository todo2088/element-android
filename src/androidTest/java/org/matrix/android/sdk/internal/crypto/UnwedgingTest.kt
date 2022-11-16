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

package org.matrix.android.sdk.internal.crypto

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.suspendCancellableCoroutine
import org.amshove.kluent.shouldBeEqualTo
import org.junit.Assert
import org.junit.Before
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import org.matrix.android.sdk.InstrumentedTest
import org.matrix.android.sdk.api.auth.UIABaseAuth
import org.matrix.android.sdk.api.auth.UserInteractiveAuthInterceptor
import org.matrix.android.sdk.api.auth.UserPasswordAuth
import org.matrix.android.sdk.api.auth.registration.RegistrationFlowResponse
import org.matrix.android.sdk.api.crypto.MXCryptoConfig
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.content.EncryptedEventContent
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.room.timeline.Timeline
import org.matrix.android.sdk.api.session.room.timeline.TimelineEvent
import org.matrix.android.sdk.api.session.room.timeline.TimelineSettings
import org.matrix.android.sdk.common.CommonTestHelper.Companion.runCryptoTest
import org.matrix.android.sdk.common.TestConstants
import org.matrix.android.sdk.internal.crypto.model.OlmSessionWrapper
import org.matrix.android.sdk.internal.crypto.store.db.deserializeFromRealm
import org.matrix.android.sdk.internal.crypto.store.db.serializeForRealm
import org.matrix.olm.OlmSession
import timber.log.Timber
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

/**
 * Ref:
 * - https://github.com/matrix-org/matrix-doc/pull/1719
 * - https://matrix.org/docs/spec/client_server/latest#recovering-from-undecryptable-messages
 * - https://github.com/matrix-org/matrix-js-sdk/pull/780
 * - https://github.com/matrix-org/matrix-ios-sdk/pull/778
 * - https://github.com/matrix-org/matrix-ios-sdk/pull/784
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.JVM)
class UnwedgingTest : InstrumentedTest {

    private lateinit var messagesReceivedByBob: List<TimelineEvent>

    @Before
    fun init() {
        messagesReceivedByBob = emptyList()
    }

    /**
     * - Alice & Bob in a e2e room
     * - Alice sends a 1st message with a 1st megolm session
     * - Store the olm session between A&B devices
     * - Alice sends a 2nd message with a 2nd megolm session
     * - Simulate Alice using a backup of her OS and make her crypto state like after the first message
     * - Alice sends a 3rd message with a 3rd megolm session but a wedged olm session
     *
     * What Bob must see:
     * -> No issue with the 2 first messages
     * -> The third event must fail to decrypt at first because Bob the olm session is wedged
     * -> This is automatically fixed after SDKs restarted the olm session
     */
    @Test
    fun testUnwedging() = runCryptoTest(
            context(),
            cryptoConfig = MXCryptoConfig(limitRoomKeyRequestsToMyDevices = false)
    ) { cryptoTestHelper, testHelper ->
        val cryptoTestData = cryptoTestHelper.doE2ETestWithAliceAndBobInARoom()

        val aliceSession = cryptoTestData.firstSession
        val aliceRoomId = cryptoTestData.roomId
        val bobSession = cryptoTestData.secondSession!!

        val aliceCryptoStore = (aliceSession.cryptoService() as DefaultCryptoService).cryptoStoreForTesting

        // bobSession.cryptoService().setWarnOnUnknownDevices(false)
        // aliceSession.cryptoService().setWarnOnUnknownDevices(false)

        val roomFromBobPOV = bobSession.getRoom(aliceRoomId)!!
        val roomFromAlicePOV = aliceSession.getRoom(aliceRoomId)!!

        val bobTimeline = roomFromBobPOV.timelineService().createTimeline(null, TimelineSettings(20))
        bobTimeline.start()

        messagesReceivedByBob = emptyList()

        // - Alice sends a 1st message with a 1st megolm session
        roomFromAlicePOV.sendService().sendTextMessage("First message")

        // Wait for the message to be received by Bob
        messagesReceivedByBob = bobTimeline.waitForMessages(expectedCount = 1)

        messagesReceivedByBob.size shouldBeEqualTo 1
        val firstMessageSession = messagesReceivedByBob[0].root.content.toModel<EncryptedEventContent>()!!.sessionId!!

        //  - Store the olm session between A&B devices
        // Let us pickle our session with bob here so we can later unpickle it
        // and wedge our session.
        val sessionIdsForBob = aliceCryptoStore.getDeviceSessionIds(bobSession.cryptoService().getMyCryptoDevice().identityKey()!!)
        sessionIdsForBob!!.size shouldBeEqualTo 1
        val olmSession = aliceCryptoStore.getDeviceSession(sessionIdsForBob.first(), bobSession.cryptoService().getMyCryptoDevice().identityKey()!!)!!

        val oldSession = serializeForRealm(olmSession.olmSession)

        aliceSession.cryptoService().discardOutboundSession(roomFromAlicePOV.roomId)

        messagesReceivedByBob = emptyList()
        Timber.i("## CRYPTO | testUnwedging:  Alice sends a 2nd message with a 2nd megolm session")
        // - Alice sends a 2nd message with a 2nd megolm session
        roomFromAlicePOV.sendService().sendTextMessage("Second message")

        // Wait for the message to be received by Bob
        messagesReceivedByBob = bobTimeline.waitForMessages(expectedCount = 2)

        messagesReceivedByBob.size shouldBeEqualTo 2
        // Session should have changed
        val secondMessageSession = messagesReceivedByBob[0].root.content.toModel<EncryptedEventContent>()!!.sessionId!!
        Assert.assertNotEquals(firstMessageSession, secondMessageSession)

        // Let us wedge the session now. Set crypto state like after the first message
        Timber.i("## CRYPTO | testUnwedging: wedge the session now. Set crypto state like after the first message")

        aliceCryptoStore.storeSession(
                OlmSessionWrapper(deserializeFromRealm<OlmSession>(oldSession)!!),
                bobSession.cryptoService().getMyCryptoDevice().identityKey()!!
        )
        // TODO mmm we can't do that with rust
//        olmDevice.clearOlmSessionCache()

        // Force new session, and key share
        aliceSession.cryptoService().discardOutboundSession(roomFromAlicePOV.roomId)

        Timber.i("## CRYPTO | testUnwedging: Alice sends a 3rd message with a 3rd megolm session but a wedged olm session")
        // - Alice sends a 3rd message with a 3rd megolm session but a wedged olm session
        roomFromAlicePOV.sendService().sendTextMessage("Third message")
        // Bob should not be able to decrypt, because the session key could not be sent
        // Wait for the message to be received by Bob
        messagesReceivedByBob = bobTimeline.waitForMessages(expectedCount = 3)

        messagesReceivedByBob.size shouldBeEqualTo 3

        val thirdMessageSession = messagesReceivedByBob[0].root.content.toModel<EncryptedEventContent>()!!.sessionId!!
        Timber.i("## CRYPTO | testUnwedging: third message session ID $thirdMessageSession")
        Assert.assertNotEquals(secondMessageSession, thirdMessageSession)

        Assert.assertEquals(EventType.ENCRYPTED, messagesReceivedByBob[0].root.getClearType())
        Assert.assertEquals(EventType.MESSAGE, messagesReceivedByBob[1].root.getClearType())
        Assert.assertEquals(EventType.MESSAGE, messagesReceivedByBob[2].root.getClearType())
        // Bob Should not be able to decrypt last message, because session could not be sent as the olm channel was wedged

        Assert.assertTrue(messagesReceivedByBob[0].root.mCryptoError == MXCryptoError.ErrorType.UNKNOWN_INBOUND_SESSION_ID)

        // It's a trick to force key request on fail to decrypt
            bobSession.cryptoService().crossSigningService()
                    .initializeCrossSigning(
                            object : UserInteractiveAuthInterceptor {
                                override fun performStage(flowResponse: RegistrationFlowResponse, errCode: String?, promise: Continuation<UIABaseAuth>) {
                                    promise.resume(
                                            UserPasswordAuth(
                                                    user = bobSession.myUserId,
                                                    password = TestConstants.PASSWORD,
                                                    session = flowResponse.session
                                            )
                                    )
                                }
                            })

        // Wait until we received back the key
        testHelper.retryPeriodically {
            // we should get back the key and be able to decrypt
            val result = tryOrNull {
                bobSession.cryptoService().decryptEvent(messagesReceivedByBob[0].root, "")
            }
            Timber.i("## CRYPTO | testUnwedging: decrypt result  ${result?.clearEvent}")
            result != null
        }

        bobTimeline.dispose()
    }
}

private suspend fun Timeline.waitForMessages(expectedCount: Int): List<TimelineEvent> {
    return suspendCancellableCoroutine { continuation ->
        val listener = object : Timeline.Listener {
            override fun onTimelineFailure(throwable: Throwable) {
                // noop
            }

            override fun onNewTimelineEvents(eventIds: List<String>) {
                // noop
            }

            override fun onTimelineUpdated(snapshot: List<TimelineEvent>) {
                val messagesReceived = snapshot.filter { it.root.type == EventType.ENCRYPTED }

                if (messagesReceived.size == expectedCount) {
                    removeListener(this)
                    continuation.resume(messagesReceived)
                }
            }
        }

        addListener(listener)
        continuation.invokeOnCancellation { removeListener(listener) }
    }
}
