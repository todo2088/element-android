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

package org.matrix.android.sdk.common

import android.util.Log
import org.amshove.kluent.fail
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.matrix.android.sdk.api.auth.UIABaseAuth
import org.matrix.android.sdk.api.auth.UserInteractiveAuthInterceptor
import org.matrix.android.sdk.api.auth.UserPasswordAuth
import org.matrix.android.sdk.api.auth.registration.RegistrationFlowResponse
import org.matrix.android.sdk.api.crypto.MXCRYPTO_ALGORITHM_MEGOLM_BACKUP
import org.matrix.android.sdk.api.extensions.orFalse
import org.matrix.android.sdk.api.session.Session
import org.matrix.android.sdk.api.session.crypto.MXCryptoError
import org.matrix.android.sdk.api.session.crypto.crosssigning.KEYBACKUP_SECRET_SSSS_NAME
import org.matrix.android.sdk.api.session.crypto.crosssigning.MASTER_KEY_SSSS_NAME
import org.matrix.android.sdk.api.session.crypto.crosssigning.SELF_SIGNING_KEY_SSSS_NAME
import org.matrix.android.sdk.api.session.crypto.crosssigning.USER_SIGNING_KEY_SSSS_NAME
import org.matrix.android.sdk.api.session.crypto.keysbackup.BackupUtils
import org.matrix.android.sdk.api.session.crypto.keysbackup.MegolmBackupAuthData
import org.matrix.android.sdk.api.session.crypto.keysbackup.MegolmBackupCreationInfo
import org.matrix.android.sdk.api.session.crypto.model.OlmDecryptionResult
import org.matrix.android.sdk.api.session.crypto.verification.EVerificationState
import org.matrix.android.sdk.api.session.crypto.verification.SasVerificationTransaction
import org.matrix.android.sdk.api.session.crypto.verification.VerificationMethod
import org.matrix.android.sdk.api.session.events.model.EventType
import org.matrix.android.sdk.api.session.events.model.toModel
import org.matrix.android.sdk.api.session.getRoom
import org.matrix.android.sdk.api.session.getRoomSummary
import org.matrix.android.sdk.api.session.room.failure.JoinRoomFailure
import org.matrix.android.sdk.api.session.room.model.Membership
import org.matrix.android.sdk.api.session.room.model.RoomHistoryVisibility
import org.matrix.android.sdk.api.session.room.model.create.CreateRoomParams
import org.matrix.android.sdk.api.session.room.model.message.MessageContent
import org.matrix.android.sdk.api.session.room.roomSummaryQueryParams
import org.matrix.android.sdk.api.session.securestorage.EmptyKeySigner
import org.matrix.android.sdk.api.session.securestorage.KeyRef
import timber.log.Timber
import java.util.UUID
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume

class CryptoTestHelper(val testHelper: CommonTestHelper) {

    private val messagesFromAlice: List<String> = listOf("0 - Hello I'm Alice!", "4 - Go!")
    private val messagesFromBob: List<String> = listOf("1 - Hello I'm Bob!", "2 - Isn't life grand?", "3 - Let's go to the opera.")

    private val defaultSessionParams = SessionTestParams(true)

    /**
     * @return alice session
     */
    suspend fun doE2ETestWithAliceInARoom(encryptedRoom: Boolean = true, roomHistoryVisibility: RoomHistoryVisibility? = null): CryptoTestData {
        val aliceSession = testHelper.createAccount(TestConstants.USER_ALICE, defaultSessionParams)

        val roomId = aliceSession.roomService().createRoom(CreateRoomParams().apply {
            historyVisibility = roomHistoryVisibility
            name = "MyRoom"
        })
        if (encryptedRoom) {
            val room = aliceSession.getRoom(roomId)!!
            waitFor(
                    continueWhen = { room.onMain { getRoomSummaryLive() }.first { it.getOrNull()?.isEncrypted.orFalse() } },
                    action = { room.roomCryptoService().enableEncryption() }
            )
        }
        return CryptoTestData(roomId, listOf(aliceSession))
    }

