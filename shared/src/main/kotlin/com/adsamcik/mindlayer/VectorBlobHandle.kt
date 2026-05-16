package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

@Parcelize
data class VectorBlobHandle(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    /** Status mirrors DeferredResult.{STILL_RUNNING, READY, CANCELLED, FAILED, EXPIRED, NOT_FOUND_OR_NOT_OWNED}. */
    val status: Int,
    /** Populated only when status == READY. Same layout as EmbeddingBatchTransfer.pfd. */
    val transfer: EmbeddingBatchTransfer? = null,
    val errorCodeInt: Int = 0,
    val errorCodeName: String? = null,
) : Parcelable {
    companion object { const val CURRENT_SCHEMA_VERSION: Int = 1 }
}
