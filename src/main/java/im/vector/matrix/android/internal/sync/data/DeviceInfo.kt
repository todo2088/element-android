/*
 * Copyright 2014 OpenMarket Ltd
 * Copyright 2017 Vector Creations Ltd
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
package im.vector.matrix.android.internal.sync.data

import com.squareup.moshi.JsonClass


/**
 * This class describes the device information
 */
@JsonClass(generateAdapter = true)
data class DeviceInfo(
        /**
         * The owner user id
         */
        var user_id: String? = null,

        /**
         * The device id
         */
        var device_id: String? = null,

        /**
         * The device display name
         */
        var display_name: String? = null,

        /**
         * The last time this device has been seen.
         */
        var last_seen_ts: Long = 0,

        /**
         * The last ip address
         */
        var last_seen_ip: String? = null

)