    /**
     * @return alice and bob sessions
     */
    suspend fun doE2ETestWithAliceAndBobInARoom(encryptedRoom: Boolean = true, roomHistoryVisibility: RoomHistoryVisibility? = null): CryptoTestData {
        val cryptoTestData = doE2ETestWithAliceInARoom(encryptedRoom, roomHistoryVisibility)
        val aliceSession = cryptoTestData.firstSession
        val aliceRoomId = cryptoTestData.roomId

        val aliceRoom = aliceSession.getRoom(aliceRoomId)!!

        val bobSession = testHelper.createAccount(TestConstants.USER_BOB, defaultSessionParams)

        waitFor(
                continueWhen = { bobSession.roomService().onMain { getRoomSummariesLive(roomSummaryQueryParams { }) }.first { it.isNotEmpty() } },
                action = { aliceRoom.membershipService().invite(bobSession.myUserId) }
        )

        waitFor(
                continueWhen = {
                    bobSession.roomService().onMain { getRoomSummariesLive(roomSummaryQueryParams { }) }.first {
                        bobSession.getRoom(aliceRoomId)
                                ?.membershipService()
                                ?.getRoomMember(bobSession.myUserId)
                                ?.membership == Membership.JOIN
                    }
                },
                action = { bobSession.roomService().joinRoom(aliceRoomId) }
        )

        // Ensure bob can send messages to the room
//        val roomFromBobPOV = bobSession.getRoom(aliceRoomId)!!
//        assertNotNull(roomFromBobPOV.powerLevels)
//        assertTrue(roomFromBobPOV.powerLevels.maySendMessage(bobSession.myUserId))

        return CryptoTestData(aliceRoomId, listOf(aliceSession, bobSession))
    }

    suspend fun inviteNewUsersAndWaitForThemToJoin(session: Session, roomId: String, usernames: List<String>): List<Session> {
        val newSessions = usernames.map { username ->
            testHelper.createAccount(username, SessionTestParams(true)).also {
                it.cryptoService().enableKeyGossiping(false)
            }
        }

        val room = session.getRoom(roomId)!!

        Log.v("#E2E TEST", "accounts for ${usernames.joinToString(",") { it.take(10) }} created")
        // we want to invite them in the room
        newSessions.forEach { newSession ->
            Log.v("#E2E TEST", "${session.myUserId.take(10)} invites ${newSession.myUserId.take(10)}")
            room.membershipService().invite(newSession.myUserId)
        }

        // All user should accept invite
        newSessions.forEach { newSession ->
            waitForAndAcceptInviteInRoom(newSession, roomId)
            Log.v("#E2E TEST", "${newSession.myUserId.take(10)} joined room $roomId")
        }
        ensureMembersHaveJoined(session, newSessions, roomId)
        return newSessions
    }

    private suspend fun ensureMembersHaveJoined(session: Session, invitedUserSessions: List<Session>, roomId: String) {
        testHelper.retryWithBackoff(
                onFail = {
                    fail("Members ${invitedUserSessions.map { it.myUserId.take(10) }} should have join from the pov of ${session.myUserId.take(10)}")
                }
        ) {
            invitedUserSessions.map { invitedUserSession ->
                session.roomService().getRoomMember(invitedUserSession.myUserId, roomId)?.membership?.also {
                    Log.v("#E2E TEST", "${invitedUserSession.myUserId.take(10)} membership is $it")
                }
            }.all {
                it == Membership.JOIN
            }
        }
    }

    private suspend fun waitForAndAcceptInviteInRoom(session: Session, roomId: String) {
        testHelper.retryWithBackoff(
                onFail = {
                    fail("${session.myUserId} cannot see the invite from ${session.myUserId.take(10)}")
                }
        ) {
            val roomSummary = session.getRoomSummary(roomId)
            (roomSummary != null && roomSummary.membership == Membership.INVITE).also {
                if (it) {
                    Log.v("#E2E TEST", "${session.myUserId.take(10)} can see the invite from ${roomSummary?.inviterId}")
                }
            }
        }

        // not sure why it's taking so long :/
        Log.v("#E2E TEST", "${session.myUserId.take(10)} tries to join room $roomId")
        try {
            session.roomService().joinRoom(roomId)
        } catch (ex: JoinRoomFailure.JoinedWithTimeout) {
            // it's ok we will wait after
        }

        Log.v("#E2E TEST", "${session.myUserId} waiting for join echo ...")
        testHelper.retryWithBackoff(
                onFail = {
                    fail("${session.myUserId.take(10)} cannot see the join echo for ${roomId}")
                }
        ) {
            val roomSummary = session.getRoomSummary(roomId)
            roomSummary != null && roomSummary.membership == Membership.JOIN
        }
    }

