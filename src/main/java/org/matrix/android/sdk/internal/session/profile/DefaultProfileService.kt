/*
 * Copyright 2020 New Vector Ltd
 * Copyright 2020 The Matrix.org Foundation C.I.C.
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
 *
 */

package org.matrix.android.sdk.internal.session.profile

import android.net.Uri
import androidx.lifecycle.LiveData
import com.zhuinden.monarchy.Monarchy
import io.realm.kotlin.where
import org.matrix.android.sdk.api.MatrixCallback
import org.matrix.android.sdk.api.session.identity.ThreePid
import org.matrix.android.sdk.api.session.profile.ProfileService
import org.matrix.android.sdk.api.util.Cancelable
import org.matrix.android.sdk.api.util.JsonDict
import org.matrix.android.sdk.api.util.Optional
import org.matrix.android.sdk.internal.database.model.PendingThreePidEntity
import org.matrix.android.sdk.internal.database.model.UserThreePidEntity
import org.matrix.android.sdk.internal.di.SessionDatabase
import org.matrix.android.sdk.internal.session.content.FileUploader
import org.matrix.android.sdk.internal.task.TaskExecutor
import org.matrix.android.sdk.internal.task.configureWith
import org.matrix.android.sdk.internal.task.launchToCallback
import org.matrix.android.sdk.internal.util.MatrixCoroutineDispatchers
import javax.inject.Inject

