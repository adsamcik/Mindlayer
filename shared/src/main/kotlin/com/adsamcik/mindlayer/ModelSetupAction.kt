package com.adsamcik.mindlayer

import android.app.PendingIntent
import android.os.Parcelable
import kotlinx.parcelize.Parcelize

/** Mindlayer-owned action that opens setup for a model family. */
@Parcelize
data class ModelSetupAction(
    val schemaVersion: Int = CURRENT_SCHEMA_VERSION,
    val family: String,
    val setupIntent: PendingIntent,
    val extensionsJson: String? = null,
) : Parcelable {
    companion object {
        const val CURRENT_SCHEMA_VERSION: Int = 1
    }
}