    /**
     * @return Alice and Bob sessions
     */
    suspend fun doE2ETestWithAliceAndBobInARoomWithEncryptedMessages(): CryptoTestData {
        val cryptoTestData = doE2ETestWithAliceAndBobInARoom()
        val aliceSession = cryptoTestData.firstSession
        val aliceRoomId = cryptoTestData.roomId
        val bobSession = cryptoTestData.secondSession!!

        bobSession.cryptoService().setWarnOnUnknownDevices(false)
        aliceSession.cryptoService().setWarnOnUnknownDevices(false)

        val roomFromBobPOV = bobSession.getRoom(aliceRoomId)!!
        val roomFromAlicePOV = aliceSession.getRoom(aliceRoomId)!!

        // Alice sends a message
        testHelper.sendTextMessage(roomFromAlicePOV, messagesFromAlice[0], 1).first().eventId.let { sentEventId ->
            // ensure bob got it
            ensureEventReceived(aliceRoomId, sentEventId, bobSession, true)
        }

        // Bob send 3 messages
        testHelper.sendTextMessage(roomFromBobPOV, messagesFromBob[0], 1).first().eventId.let { sentEventId ->
            // ensure alice got it
            ensureEventReceived(aliceRoomId, sentEventId, aliceSession, true)
        }

        testHelper.sendTextMessage(roomFromBobPOV, messagesFromBob[1], 1).first().eventId.let { sentEventId ->
            // ensure alice got it
            ensureEventReceived(aliceRoomId, sentEventId, aliceSession, true)
        }
        testHelper.sendTextMessage(roomFromBobPOV, messagesFromBob[2], 1).first().eventId.let { sentEventId ->
            // ensure alice got it
            ensureEventReceived(aliceRoomId, sentEventId, aliceSession, true)
        }

        // Alice sends a message
        testHelper.sendTextMessage(roomFromAlicePOV, messagesFromAlice[1], 1).first().eventId.let { sentEventId ->
            // ensure bob got it
            ensureEventReceived(aliceRoomId, sentEventId, bobSession, true)
        }
        return cryptoTestData
    }

    private suspend fun ensureEventReceived(roomId: String, eventId: String, session: Session, andCanDecrypt: Boolean) {
        testHelper.retryPeriodically {
            val timeLineEvent = session.getRoom(roomId)?.timelineService()?.getTimelineEvent(eventId)
            if (andCanDecrypt) {
                timeLineEvent != null &&
                        timeLineEvent.isEncrypted() &&
                        timeLineEvent.root.getClearType() == EventType.MESSAGE
            } else {
                timeLineEvent != null
            }
        }
    }

    private fun createFakeMegolmBackupAuthData(): MegolmBackupAuthData {
        return MegolmBackupAuthData(
                publicKey = "abcdefg",
                signatures = mapOf("something" to mapOf("ed25519:something" to "hijklmnop"))
        )
    }

    fun createFakeMegolmBackupCreationInfo(): MegolmBackupCreationInfo {
        return MegolmBackupCreationInfo(
                algorithm = MXCRYPTO_ALGORITHM_MEGOLM_BACKUP,
                authData = createFakeMegolmBackupAuthData(),
                recoveryKey = BackupUtils.recoveryKeyFromPassphrase("3cnTdW")!!
        )
    }

    suspend fun createDM(alice: Session, bob: Session): String {
        var roomId = ""
        waitFor(
                continueWhen = {
                    bob.roomService()
                            .onMain { getRoomSummariesLive(roomSummaryQueryParams { }) }
                            .first { it.any { it.roomId == roomId }.orFalse() }
                },
                action = { roomId = alice.roomService().createDirectRoom(bob.myUserId) }
        )

        waitFor(
                continueWhen = {
                    bob.roomService()
                            .onMain { getRoomSummariesLive(roomSummaryQueryParams { }) }
                            .first {
                                bob.getRoom(roomId)
                                        ?.membershipService()
                                        ?.getRoomMember(bob.myUserId)
                                        ?.membership == Membership.JOIN
                            }
                },
                action = { bob.roomService().joinRoom(roomId) }
        )
        return roomId
    }

    suspend fun initializeCrossSigning(session: Session) {
            session.cryptoService().crossSigningService()
                    .initializeCrossSigning(
                            object : UserInteractiveAuthInterceptor {
                                override fun performStage(flowResponse: RegistrationFlowResponse, errCode: String?, promise: Continuation<UIABaseAuth>) {
                                    promise.resume(
                                            UserPasswordAuth(
                                                    user = session.myUserId,
                                                    password = TestConstants.PASSWORD,
                                                    session = flowResponse.session
                                            )
                                    )
                                }
                            })
    }