internal class DefaultProfileService @Inject constructor(private val taskExecutor: TaskExecutor,
                                                         @SessionDatabase private val monarchy: Monarchy,
                                                         private val coroutineDispatchers: MatrixCoroutineDispatchers,
                                                         private val refreshUserThreePidsTask: RefreshUserThreePidsTask,
                                                         private val getProfileInfoTask: GetProfileInfoTask,
                                                         private val setDisplayNameTask: SetDisplayNameTask,
                                                         private val setAvatarUrlTask: SetAvatarUrlTask,
                                                         private val addThreePidTask: AddThreePidTask,
                                                         private val validateSmsCodeTask: ValidateSmsCodeTask,
                                                         private val finalizeAddingThreePidTask: FinalizeAddingThreePidTask,
                                                         private val deleteThreePidTask: DeleteThreePidTask,
                                                         private val pendingThreePidMapper: PendingThreePidMapper,
                                                         private val fileUploader: FileUploader) : ProfileService {

    override fun getDisplayName(userId: String, matrixCallback: MatrixCallback<Optional<String>>): Cancelable {
        val params = GetProfileInfoTask.Params(userId)
        return getProfileInfoTask
                .configureWith(params) {
                    this.callback = object : MatrixCallback<JsonDict> {
                        override fun onSuccess(data: JsonDict) {
                            val displayName = data[ProfileService.DISPLAY_NAME_KEY] as? String
                            matrixCallback.onSuccess(Optional.from(displayName))
                        }

                        override fun onFailure(failure: Throwable) {
                            matrixCallback.onFailure(failure)
                        }
                    }
                }
                .executeBy(taskExecutor)
    }

    override fun setDisplayName(userId: String, newDisplayName: String, matrixCallback: MatrixCallback<Unit>): Cancelable {
        return setDisplayNameTask
                .configureWith(SetDisplayNameTask.Params(userId = userId, newDisplayName = newDisplayName)) {
                    callback = matrixCallback
                }
                .executeBy(taskExecutor)
    }

    override fun updateAvatar(userId: String, newAvatarUri: Uri, fileName: String, matrixCallback: MatrixCallback<Unit>): Cancelable {
        return taskExecutor.executorScope.launchToCallback(coroutineDispatchers.main, matrixCallback) {
            val response = fileUploader.uploadFromUri(newAvatarUri, fileName, "image/jpeg")
            setAvatarUrlTask.execute(SetAvatarUrlTask.Params(userId = userId, newAvatarUrl = response.contentUri))
        }
    }

    override fun getAvatarUrl(userId: String, matrixCallback: MatrixCallback<Optional<String>>): Cancelable {
        val params = GetProfileInfoTask.Params(userId)
        return getProfileInfoTask
                .configureWith(params) {
                    this.callback = object : MatrixCallback<JsonDict> {
                        override fun onSuccess(data: JsonDict) {
                            val avatarUrl = data[ProfileService.AVATAR_URL_KEY] as? String
                            matrixCallback.onSuccess(Optional.from(avatarUrl))
                        }

                        override fun onFailure(failure: Throwable) {
                            matrixCallback.onFailure(failure)
                        }
                    }
                }
                .executeBy(taskExecutor)
    }

    override fun getProfile(userId: String, matrixCallback: MatrixCallback<JsonDict>): Cancelable {
        val params = GetProfileInfoTask.Params(userId)
        return getProfileInfoTask
                .configureWith(params) {
                    this.callback = matrixCallback
                }
                .executeBy(taskExecutor)
    }

    override fun getThreePids(): List<ThreePid> {
        return monarchy.fetchAllMappedSync(
                { it.where<UserThreePidEntity>() },
                { it.asDomain() }
        )
    }

    override fun getThreePidsLive(refreshData: Boolean): LiveData<List<ThreePid>> {
        if (refreshData) {
            // Force a refresh of the values
            refreshThreePids()
        }

        return monarchy.findAllMappedWithChanges(
                { it.where<UserThreePidEntity>() },
                { it.asDomain() }
        )
    }

    private fun refreshThreePids() {
        refreshUserThreePidsTask
                .configureWith()
                .executeBy(taskExecutor)
    }

    override fun getPendingThreePids(): List<ThreePid> {
        return monarchy.fetchAllMappedSync(
                { it.where<PendingThreePidEntity>() },
                { pendingThreePidMapper.map(it).threePid }
        )
    }

    override fun getPendingThreePidsLive(): LiveData<List<ThreePid>> {
        return monarchy.findAllMappedWithChanges(
                { it.where<PendingThreePidEntity>() },
                { pendingThreePidMapper.map(it).threePid }
        )
    }

    override fun addThreePid(threePid: ThreePid, matrixCallback: MatrixCallback<Unit>): Cancelable {
        return addThreePidTask
                .configureWith(AddThreePidTask.Params(threePid)) {
                    callback = matrixCallback
                }
                .executeBy(taskExecutor)
    }

    override fun submitSmsCode(threePid: ThreePid.Msisdn, code: String, matrixCallback: MatrixCallback<Unit>): Cancelable {
        return validateSmsCodeTask
                .configureWith(ValidateSmsCodeTask.Params(threePid, code)) {
                    callback = matrixCallback
                }
                .executeBy(taskExecutor)
    }

    override fun finalizeAddingThreePid(threePid: ThreePid,
                                        uiaSession: String?,
                                        accountPassword: String?,
                                        matrixCallback: MatrixCallback<Unit>): Cancelable {
        return finalizeAddingThreePidTask
                .configureWith(FinalizeAddingThreePidTask.Params(
                        threePid = threePid,
                        session = uiaSession,
                        accountPassword = accountPassword,
                        userWantsToCancel = false
                )) {
                    callback = alsoRefresh(matrixCallback)
                }
                .executeBy(taskExecutor)
    }

    override fun cancelAddingThreePid(threePid: ThreePid, matrixCallback: MatrixCallback<Unit>): Cancelable {
        return finalizeAddingThreePidTask
                .configureWith(FinalizeAddingThreePidTask.Params(
                        threePid = threePid,
                        session = null,
                        accountPassword = null,
                        userWantsToCancel = true
                )) {
                    callback = alsoRefresh(matrixCallback)
                }
                .executeBy(taskExecutor)
    }

    /**
     * Wrap the callback to fetch 3Pids from the server in case of success
     */
    private fun alsoRefresh(callback: MatrixCallback<Unit>): MatrixCallback<Unit> {
        return object : MatrixCallback<Unit> {
            override fun onFailure(failure: Throwable) {
                callback.onFailure(failure)
            }

            override fun onSuccess(data: Unit) {
                refreshThreePids()
                callback.onSuccess(data)
            }
        }
    }

    override fun deleteThreePid(threePid: ThreePid, matrixCallback: MatrixCallback<Unit>): Cancelable {
        return deleteThreePidTask
                .configureWith(DeleteThreePidTask.Params(threePid)) {
                    callback = alsoRefresh(matrixCallback)
                }
                .executeBy(taskExecutor)
    }
}

private fun UserThreePidEntity.asDomain(): ThreePid {
    return when (medium) {
        ThirdPartyIdentifier.MEDIUM_EMAIL  -> ThreePid.Email(address)
        ThirdPartyIdentifier.MEDIUM_MSISDN -> ThreePid.Msisdn(address)
        else                               -> error("Invalid medium type")
    }
}
