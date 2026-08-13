package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/**
 * Current readiness of one on-device model family.
 *
 * This is intentionally a small, coarse contract for task-aware clients. The
 * Mindlayer app remains responsible for model delivery details and recovery;
 * callers only decide whether to proceed, wait, or open the setup action.
 *
 * This Parcelable is wire-frozen. Add future data through [extensionsJson] or
 * introduce a new type and AIDL method.
 */
@Parcelize
data class ModelReadinessItem(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val family: String,
    val state: Int,
    val reasonCode: String? = null,
    val extensionsJson: String? = null,
) : Parcelable {
    val isReady: Boolean get() = state == STATE_READY

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1

        const val FAMILY_CHAT: String = "CHAT"
        const val FAMILY_OCR: String = "OCR"

        const val STATE_UNKNOWN: Int = 0
        const val STATE_READY: Int = 1
        const val STATE_SETUP_REQUIRED: Int = 2
        const val STATE_IN_PROGRESS: Int = 3
        const val STATE_FAILED: Int = 4
        const val STATE_UNSUPPORTED: Int = 5

        const val REASON_MODEL_MISSING: String = "MODEL_MISSING"
        const val REASON_LOW_MEMORY: String = "LOW_MEMORY"
        const val REASON_INTEGRITY_MISMATCH: String = "INTEGRITY_MISMATCH"
        const val REASON_BACKEND_UNAVAILABLE: String = "BACKEND_UNAVAILABLE"
        const val REASON_NATIVE_ERROR: String = "NATIVE_ERROR"
        const val REASON_OLD_SERVICE: String = "OLD_SERVICE"
    }
}
