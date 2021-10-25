/*
 * Copyright 2020 New Vector Ltd - All Rights Reserved
 * Unauthorized copying of this file, via any medium is strictly prohibited
 * Proprietary and confidential
 */

package im.vector.matrix.android.sdk.internal.session.contentscanning.db

import io.realm.RealmObject
import io.realm.annotations.Index
import org.matrix.android.sdk.api.extensions.tryOrNull
import org.matrix.android.sdk.api.session.contentscanning.ScanState
import org.matrix.android.sdk.api.session.contentscanning.ScanStatusInfo

internal open class ContentScanResultEntity(
        @Index
        var mediaUrl: String? = null,
        var scanStatusString: String? = null,
        var humanReadableMessage: String? = null,
        var scanDateTimestamp: Long? = null,
        var scannerUrl: String? = null
) : RealmObject() {

    var scanResult: ScanState
        get() {
            return scanStatusString
                    ?.let {
                        tryOrNull { ScanState.valueOf(it) }
                    }
                    ?: ScanState.UNKNOWN
        }
        set(result) {
            scanStatusString = result.name
        }

    fun toModel() : ScanStatusInfo {
        return ScanStatusInfo(
                state = this.scanResult,
                humanReadableMessage = humanReadableMessage,
                scanDateTimestamp = scanDateTimestamp
        )
    }

    companion object
}