    /**
     * Initialize cross-signing, set up megolm backup and save all in 4S
     */
    suspend fun bootstrapSecurity(session: Session) {
        initializeCrossSigning(session)
        val ssssService = session.sharedSecretStorageService()
        val keyInfo = ssssService.generateKey(
                UUID.randomUUID().toString(),
                null,
                "ssss_key",
                EmptyKeySigner()
        )
        ssssService.setDefaultKey(keyInfo.keyId)

        ssssService.storeSecret(
                MASTER_KEY_SSSS_NAME,
                session.cryptoService().crossSigningService().getCrossSigningPrivateKeys()!!.master!!,
                listOf(KeyRef(keyInfo.keyId, keyInfo.keySpec))
        )

        ssssService.storeSecret(
                SELF_SIGNING_KEY_SSSS_NAME,
                session.cryptoService().crossSigningService().getCrossSigningPrivateKeys()!!.selfSigned!!,
                listOf(KeyRef(keyInfo.keyId, keyInfo.keySpec))
        )

        ssssService.storeSecret(
                USER_SIGNING_KEY_SSSS_NAME,
                session.cryptoService().crossSigningService().getCrossSigningPrivateKeys()!!.user!!,
                listOf(KeyRef(keyInfo.keyId, keyInfo.keySpec))
        )

        // set up megolm backup
        val creationInfo = session.cryptoService().keysBackupService().prepareKeysBackupVersion(null, null)
        val version = session.cryptoService().keysBackupService().createKeysBackupVersion(creationInfo)

        // Save it for gossiping
        session.cryptoService().keysBackupService().saveBackupRecoveryKey(creationInfo.recoveryKey, version = version.version)

        creationInfo.recoveryKey.toBase64().let { secret ->
            ssssService.storeSecret(
                    KEYBACKUP_SECRET_SSSS_NAME,
                    secret,
                    listOf(KeyRef(keyInfo.keyId, keyInfo.keySpec))
            )
        }
    }

    suspend fun verifySASCrossSign(alice: Session, bob: Session, roomId: String) {
        assertTrue(alice.cryptoService().crossSigningService().canCrossSign())
        assertTrue(bob.cryptoService().crossSigningService().canCrossSign())

        val aliceVerificationService = alice.cryptoService().verificationService()
        val bobVerificationService = bob.cryptoService().verificationService()

        val localId = UUID.randomUUID().toString()
        val requestID = aliceVerificationService.requestKeyVerificationInDMs(
                localId = localId,
                methods = listOf(VerificationMethod.SAS, VerificationMethod.QR_CODE_SCAN, VerificationMethod.QR_CODE_SHOW),
                otherUserId = bob.myUserId,
                roomId = roomId
        ).transactionId

        testHelper.retryWithBackoff(
                onFail = {
                    fail("Bob should see an incoming request from alice")
                }
        ) {
            bobVerificationService.getExistingVerificationRequests(alice.myUserId).firstOrNull {
                it.otherDeviceId == alice.sessionParams.deviceId
            } != null
        }

        val incomingRequest = bobVerificationService.getExistingVerificationRequests(alice.myUserId).first {
            it.otherDeviceId == alice.sessionParams.deviceId
        }

        Timber.v("#TEST Incoming request is $incomingRequest")

        Timber.v("#TEST let bob ready the verification with SAS method")
        bobVerificationService.readyPendingVerification(
                listOf(VerificationMethod.SAS),
                alice.myUserId,
                incomingRequest.transactionId
        )

        // wait for it to be readied
        testHelper.retryWithBackoff(
                onFail = {
                    fail("Alice should see the verification in ready state")
                }
        ) {
            val outgoingRequest = aliceVerificationService.getExistingVerificationRequest(bob.myUserId, requestID)
            outgoingRequest?.state == EVerificationState.Ready
        }

        Timber.v("#TEST let alice start the verification")
        aliceVerificationService.startKeyVerification(
                VerificationMethod.SAS,
                bob.myUserId,
                requestID,
        )

        // we should reach SHOW SAS on both
        var alicePovTx: SasVerificationTransaction? = null
        var bobPovTx: SasVerificationTransaction? = null

        testHelper.retryWithBackoff(
                onFail = {
                    fail("Alice should should see a verification code")
                }
        ) {
            alicePovTx = aliceVerificationService.getExistingTransaction(bob.myUserId, requestID)
                    as? SasVerificationTransaction
            Log.v("TEST", "== alicePovTx id:${requestID} is ${alicePovTx?.state()}")
            alicePovTx?.getDecimalCodeRepresentation() != null
        }
        // wait for alice to get the ready
        testHelper.retryWithBackoff(
                onFail = {
                    fail("Bob should should see a verification code")
                }
        ) {
            bobPovTx = bobVerificationService.getExistingTransaction(alice.myUserId, requestID)
                    as? SasVerificationTransaction
            Log.v("TEST", "== bobPovTx is ${bobPovTx?.state()}")
//            bobPovTx?.state == VerificationTxState.ShortCodeReady
            bobPovTx?.getDecimalCodeRepresentation() != null
        }

        assertEquals("SAS code do not match", alicePovTx!!.getDecimalCodeRepresentation(), bobPovTx!!.getDecimalCodeRepresentation())

        bobPovTx!!.userHasVerifiedShortCode()
        alicePovTx!!.userHasVerifiedShortCode()

        testHelper.retryWithBackoff {
            alice.cryptoService().crossSigningService().isUserTrusted(bob.myUserId)
        }

        testHelper.retryWithBackoff {
            bob.cryptoService().crossSigningService().isUserTrusted(alice.myUserId)
        }
    }

