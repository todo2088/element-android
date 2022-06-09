/*
 * Copyright (c) 2022 The Matrix.org Foundation C.I.C.
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

package org.matrix.android.sdk.api.session.room.location

import androidx.lifecycle.LiveData
import org.matrix.android.sdk.api.session.room.model.livelocation.LiveLocationShareAggregatedSummary

/**
 * Manage all location sharing related features.
 */
interface LocationSharingService {
    /**
     * Starts sharing live location in the room.
     * @param timeoutMillis timeout of the live in milliseconds
     * @return the id of the created beacon info event
     */
    suspend fun startLiveLocationShare(timeoutMillis: Long): String

    /**
     * Stops sharing live location in the room.
     */
    suspend fun stopLiveLocationShare()

    /**
     * Returns a LiveData on the list of current running live location shares.
     */
    fun getRunningLiveLocationShareSummaries(): LiveData<List<LiveLocationShareAggregatedSummary>>
}
