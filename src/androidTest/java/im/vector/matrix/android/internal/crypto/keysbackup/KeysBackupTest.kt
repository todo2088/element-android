/*
 * Copyright 2018 New Vector Ltd
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

package im.vector.matrix.android.internal.crypto.keysbackup

import androidx.test.ext.junit.runners.AndroidJUnit4
import im.vector.matrix.android.InstrumentedTest
import im.vector.matrix.android.api.listeners.ProgressListener
import im.vector.matrix.android.api.listeners.StepProgressListener
import im.vector.matrix.android.api.session.Session
import im.vector.matrix.android.api.session.crypto.keysbackup.KeysBackupService
import im.vector.matrix.android.api.session.crypto.keysbackup.KeysBackupState
import im.vector.matrix.android.api.session.crypto.keysbackup.KeysBackupStateListener
import im.vector.matrix.android.common.CommonTestHelper
import im.vector.matrix.android.common.CryptoTestData
import im.vector.matrix.android.common.CryptoTestHelper
import im.vector.matrix.android.common.SessionTestParams
import im.vector.matrix.android.common.TestConstants
import im.vector.matrix.android.common.TestMatrixCallback
import im.vector.matrix.android.common.assertDictEquals
import im.vector.matrix.android.common.assertListEquals
import im.vector.matrix.android.internal.crypto.MXCRYPTO_ALGORITHM_MEGOLM_BACKUP
import im.vector.matrix.android.internal.crypto.MegolmSessionData
import im.vector.matrix.android.internal.crypto.OutgoingRoomKeyRequest
import im.vector.matrix.android.internal.crypto.crosssigning.DeviceTrustLevel
import im.vector.matrix.android.internal.crypto.keysbackup.model.KeysBackupVersionTrust
import im.vector.matrix.android.internal.crypto.keysbackup.model.MegolmBackupCreationInfo
import im.vector.matrix.android.internal.crypto.keysbackup.model.rest.KeysVersion
import im.vector.matrix.android.internal.crypto.keysbackup.model.rest.KeysVersionResult
import im.vector.matrix.android.internal.crypto.model.ImportRoomKeysResult
import im.vector.matrix.android.internal.crypto.model.OlmInboundGroupSessionWrapper
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.FixMethodOrder
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters
import java.util.ArrayList
import java.util.Collections
import java.util.concurrent.CountDownLatch

@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.JVM)
class KeysBackupTest : InstrumentedTest {

    private val mTestHelper = CommonTestHelper(context())
    private val mCryptoTestHelper = CryptoTestHelper(mTestHelper)

    private val defaultSessionParams = SessionTestParams(withInitialSync = false)
    private val defaultSessionParamsWithInitialSync = SessionTestParams(withInitialSync = true)

    /**
     * - From doE2ETestWithAliceAndBobInARoomWithEncryptedMessages, we should have no backed up keys
     * - Check backup keys after having marked one as backed up
     * - Reset keys backup markers
     */
    @Test
    fun roomKeysTest_testBackupStore_ok() {
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages()

        // From doE2ETestWithAliceAndBobInARoomWithEncryptedMessages, we should have no backed up keys
        val cryptoStore = (cryptoTestData.firstSession.cryptoService().keysBackupService() as DefaultKeysBackupService).store
        val sessions = cryptoStore.inboundGroupSessionsToBackup(100)
        val sessionsCount = sessions.size

        assertFalse(sessions.isEmpty())
        assertEquals(sessionsCount, cryptoTestData.firstSession.cryptoService().inboundGroupSessionsCount(false))
        assertEquals(0, cryptoTestData.firstSession.cryptoService().inboundGroupSessionsCount(true))

        // - Check backup keys after having marked one as backed up
        val session = sessions[0]

        cryptoStore.markBackupDoneForInboundGroupSessions(Collections.singletonList(session))

        assertEquals(sessionsCount, cryptoTestData.firstSession.cryptoService().inboundGroupSessionsCount(false))
        assertEquals(1, cryptoTestData.firstSession.cryptoService().inboundGroupSessionsCount(true))

        val sessions2 = cryptoStore.inboundGroupSessionsToBackup(100)
        assertEquals(sessionsCount - 1, sessions2.size)

        // - Reset keys backup markers
        cryptoStore.resetBackupMarkers()

        val sessions3 = cryptoStore.inboundGroupSessionsToBackup(100)
        assertEquals(sessionsCount, sessions3.size)
        assertEquals(sessionsCount, cryptoTestData.firstSession.cryptoService().inboundGroupSessionsCount(false))
        assertEquals(0, cryptoTestData.firstSession.cryptoService().inboundGroupSessionsCount(true))

        cryptoTestData.cleanUp(mTestHelper)
    }

    /**
     * Check that prepareKeysBackupVersionWithPassword returns valid data
     */
    @Test
    fun prepareKeysBackupVersionTest() {
        val bobSession = mTestHelper.createAccount(TestConstants.USER_BOB, defaultSessionParams)

        assertNotNull(bobSession.cryptoService().keysBackupService())

        val keysBackup = bobSession.cryptoService().keysBackupService()

        val stateObserver = StateObserver(keysBackup)

        assertFalse(keysBackup.isEnabled)

        val megolmBackupCreationInfo = mTestHelper.doSync<MegolmBackupCreationInfo> {
            keysBackup.prepareKeysBackupVersion(null, null, it)
        }

        assertEquals(MXCRYPTO_ALGORITHM_MEGOLM_BACKUP, megolmBackupCreationInfo.algorithm)
        assertNotNull(megolmBackupCreationInfo.authData)
        assertNotNull(megolmBackupCreationInfo.authData!!.publicKey)
        assertNotNull(megolmBackupCreationInfo.authData!!.signatures)
        assertNotNull(megolmBackupCreationInfo.recoveryKey)

        stateObserver.stopAndCheckStates(null)
        mTestHelper.signOutAndClose(bobSession)
    }

    /**
     * Test creating a keys backup version and check that createKeysBackupVersion() returns valid data
     */
    @Test
    fun createKeysBackupVersionTest() {
        val bobSession = mTestHelper.createAccount(TestConstants.USER_BOB, defaultSessionParams)

        val keysBackup = bobSession.cryptoService().keysBackupService()

        val stateObserver = StateObserver(keysBackup)

        assertFalse(keysBackup.isEnabled)

        val megolmBackupCreationInfo = mTestHelper.doSync<MegolmBackupCreationInfo> {
            keysBackup.prepareKeysBackupVersion(null, null, it)
        }

        assertFalse(keysBackup.isEnabled)

        // Create the version
        mTestHelper.doSync<KeysVersion> {
            keysBackup.createKeysBackupVersion(megolmBackupCreationInfo, it)
        }

        // Backup must be enable now
        assertTrue(keysBackup.isEnabled)

        stateObserver.stopAndCheckStates(null)
        mTestHelper.signOutAndClose(bobSession)
    }

    /**
     * - Check that createKeysBackupVersion() launches the backup
     * - Check the backup completes
     */
    @Test
    fun backupAfterCreateKeysBackupVersionTest() {
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages()

        val keysBackup = cryptoTestData.firstSession.cryptoService().keysBackupService()

        val latch = CountDownLatch(1)

        assertEquals(2, cryptoTestData.firstSession.cryptoService().inboundGroupSessionsCount(false))
        assertEquals(0, cryptoTestData.firstSession.cryptoService().inboundGroupSessionsCount(true))

        val stateObserver = StateObserver(keysBackup, latch, 5)

        prepareAndCreateKeysBackupData(keysBackup)

        mTestHelper.await(latch)

        val nbOfKeys = cryptoTestData.firstSession.cryptoService().inboundGroupSessionsCount(false)
        val backedUpKeys = cryptoTestData.firstSession.cryptoService().inboundGroupSessionsCount(true)

        assertEquals(2, nbOfKeys)
        assertEquals("All keys must have been marked as backed up", nbOfKeys, backedUpKeys)

        // Check the several backup state changes
        stateObserver.stopAndCheckStates(
                listOf(
                        KeysBackupState.Enabling,
                        KeysBackupState.ReadyToBackUp,
                        KeysBackupState.WillBackUp,
                        KeysBackupState.BackingUp,
                        KeysBackupState.ReadyToBackUp
                )
        )
        cryptoTestData.cleanUp(mTestHelper)
    }

    /**
     * Check that backupAllGroupSessions() returns valid data
     */
    @Test
    fun backupAllGroupSessionsTest() {
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages()

        val keysBackup = cryptoTestData.firstSession.cryptoService().keysBackupService()

        val stateObserver = StateObserver(keysBackup)

        prepareAndCreateKeysBackupData(keysBackup)

        // Check that backupAllGroupSessions returns valid data
        val nbOfKeys = cryptoTestData.firstSession.cryptoService().inboundGroupSessionsCount(false)

        assertEquals(2, nbOfKeys)

        var lastBackedUpKeysProgress = 0

        mTestHelper.doSync<Unit> {
            keysBackup.backupAllGroupSessions(object : ProgressListener {
                override fun onProgress(progress: Int, total: Int) {
                    assertEquals(nbOfKeys, total)
                    lastBackedUpKeysProgress = progress
                }
            }, it)
        }

        assertEquals(nbOfKeys, lastBackedUpKeysProgress)

        val backedUpKeys = cryptoTestData.firstSession.cryptoService().inboundGroupSessionsCount(true)

        assertEquals("All keys must have been marked as backed up", nbOfKeys, backedUpKeys)

        stateObserver.stopAndCheckStates(null)
        cryptoTestData.cleanUp(mTestHelper)
    }

    /**
     * Check encryption and decryption of megolm keys in the backup.
     * - Pick a megolm key
     * - Check [MXKeyBackup encryptGroupSession] returns stg
     * - Check [MXKeyBackup pkDecryptionFromRecoveryKey] is able to create a OLMPkDecryption
     * - Check [MXKeyBackup decryptKeyBackupData] returns stg
     * - Compare the decrypted megolm key with the original one
     */
    @Test
    fun testEncryptAndDecryptKeysBackupData() {
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages()

        val keysBackup = cryptoTestData.firstSession.cryptoService().keysBackupService() as DefaultKeysBackupService

        val stateObserver = StateObserver(keysBackup)

        // - Pick a megolm key
        val session = keysBackup.store.inboundGroupSessionsToBackup(1)[0]

        val keyBackupCreationInfo = prepareAndCreateKeysBackupData(keysBackup).megolmBackupCreationInfo

        // - Check encryptGroupSession() returns stg
        val keyBackupData = keysBackup.encryptGroupSession(session)
        assertNotNull(keyBackupData)
        assertNotNull(keyBackupData.sessionData)

        // - Check pkDecryptionFromRecoveryKey() is able to create a OlmPkDecryption
        val decryption = keysBackup.pkDecryptionFromRecoveryKey(keyBackupCreationInfo.recoveryKey)
        assertNotNull(decryption)
        // - Check decryptKeyBackupData() returns stg
        val sessionData = keysBackup
                .decryptKeyBackupData(keyBackupData,
                        session.olmInboundGroupSession!!.sessionIdentifier(),
                        cryptoTestData.roomId,
                        decryption!!)
        assertNotNull(sessionData)
        // - Compare the decrypted megolm key with the original one
        assertKeysEquals(session.exportKeys(), sessionData)

        stateObserver.stopAndCheckStates(null)
        cryptoTestData.cleanUp(mTestHelper)
    }

    /**
     * - Do an e2e backup to the homeserver with a recovery key
     * - Log Alice on a new device
     * - Restore the e2e backup from the homeserver with the recovery key
     * - Restore must be successful
     */
    @Test
    fun restoreKeysBackupTest() {
        val testData = createKeysBackupScenarioWithPassword(null)

        // - Restore the e2e backup from the homeserver
        val importRoomKeysResult = mTestHelper.doSync<ImportRoomKeysResult> {
            testData.aliceSession2.cryptoService().keysBackupService().restoreKeysWithRecoveryKey(testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion!!,
                    testData.prepareKeysBackupDataResult.megolmBackupCreationInfo.recoveryKey,
                    null,
                    null,
                    null,
                    it
            )
        }

        checkRestoreSuccess(testData, importRoomKeysResult.totalNumberOfKeys, importRoomKeysResult.successfullyNumberOfImportedKeys)

        testData.cleanUp(mTestHelper)
    }

    /**
     *
     * This is the same as `testRestoreKeyBackup` but this test checks that pending key
     * share requests are cancelled.
     *
     * - Do an e2e backup to the homeserver with a recovery key
     * - Log Alice on a new device
     * - *** Check the SDK sent key share requests
     * - Restore the e2e backup from the homeserver with the recovery key
     * - Restore must be successful
     * - *** There must be no more pending key share requests
     */
    @Test
    fun restoreKeysBackupAndKeyShareRequestTest() {
        fail("Check with Valere for this test. I think we do not send key share request")

        val testData = createKeysBackupScenarioWithPassword(null)

        // - Check the SDK sent key share requests
        val cryptoStore2 = (testData.aliceSession2.cryptoService().keysBackupService() as DefaultKeysBackupService).store
        val unsentRequest = cryptoStore2
                .getOutgoingRoomKeyRequestByState(setOf(OutgoingRoomKeyRequest.RequestState.UNSENT))
        val sentRequest = cryptoStore2
                .getOutgoingRoomKeyRequestByState(setOf(OutgoingRoomKeyRequest.RequestState.SENT))

        // Request is either sent or unsent
        assertTrue(unsentRequest != null || sentRequest != null)

        // - Restore the e2e backup from the homeserver
        val importRoomKeysResult = mTestHelper.doSync<ImportRoomKeysResult> {
            testData.aliceSession2.cryptoService().keysBackupService().restoreKeysWithRecoveryKey(testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion!!,
                    testData.prepareKeysBackupDataResult.megolmBackupCreationInfo.recoveryKey,
                    null,
                    null,
                    null,
                    it
            )
        }

        checkRestoreSuccess(testData, importRoomKeysResult.totalNumberOfKeys, importRoomKeysResult.successfullyNumberOfImportedKeys)

        // - There must be no more pending key share requests
        val unsentRequestAfterRestoration = cryptoStore2
                .getOutgoingRoomKeyRequestByState(setOf(OutgoingRoomKeyRequest.RequestState.UNSENT))
        val sentRequestAfterRestoration = cryptoStore2
                .getOutgoingRoomKeyRequestByState(setOf(OutgoingRoomKeyRequest.RequestState.SENT))

        // Request is either sent or unsent
        assertTrue(unsentRequestAfterRestoration == null && sentRequestAfterRestoration == null)

        testData.cleanUp(mTestHelper)
    }

    /**
     * - Do an e2e backup to the homeserver with a recovery key
     * - And log Alice on a new device
     * - The new device must see the previous backup as not trusted
     * - Trust the backup from the new device
     * - Backup must be enabled on the new device
     * - Retrieve the last version from the server
     * - It must be the same
     * - It must be trusted and must have with 2 signatures now
     */
    @Test
    fun trustKeyBackupVersionTest() {
        // - Do an e2e backup to the homeserver with a recovery key
        // - And log Alice on a new device
        val testData = createKeysBackupScenarioWithPassword(null)

        val stateObserver = StateObserver(testData.aliceSession2.cryptoService().keysBackupService())

        // - The new device must see the previous backup as not trusted
        assertNotNull(testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion)
        assertFalse(testData.aliceSession2.cryptoService().keysBackupService().isEnabled)
        assertEquals(KeysBackupState.NotTrusted, testData.aliceSession2.cryptoService().keysBackupService().state)

        // - Trust the backup from the new device
        mTestHelper.doSync<Unit> {
            testData.aliceSession2.cryptoService().keysBackupService().trustKeysBackupVersion(
                    testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion!!,
                    true,
                    it
            )
        }

        // Wait for backup state to be ReadyToBackUp
        waitForKeysBackupToBeInState(testData.aliceSession2, KeysBackupState.ReadyToBackUp)

        // - Backup must be enabled on the new device, on the same version
        assertEquals(testData.prepareKeysBackupDataResult.version, testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion?.version)
        assertTrue(testData.aliceSession2.cryptoService().keysBackupService().isEnabled)

        // - Retrieve the last version from the server
        val keysVersionResult = mTestHelper.doSync<KeysVersionResult?> {
            testData.aliceSession2.cryptoService().keysBackupService().getCurrentVersion(it)
        }

        // - It must be the same
        assertEquals(testData.prepareKeysBackupDataResult.version, keysVersionResult!!.version)

        val keysBackupVersionTrust = mTestHelper.doSync<KeysBackupVersionTrust> {
            testData.aliceSession2.cryptoService().keysBackupService().getKeysBackupTrust(keysVersionResult, it)
        }

        // - It must be trusted and must have 2 signatures now
        assertTrue(keysBackupVersionTrust.usable)
        assertEquals(2, keysBackupVersionTrust.signatures.size)

        stateObserver.stopAndCheckStates(null)
        testData.cleanUp(mTestHelper)
    }

    /**
     * - Do an e2e backup to the homeserver with a recovery key
     * - And log Alice on a new device
     * - The new device must see the previous backup as not trusted
     * - Trust the backup from the new device with the recovery key
     * - Backup must be enabled on the new device
     * - Retrieve the last version from the server
     * - It must be the same
     * - It must be trusted and must have with 2 signatures now
     */
    @Test
    fun trustKeyBackupVersionWithRecoveryKeyTest() {
        // - Do an e2e backup to the homeserver with a recovery key
        // - And log Alice on a new device
        val testData = createKeysBackupScenarioWithPassword(null)

        val stateObserver = StateObserver(testData.aliceSession2.cryptoService().keysBackupService())

        // - The new device must see the previous backup as not trusted
        assertNotNull(testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion)
        assertFalse(testData.aliceSession2.cryptoService().keysBackupService().isEnabled)
        assertEquals(KeysBackupState.NotTrusted, testData.aliceSession2.cryptoService().keysBackupService().state)

        // - Trust the backup from the new device with the recovery key
        mTestHelper.doSync<Unit> {
            testData.aliceSession2.cryptoService().keysBackupService().trustKeysBackupVersionWithRecoveryKey(
                    testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion!!,
                    testData.prepareKeysBackupDataResult.megolmBackupCreationInfo.recoveryKey,
                    it
            )
        }

        // Wait for backup state to be ReadyToBackUp
        waitForKeysBackupToBeInState(testData.aliceSession2, KeysBackupState.ReadyToBackUp)

        // - Backup must be enabled on the new device, on the same version
        assertEquals(testData.prepareKeysBackupDataResult.version, testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion?.version)
        assertTrue(testData.aliceSession2.cryptoService().keysBackupService().isEnabled)

        // - Retrieve the last version from the server
        val keysVersionResult = mTestHelper.doSync<KeysVersionResult?> {
            testData.aliceSession2.cryptoService().keysBackupService().getCurrentVersion(it)
        }

        // - It must be the same
        assertEquals(testData.prepareKeysBackupDataResult.version, keysVersionResult!!.version)

        val keysBackupVersionTrust = mTestHelper.doSync<KeysBackupVersionTrust> {
            testData.aliceSession2.cryptoService().keysBackupService().getKeysBackupTrust(keysVersionResult, it)
        }

        // - It must be trusted and must have 2 signatures now
        assertTrue(keysBackupVersionTrust.usable)
        assertEquals(2, keysBackupVersionTrust.signatures.size)

        stateObserver.stopAndCheckStates(null)
        testData.cleanUp(mTestHelper)
    }

    /**
     * - Do an e2e backup to the homeserver with a recovery key
     * - And log Alice on a new device
     * - The new device must see the previous backup as not trusted
     * - Try to trust the backup from the new device with a wrong recovery key
     * - It must fail
     * - The backup must still be untrusted and disabled
     */
    @Test
    fun trustKeyBackupVersionWithWrongRecoveryKeyTest() {
        // - Do an e2e backup to the homeserver with a recovery key
        // - And log Alice on a new device
        val testData = createKeysBackupScenarioWithPassword(null)

        val stateObserver = StateObserver(testData.aliceSession2.cryptoService().keysBackupService())

        // - The new device must see the previous backup as not trusted
        assertNotNull(testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion)
        assertFalse(testData.aliceSession2.cryptoService().keysBackupService().isEnabled)
        assertEquals(KeysBackupState.NotTrusted, testData.aliceSession2.cryptoService().keysBackupService().state)

        // - Try to trust the backup from the new device with a wrong recovery key
        val latch = CountDownLatch(1)
        testData.aliceSession2.cryptoService().keysBackupService().trustKeysBackupVersionWithRecoveryKey(
                testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion!!,
                "Bad recovery key",
                TestMatrixCallback(latch, false)
        )
        mTestHelper.await(latch)

        // - The new device must still see the previous backup as not trusted
        assertNotNull(testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion)
        assertFalse(testData.aliceSession2.cryptoService().keysBackupService().isEnabled)
        assertEquals(KeysBackupState.NotTrusted, testData.aliceSession2.cryptoService().keysBackupService().state)

        stateObserver.stopAndCheckStates(null)
        testData.cleanUp(mTestHelper)
    }

    /**
     * - Do an e2e backup to the homeserver with a password
     * - And log Alice on a new device
     * - The new device must see the previous backup as not trusted
     * - Trust the backup from the new device with the password
     * - Backup must be enabled on the new device
     * - Retrieve the last version from the server
     * - It must be the same
     * - It must be trusted and must have with 2 signatures now
     */
    @Test
    fun trustKeyBackupVersionWithPasswordTest() {
        val password = "Password"

        // - Do an e2e backup to the homeserver with a password
        // - And log Alice on a new device
        val testData = createKeysBackupScenarioWithPassword(password)

        val stateObserver = StateObserver(testData.aliceSession2.cryptoService().keysBackupService())

        // - The new device must see the previous backup as not trusted
        assertNotNull(testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion)
        assertFalse(testData.aliceSession2.cryptoService().keysBackupService().isEnabled)
        assertEquals(KeysBackupState.NotTrusted, testData.aliceSession2.cryptoService().keysBackupService().state)

        // - Trust the backup from the new device with the password
        mTestHelper.doSync<Unit> {
            testData.aliceSession2.cryptoService().keysBackupService().trustKeysBackupVersionWithPassphrase(
                    testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion!!,
                    password,
                    it
            )
        }

        // Wait for backup state to be ReadyToBackUp
        waitForKeysBackupToBeInState(testData.aliceSession2, KeysBackupState.ReadyToBackUp)

        // - Backup must be enabled on the new device, on the same version
        assertEquals(testData.prepareKeysBackupDataResult.version, testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion?.version)
        assertTrue(testData.aliceSession2.cryptoService().keysBackupService().isEnabled)

        // - Retrieve the last version from the server
        val keysVersionResult = mTestHelper.doSync<KeysVersionResult?> {
            testData.aliceSession2.cryptoService().keysBackupService().getCurrentVersion(it)
        }

        // - It must be the same
        assertEquals(testData.prepareKeysBackupDataResult.version, keysVersionResult!!.version)

        val keysBackupVersionTrust = mTestHelper.doSync<KeysBackupVersionTrust> {
            testData.aliceSession2.cryptoService().keysBackupService().getKeysBackupTrust(keysVersionResult, it)
        }

        // - It must be trusted and must have 2 signatures now
        assertTrue(keysBackupVersionTrust.usable)
        assertEquals(2, keysBackupVersionTrust.signatures.size)

        stateObserver.stopAndCheckStates(null)
        testData.cleanUp(mTestHelper)
    }

    /**
     * - Do an e2e backup to the homeserver with a password
     * - And log Alice on a new device
     * - The new device must see the previous backup as not trusted
     * - Try to trust the backup from the new device with a wrong password
     * - It must fail
     * - The backup must still be untrusted and disabled
     */
    @Test
    fun trustKeyBackupVersionWithWrongPasswordTest() {
        val password = "Password"
        val badPassword = "Bad Password"

        // - Do an e2e backup to the homeserver with a password
        // - And log Alice on a new device
        val testData = createKeysBackupScenarioWithPassword(password)

        val stateObserver = StateObserver(testData.aliceSession2.cryptoService().keysBackupService())

        // - The new device must see the previous backup as not trusted
        assertNotNull(testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion)
        assertFalse(testData.aliceSession2.cryptoService().keysBackupService().isEnabled)
        assertEquals(KeysBackupState.NotTrusted, testData.aliceSession2.cryptoService().keysBackupService().state)

        // - Try to trust the backup from the new device with a wrong password
        val latch = CountDownLatch(1)
        testData.aliceSession2.cryptoService().keysBackupService().trustKeysBackupVersionWithPassphrase(
                testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion!!,
                badPassword,
                TestMatrixCallback(latch, false)
        )
        mTestHelper.await(latch)

        // - The new device must still see the previous backup as not trusted
        assertNotNull(testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion)
        assertFalse(testData.aliceSession2.cryptoService().keysBackupService().isEnabled)
        assertEquals(KeysBackupState.NotTrusted, testData.aliceSession2.cryptoService().keysBackupService().state)

        stateObserver.stopAndCheckStates(null)
        testData.cleanUp(mTestHelper)
    }

    /**
     * - Do an e2e backup to the homeserver with a recovery key
     * - Log Alice on a new device
     * - Try to restore the e2e backup with a wrong recovery key
     * - It must fail
     */
    @Test
    fun restoreKeysBackupWithAWrongRecoveryKeyTest() {
        val testData = createKeysBackupScenarioWithPassword(null)

        // - Try to restore the e2e backup with a wrong recovery key
        val latch2 = CountDownLatch(1)
        var importRoomKeysResult: ImportRoomKeysResult? = null
        testData.aliceSession2.cryptoService().keysBackupService().restoreKeysWithRecoveryKey(testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion!!,
                "EsTc LW2K PGiF wKEA 3As5 g5c4 BXwk qeeJ ZJV8 Q9fu gUMN UE4d",
                null,
                null,
                null,
                object : TestMatrixCallback<ImportRoomKeysResult>(latch2, false) {
                    override fun onSuccess(data: ImportRoomKeysResult) {
                        importRoomKeysResult = data
                        super.onSuccess(data)
                    }
                }
        )
        mTestHelper.await(latch2)

        // onSuccess may not have been called
        assertNull(importRoomKeysResult)

        testData.cleanUp(mTestHelper)
    }

    /**
     * - Do an e2e backup to the homeserver with a password
     * - Log Alice on a new device
     * - Restore the e2e backup with the password
     * - Restore must be successful
     */
    @Test
    fun testBackupWithPassword() {
        val password = "password"

        val testData = createKeysBackupScenarioWithPassword(password)

        // - Restore the e2e backup with the password
        val steps = ArrayList<StepProgressListener.Step>()

        val importRoomKeysResult = mTestHelper.doSync<ImportRoomKeysResult> {
            testData.aliceSession2.cryptoService().keysBackupService().restoreKeyBackupWithPassword(testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion!!,
                    password,
                    null,
                    null,
                    object : StepProgressListener {
                        override fun onStepProgress(step: StepProgressListener.Step) {
                            steps.add(step)
                        }
                    },
                    it
            )
        }

        // Check steps
        assertEquals(105, steps.size)

        for (i in 0..100) {
            assertTrue(steps[i] is StepProgressListener.Step.ComputingKey)
            assertEquals(i, (steps[i] as StepProgressListener.Step.ComputingKey).progress)
            assertEquals(100, (steps[i] as StepProgressListener.Step.ComputingKey).total)
        }

        assertTrue(steps[101] is StepProgressListener.Step.DownloadingKey)

        // 2 Keys to import, value will be 0%, 50%, 100%
        for (i in 102..104) {
            assertTrue(steps[i] is StepProgressListener.Step.ImportingKey)
            assertEquals(100, (steps[i] as StepProgressListener.Step.ImportingKey).total)
        }

        assertEquals(0, (steps[102] as StepProgressListener.Step.ImportingKey).progress)
        assertEquals(50, (steps[103] as StepProgressListener.Step.ImportingKey).progress)
        assertEquals(100, (steps[104] as StepProgressListener.Step.ImportingKey).progress)

        checkRestoreSuccess(testData, importRoomKeysResult.totalNumberOfKeys, importRoomKeysResult.successfullyNumberOfImportedKeys)

        testData.cleanUp(mTestHelper)
    }

    /**
     * - Do an e2e backup to the homeserver with a password
     * - Log Alice on a new device
     * - Try to restore the e2e backup with a wrong password
     * - It must fail
     */
    @Test
    fun restoreKeysBackupWithAWrongPasswordTest() {
        val password = "password"
        val wrongPassword = "passw0rd"

        val testData = createKeysBackupScenarioWithPassword(password)

        // - Try to restore the e2e backup with a wrong password
        val latch2 = CountDownLatch(1)
        var importRoomKeysResult: ImportRoomKeysResult? = null
        testData.aliceSession2.cryptoService().keysBackupService().restoreKeyBackupWithPassword(testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion!!,
                wrongPassword,
                null,
                null,
                null,
                object : TestMatrixCallback<ImportRoomKeysResult>(latch2, false) {
                    override fun onSuccess(data: ImportRoomKeysResult) {
                        importRoomKeysResult = data
                        super.onSuccess(data)
                    }
                }
        )
        mTestHelper.await(latch2)

        // onSuccess may not have been called
        assertNull(importRoomKeysResult)

        testData.cleanUp(mTestHelper)
    }

    /**
     * - Do an e2e backup to the homeserver with a password
     * - Log Alice on a new device
     * - Restore the e2e backup with the recovery key.
     * - Restore must be successful
     */
    @Test
    fun testUseRecoveryKeyToRestoreAPasswordBasedKeysBackup() {
        val password = "password"

        val testData = createKeysBackupScenarioWithPassword(password)

        // - Restore the e2e backup with the recovery key.
        val importRoomKeysResult = mTestHelper.doSync<ImportRoomKeysResult> {
            testData.aliceSession2.cryptoService().keysBackupService().restoreKeysWithRecoveryKey(testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion!!,
                    testData.prepareKeysBackupDataResult.megolmBackupCreationInfo.recoveryKey,
                    null,
                    null,
                    null,
                    it
            )
        }

        checkRestoreSuccess(testData, importRoomKeysResult.totalNumberOfKeys, importRoomKeysResult.successfullyNumberOfImportedKeys)

        testData.cleanUp(mTestHelper)
    }

    /**
     * - Do an e2e backup to the homeserver with a recovery key
     * - And log Alice on a new device
     * - Try to restore the e2e backup with a password
     * - It must fail
     */
    @Test
    fun testUsePasswordToRestoreARecoveryKeyBasedKeysBackup() {
        val testData = createKeysBackupScenarioWithPassword(null)

        // - Try to restore the e2e backup with a password
        val latch2 = CountDownLatch(1)
        var importRoomKeysResult: ImportRoomKeysResult? = null
        testData.aliceSession2.cryptoService().keysBackupService().restoreKeyBackupWithPassword(testData.aliceSession2.cryptoService().keysBackupService().keysBackupVersion!!,
                "password",
                null,
                null,
                null,
                object : TestMatrixCallback<ImportRoomKeysResult>(latch2, false) {
                    override fun onSuccess(data: ImportRoomKeysResult) {
                        importRoomKeysResult = data
                        super.onSuccess(data)
                    }
                }
        )
        mTestHelper.await(latch2)

        // onSuccess may not have been called
        assertNull(importRoomKeysResult)

        testData.cleanUp(mTestHelper)
    }

    /**
     * - Create a backup version
     * - Check the returned KeysVersionResult is trusted
     */
    @Test
    fun testIsKeysBackupTrusted() {
        // - Create a backup version
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages()

        val keysBackup = cryptoTestData.firstSession.cryptoService().keysBackupService()

        val stateObserver = StateObserver(keysBackup)

        // - Do an e2e backup to the homeserver
        prepareAndCreateKeysBackupData(keysBackup)

        // Get key backup version from the home server
        val keysVersionResult = mTestHelper.doSync<KeysVersionResult?> {
            keysBackup.getCurrentVersion(it)
        }

        // - Check the returned KeyBackupVersion is trusted
        val keysBackupVersionTrust = mTestHelper.doSync<KeysBackupVersionTrust> {
            keysBackup.getKeysBackupTrust(keysVersionResult!!, it)
        }

        assertNotNull(keysBackupVersionTrust)
        assertTrue(keysBackupVersionTrust.usable)
        assertEquals(1, keysBackupVersionTrust.signatures.size)

        val signature = keysBackupVersionTrust.signatures[0]
        assertTrue(signature.valid)
        assertNotNull(signature.device)
        assertEquals(cryptoTestData.firstSession.cryptoService().getMyDevice().deviceId, signature.deviceId)
        assertEquals(signature.device!!.deviceId, cryptoTestData.firstSession.sessionParams.credentials.deviceId)

        stateObserver.stopAndCheckStates(null)
        cryptoTestData.cleanUp(mTestHelper)
    }

    /**
     * Check backup starts automatically if there is an existing and compatible backup
     * version on the homeserver.
     * - Create a backup version
     * - Restart alice session
     * -> The new alice session must back up to the same version
     */
    @Test
    fun testCheckAndStartKeysBackupWhenRestartingAMatrixSession() {
        // - Create a backup version
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages()

        val keysBackup = cryptoTestData.firstSession.cryptoService().keysBackupService()

        val stateObserver = StateObserver(keysBackup)

        assertFalse(keysBackup.isEnabled)

        val keyBackupCreationInfo = prepareAndCreateKeysBackupData(keysBackup)

        assertTrue(keysBackup.isEnabled)

        // - Restart alice session
        // - Log Alice on a new device
        val aliceSession2 = mTestHelper.logIntoAccount(cryptoTestData.firstSession.myUserId, defaultSessionParamsWithInitialSync)

        cryptoTestData.cleanUp(mTestHelper)

        val keysBackup2 = aliceSession2.cryptoService().keysBackupService()

        val stateObserver2 = StateObserver(keysBackup2)

        // -> The new alice session must back up to the same version
        val latch = CountDownLatch(1)
        var count = 0
        keysBackup2.addListener(object : KeysBackupStateListener {
            override fun onStateChange(newState: KeysBackupState) {
                // Check the backup completes
                if (newState == KeysBackupState.ReadyToBackUp) {
                    count++

                    if (count == 2) {
                        // Remove itself from the list of listeners
                        keysBackup2.removeListener(this)

                        latch.countDown()
                    }
                }
            }
        })
        mTestHelper.await(latch)

        assertEquals(keyBackupCreationInfo.version, keysBackup2.currentBackupVersion)

        stateObserver.stopAndCheckStates(null)
        stateObserver2.stopAndCheckStates(null)
        mTestHelper.signOutAndClose(aliceSession2)
    }

    /**
     * Check WrongBackUpVersion state
     *
     * - Make alice back up her keys to her homeserver
     * - Create a new backup with fake data on the homeserver
     * - Make alice back up all her keys again
     * -> That must fail and her backup state must be WrongBackUpVersion
     */
    @Test
    fun testBackupWhenAnotherBackupWasCreated() {
        // - Create a backup version
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages()

        val keysBackup = cryptoTestData.firstSession.cryptoService().keysBackupService()

        val stateObserver = StateObserver(keysBackup)

        assertFalse(keysBackup.isEnabled)

        // Wait for keys backup to be finished
        val latch0 = CountDownLatch(1)
        var count = 0
        keysBackup.addListener(object : KeysBackupStateListener {
            override fun onStateChange(newState: KeysBackupState) {
                // Check the backup completes
                if (newState == KeysBackupState.ReadyToBackUp) {
                    count++

                    if (count == 2) {
                        // Remove itself from the list of listeners
                        keysBackup.removeListener(this)

                        latch0.countDown()
                    }
                }
            }
        })

        // - Make alice back up her keys to her homeserver
        prepareAndCreateKeysBackupData(keysBackup)

        assertTrue(keysBackup.isEnabled)

        mTestHelper.await(latch0)

        // - Create a new backup with fake data on the homeserver, directly using the rest client
        val megolmBackupCreationInfo = mCryptoTestHelper.createFakeMegolmBackupCreationInfo()
        mTestHelper.doSync<KeysVersion> {
            (keysBackup as DefaultKeysBackupService).createFakeKeysBackupVersion(megolmBackupCreationInfo, it)
        }

        // Reset the store backup status for keys
        (cryptoTestData.firstSession.cryptoService().keysBackupService() as DefaultKeysBackupService).store.resetBackupMarkers()

        // - Make alice back up all her keys again
        val latch2 = CountDownLatch(1)
        keysBackup.backupAllGroupSessions(null, TestMatrixCallback(latch2, false))
        mTestHelper.await(latch2)

        // -> That must fail and her backup state must be WrongBackUpVersion
        assertEquals(KeysBackupState.WrongBackUpVersion, keysBackup.state)
        assertFalse(keysBackup.isEnabled)

        stateObserver.stopAndCheckStates(null)
        cryptoTestData.cleanUp(mTestHelper)
    }

    /**
     * - Do an e2e backup to the homeserver
     * - Log Alice on a new device
     * - Post a message to have a new megolm session
     * - Try to backup all
     * -> It must fail. Backup state must be NotTrusted
     * - Validate the old device from the new one
     * -> Backup should automatically enable on the new device
     * -> It must use the same backup version
     * - Try to backup all again
     * -> It must success
     */
    @Test
    fun testBackupAfterVerifyingADevice() {
        // - Create a backup version
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages()

        val keysBackup = cryptoTestData.firstSession.cryptoService().keysBackupService()

        val stateObserver = StateObserver(keysBackup)

        // - Make alice back up her keys to her homeserver
        prepareAndCreateKeysBackupData(keysBackup)

        // Wait for keys backup to finish by asking again to backup keys.
        mTestHelper.doSync<Unit> {
            keysBackup.backupAllGroupSessions(null, it)
        }

        val oldDeviceId = cryptoTestData.firstSession.sessionParams.credentials.deviceId!!
        val oldKeyBackupVersion = keysBackup.currentBackupVersion
        val aliceUserId = cryptoTestData.firstSession.myUserId

        // Close first Alice session, else they will share the same Crypto store and the test fails.
        cryptoTestData.firstSession.close()

        // - Log Alice on a new device
        val aliceSession2 = mTestHelper.logIntoAccount(aliceUserId, defaultSessionParamsWithInitialSync)

        // - Post a message to have a new megolm session
        aliceSession2.cryptoService().setWarnOnUnknownDevices(false)

        val room2 = aliceSession2.getRoom(cryptoTestData.roomId)!!

        mTestHelper.sendTextMessage(room2, "New key", 1)

        // - Try to backup all in aliceSession2, it must fail
        val keysBackup2 = aliceSession2.cryptoService().keysBackupService()

        val stateObserver2 = StateObserver(keysBackup2)

        var isSuccessful = false
        val latch2 = CountDownLatch(1)
        keysBackup2.backupAllGroupSessions(
                null,
                object : TestMatrixCallback<Unit>(latch2, false) {
                    override fun onSuccess(data: Unit) {
                        isSuccessful = true
                        super.onSuccess(data)
                    }
                })
        mTestHelper.await(latch2)

        assertFalse(isSuccessful)

        // Backup state must be NotTrusted
        assertEquals(KeysBackupState.NotTrusted, keysBackup2.state)
        assertFalse(keysBackup2.isEnabled)

        // - Validate the old device from the new one
        aliceSession2.cryptoService().setDeviceVerification(DeviceTrustLevel(crossSigningVerified = false, locallyVerified = true), aliceSession2.myUserId, oldDeviceId)

        // -> Backup should automatically enable on the new device
        val latch4 = CountDownLatch(1)
        keysBackup2.addListener(object : KeysBackupStateListener {
            override fun onStateChange(newState: KeysBackupState) {
                // Check the backup completes
                if (keysBackup2.state == KeysBackupState.ReadyToBackUp) {
                    // Remove itself from the list of listeners
                    keysBackup2.removeListener(this)

                    latch4.countDown()
                }
            }
        })
        mTestHelper.await(latch4)

        // -> It must use the same backup version
        assertEquals(oldKeyBackupVersion, aliceSession2.cryptoService().keysBackupService().currentBackupVersion)

        mTestHelper.doSync<Unit> {
            aliceSession2.cryptoService().keysBackupService().backupAllGroupSessions(null, it)
        }

        // -> It must success
        assertTrue(aliceSession2.cryptoService().keysBackupService().isEnabled)

        stateObserver.stopAndCheckStates(null)
        stateObserver2.stopAndCheckStates(null)
        mTestHelper.signOutAndClose(aliceSession2)
        cryptoTestData.cleanUp(mTestHelper)
    }

    /**
     * - Do an e2e backup to the homeserver with a recovery key
     * - Delete the backup
     */
    @Test
    fun deleteKeysBackupTest() {
        // - Create a backup version
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages()

        val keysBackup = cryptoTestData.firstSession.cryptoService().keysBackupService()

        val stateObserver = StateObserver(keysBackup)

        assertFalse(keysBackup.isEnabled)

        val keyBackupCreationInfo = prepareAndCreateKeysBackupData(keysBackup)

        assertTrue(keysBackup.isEnabled)

        // Delete the backup
        mTestHelper.doSync<Unit> { keysBackup.deleteBackup(keyBackupCreationInfo.version, it) }

        // Backup is now disabled
        assertFalse(keysBackup.isEnabled)

        stateObserver.stopAndCheckStates(null)
        cryptoTestData.cleanUp(mTestHelper)
    }

    /* ==========================================================================================
     * Private
     * ========================================================================================== */

    /**
     * As KeysBackup is doing asynchronous call to update its internal state, this method help to wait for the
     * KeysBackup object to be in the specified state
     */
    private fun waitForKeysBackupToBeInState(session: Session, state: KeysBackupState) {
        // If already in the wanted state, return
        if (session.cryptoService().keysBackupService().state == state) {
            return
        }

        // Else observe state changes
        val latch = CountDownLatch(1)

        session.cryptoService().keysBackupService().addListener(object : KeysBackupStateListener {
            override fun onStateChange(newState: KeysBackupState) {
                if (newState == state) {
                    session.cryptoService().keysBackupService().removeListener(this)
                    latch.countDown()
                }
            }
        })

        mTestHelper.await(latch)
    }

    private data class PrepareKeysBackupDataResult(val megolmBackupCreationInfo: MegolmBackupCreationInfo,
                                                   val version: String)

    private fun prepareAndCreateKeysBackupData(keysBackup: KeysBackupService,
                                               password: String? = null): PrepareKeysBackupDataResult {
        val stateObserver = StateObserver(keysBackup)

        val megolmBackupCreationInfo = mTestHelper.doSync<MegolmBackupCreationInfo> {
            keysBackup.prepareKeysBackupVersion(password, null, it)
        }

        assertNotNull(megolmBackupCreationInfo)

        assertFalse(keysBackup.isEnabled)

        // Create the version
        val keysVersion = mTestHelper.doSync<KeysVersion> {
            keysBackup.createKeysBackupVersion(megolmBackupCreationInfo, it)
        }

        assertNotNull(keysVersion.version)

        // Backup must be enable now
        assertTrue(keysBackup.isEnabled)

        stateObserver.stopAndCheckStates(null)
        return PrepareKeysBackupDataResult(megolmBackupCreationInfo, keysVersion.version!!)
    }

    private fun assertKeysEquals(keys1: MegolmSessionData?, keys2: MegolmSessionData?) {
        assertNotNull(keys1)
        assertNotNull(keys2)

        assertEquals(keys1?.algorithm, keys2?.algorithm)
        assertEquals(keys1?.roomId, keys2?.roomId)
        // No need to compare the shortcut
        // assertEquals(keys1?.sender_claimed_ed25519_key, keys2?.sender_claimed_ed25519_key)
        assertEquals(keys1?.senderKey, keys2?.senderKey)
        assertEquals(keys1?.sessionId, keys2?.sessionId)
        assertEquals(keys1?.sessionKey, keys2?.sessionKey)

        assertListEquals(keys1?.forwardingCurve25519KeyChain, keys2?.forwardingCurve25519KeyChain)
        assertDictEquals(keys1?.senderClaimedKeys, keys2?.senderClaimedKeys)
    }

    /**
     * Data class to store result of [createKeysBackupScenarioWithPassword]
     */
    private data class KeysBackupScenarioData(val cryptoTestData: CryptoTestData,
                                              val aliceKeys: List<OlmInboundGroupSessionWrapper>,
                                              val prepareKeysBackupDataResult: PrepareKeysBackupDataResult,
                                              val aliceSession2: Session) {
        fun cleanUp(testHelper: CommonTestHelper) {
            cryptoTestData.cleanUp(testHelper)
            testHelper.signOutAndClose(aliceSession2)
        }
    }

    /**
     * Common initial condition
     * - Do an e2e backup to the homeserver
     * - Log Alice on a new device, and wait for its keysBackup object to be ready (in state NotTrusted)
     *
     * @param password optional password
     */
    private fun createKeysBackupScenarioWithPassword(password: String?): KeysBackupScenarioData {
        val cryptoTestData = mCryptoTestHelper.doE2ETestWithAliceAndBobInARoomWithEncryptedMessages()

        val cryptoStore = (cryptoTestData.firstSession.cryptoService().keysBackupService() as DefaultKeysBackupService).store
        val keysBackup = cryptoTestData.firstSession.cryptoService().keysBackupService()

        val stateObserver = StateObserver(keysBackup)

        val aliceKeys = cryptoStore.inboundGroupSessionsToBackup(100)

        // - Do an e2e backup to the homeserver
        val prepareKeysBackupDataResult = prepareAndCreateKeysBackupData(keysBackup, password)

        var lastProgress = 0
        var lastTotal = 0
        mTestHelper.doSync<Unit> {
            keysBackup.backupAllGroupSessions(object : ProgressListener {
                override fun onProgress(progress: Int, total: Int) {
                    lastProgress = progress
                    lastTotal = total
                }
            }, it)
        }

        assertEquals(2, lastProgress)
        assertEquals(2, lastTotal)

        val aliceUserId = cryptoTestData.firstSession.myUserId

        // - Log Alice on a new device
        val aliceSession2 = mTestHelper.logIntoAccount(aliceUserId, defaultSessionParamsWithInitialSync)

        // Test check: aliceSession2 has no keys at login
        assertEquals(0, aliceSession2.cryptoService().inboundGroupSessionsCount(false))

        // Wait for backup state to be NotTrusted
        waitForKeysBackupToBeInState(aliceSession2, KeysBackupState.NotTrusted)

        stateObserver.stopAndCheckStates(null)

        return KeysBackupScenarioData(cryptoTestData,
                aliceKeys,
                prepareKeysBackupDataResult,
                aliceSession2)
    }

    /**
     * Common restore success check after [createKeysBackupScenarioWithPassword]:
     * - Imported keys number must be correct
     * - The new device must have the same count of megolm keys
     * - Alice must have the same keys on both devices
     */
    private fun checkRestoreSuccess(testData: KeysBackupScenarioData,
                                    total: Int,
                                    imported: Int) {
        // - Imported keys number must be correct
        assertEquals(testData.aliceKeys.size, total)
        assertEquals(total, imported)

        // - The new device must have the same count of megolm keys
        assertEquals(testData.aliceKeys.size, testData.aliceSession2.cryptoService().inboundGroupSessionsCount(false))

        // - Alice must have the same keys on both devices
        for (aliceKey1 in testData.aliceKeys) {
            val aliceKey2 = (testData.aliceSession2.cryptoService().keysBackupService() as DefaultKeysBackupService).store
                    .getInboundGroupSession(aliceKey1.olmInboundGroupSession!!.sessionIdentifier(), aliceKey1.senderKey!!)
            assertNotNull(aliceKey2)
            assertKeysEquals(aliceKey1.exportKeys(), aliceKey2!!.exportKeys())
        }
    }
}
