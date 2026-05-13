package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class DeferredHandle(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val requestId: String,
    val expiresAtMs: Long,
) : Parcelable {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