    suspend fun doE2ETestWithManyMembers(numberOfMembers: Int): CryptoTestData {
        val aliceSession = testHelper.createAccount(TestConstants.USER_ALICE, defaultSessionParams)
        aliceSession.cryptoService().setWarnOnUnknownDevices(false)

        val roomId = aliceSession.roomService().createRoom(CreateRoomParams().apply { name = "MyRoom" })
        val room = aliceSession.getRoom(roomId)!!

        room.roomCryptoService().enableEncryption()

        val sessions = mutableListOf(aliceSession)
        for (index in 1 until numberOfMembers) {
            val session = testHelper.createAccount("User_$index", defaultSessionParams)
            room.membershipService().invite(session.myUserId, null)
            println("TEST -> " + session.myUserId + " invited")
            session.roomService().joinRoom(room.roomId, null, emptyList())
            println("TEST -> " + session.myUserId + " joined")
            sessions.add(session)
        }

        return CryptoTestData(roomId, sessions)
    }

    suspend fun ensureCanDecrypt(sentEventIds: List<String>, session: Session, e2eRoomID: String, messagesText: List<String>) {
        sentEventIds.forEachIndexed { index, sentEventId ->
            testHelper.retryPeriodically {
                val event = session.getRoom(e2eRoomID)?.timelineService()?.getTimelineEvent(sentEventId)?.root
                        ?: return@retryPeriodically false
                try {
                    session.cryptoService().decryptEvent(event, "").let { result ->
                        event.mxDecryptionResult = OlmDecryptionResult(
                                payload = result.clearEvent,
                                senderKey = result.senderCurve25519Key,
                                keysClaimed = result.claimedEd25519Key?.let { mapOf("ed25519" to it) },
                                forwardingCurve25519KeyChain = result.forwardingCurve25519KeyChain,
                                isSafe = result.isSafe
                        )
                    }
                } catch (error: MXCryptoError) {
                    // nop
                }
                Log.v("TEST", "ensureCanDecrypt ${event.getClearType()} is ${event.getClearContent()}")
                event.getClearType() == EventType.MESSAGE &&
                        messagesText[index] == event.getClearContent()?.toModel<MessageContent>()?.body
            }
        }
    }

    suspend fun ensureCannotDecrypt(sentEventIds: List<String>, session: Session, e2eRoomID: String, expectedError: MXCryptoError.ErrorType? = null) {
        sentEventIds.forEach { sentEventId ->
            val event = session.getRoom(e2eRoomID)!!.timelineService().getTimelineEvent(sentEventId)!!.root
            try {
                session.cryptoService().decryptEvent(event, "")
                fail("Should not be able to decrypt event")
            } catch (error: MXCryptoError) {
                val errorType = (error as? MXCryptoError.Base)?.errorType
                if (expectedError == null) {
                    assertNotNull(errorType)
                } else {
                    assertEquals("Unexpected reason", expectedError, errorType)
                }
            }
        }
    }
}
