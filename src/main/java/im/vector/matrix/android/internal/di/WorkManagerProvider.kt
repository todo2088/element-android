/*
 * Copyright 2020 New Vector Ltd
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

package im.vector.matrix.android.internal.di

import android.content.Context
import androidx.work.*
import javax.inject.Inject

internal class WorkManagerProvider @Inject constructor(
        context: Context,
        @SessionId private val sessionId: String
) {
    private val tag = MATRIX_SDK_TAG_PREFIX + sessionId

    val workManager = WorkManager.getInstance(context)

    /**
     * Create a OneTimeWorkRequestBuilder, with the Matrix SDK tag
     */
    inline fun <reified W : ListenableWorker> matrixOneTimeWorkRequestBuilder() =
            OneTimeWorkRequestBuilder<W>()
                    .addTag(tag)

    /**
     * Cancel all works instantiated by the Matrix SDK for the current session, and not those from the SDK client, or for other sessions
     */
    fun cancelAllWorks() {
        workManager.let {
            it.cancelAllWorkByTag(tag)
            it.pruneWork()
        }
    }

    companion object {
        private const val MATRIX_SDK_TAG_PREFIX = "MatrixSDK-"

        /**
         * Default constraints: connected network
         */
        val workConstraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
    }
}
