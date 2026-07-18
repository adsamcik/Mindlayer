package com.adsamcik.mindlayer.service.modeldelivery

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.flow.StateFlow
import java.io.Closeable

enum class AssetPackPhase {
    UNKNOWN,
    NOT_INSTALLED,
    PENDING,
    DOWNLOADING,
    TRANSFERRING,
    WAITING_FOR_WIFI,
    REQUIRES_USER_CONFIRMATION,
    COMPLETED,
    FAILED,
    CANCELED,
}

data class AssetPackSnapshot(
    val packName: String,
    val phase: AssetPackPhase,
    val bytesDownloaded: Long = 0L,
    val totalBytesToDownload: Long = 0L,
    val errorCode: Int = 0,
    val assetsPath: String? = null,
)

/**
 * App-owned façade over Play Asset Delivery. It is deliberately free of Play
 * Core types so unit tests and dashboard state do not depend on Play services.
 */
interface AssetPackClient : Closeable {
    val states: StateFlow<Map<String, AssetPackSnapshot>>

    suspend fun refresh(packNames: Collection<String>)

    suspend fun fetch(packNames: Collection<String>)

    suspend fun cancel(packNames: Collection<String>)

    suspend fun removePack(packName: String)

    /**
     * Launches a pending Play confirmation request, if Play supplied one.
     * Returns false when no confirmation is currently pending.
     */
    fun showConfirmationDialog(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean
}
