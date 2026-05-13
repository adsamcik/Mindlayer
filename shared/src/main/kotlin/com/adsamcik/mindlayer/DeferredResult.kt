package com.adsamcik.mindlayer

import android.os.Bundle
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class DeferredResult(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val status: Int,
    val text: String? = null,
    val metrics: Bundle? = null,
    val errorCodeInt: Int = 0,
    val errorCodeName: String? = null,
) : Parcelable {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
        const val READY: Int = 0
        const val STILL_RUNNING: Int = 1
        const val NOT_FOUND_OR_NOT_OWNED: Int = 2
        const val EXPIRED: Int = 3
        const val FAILED: Int = 4
        const val CANCELLED: Int = 5
    }
}
