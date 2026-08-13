package com.adsamcik.mindlayer

import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** A point-in-time readiness snapshot returned by Mindlayer. */
@Parcelize
data class ModelReadinessSnapshot(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val capturedAtEpochMs: Long,
    val items: List<ModelReadinessItem>,
    val extensionsJson: String? = null,
) : Parcelable {
    fun item(family: String): ModelReadinessItem? = items.firstOrNull { it.family == family }

    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1

        fun unsupported(capturedAtEpochMs: Long = System.currentTimeMillis()): ModelReadinessSnapshot =
            ModelReadinessSnapshot(
                capturedAtEpochMs = capturedAtEpochMs,
                items = listOf(
                    ModelReadinessItem(
                        family = ModelReadinessItem.FAMILY_CHAT,
                        state = ModelReadinessItem.STATE_UNSUPPORTED,
                        reasonCode = ModelReadinessItem.REASON_OLD_SERVICE,
                    ),
                    ModelReadinessItem(
                        family = ModelReadinessItem.FAMILY_OCR,
                        state = ModelReadinessItem.STATE_UNSUPPORTED,
                        reasonCode = ModelReadinessItem.REASON_OLD_SERVICE,
                    ),
                ),
            )
    }
}
