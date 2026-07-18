package com.adsamcik.mindlayer.service.modeldelivery

import android.content.Context
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.google.android.gms.tasks.Task
import com.google.android.play.core.assetpacks.AssetPackManager
import com.google.android.play.core.assetpacks.AssetPackManagerFactory
import com.google.android.play.core.assetpacks.AssetPackState
import com.google.android.play.core.assetpacks.AssetPackStateUpdateListener
import com.google.android.play.core.assetpacks.model.AssetPackStatus
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * The sole production boundary that imports Google Play Asset Delivery types.
 * Play owns network transfer; Mindlayer only reads the verified local pack data.
 */
class PlayAssetPackClient internal constructor(
    private val manager: AssetPackManager,
) : AssetPackClient {
    constructor(context: Context) : this(AssetPackManagerFactory.getInstance(context.applicationContext))

    private val closed = AtomicBoolean(false)
    private val _states = MutableStateFlow<Map<String, AssetPackSnapshot>>(emptyMap())
    override val states: StateFlow<Map<String, AssetPackSnapshot>> = _states.asStateFlow()

    private val listener = AssetPackStateUpdateListener { state ->
        update(state.toSnapshot())
    }

    init {
        manager.registerListener(listener)
    }

    override suspend fun refresh(packNames: Collection<String>) {
        val states = manager.getPackStates(packNames.toList()).await().packStates()
        states.values.forEach { update(it.toSnapshot()) }
    }

    override suspend fun fetch(packNames: Collection<String>) {
        val states = manager.fetch(packNames.toList()).await().packStates()
        states.values.forEach { update(it.toSnapshot()) }
    }

    override suspend fun cancel(packNames: Collection<String>) {
        val states = manager.cancel(packNames.toList()).packStates()
        states.values.forEach { update(it.toSnapshot()) }
    }

    override suspend fun removePack(packName: String) {
        manager.removePack(packName).await()
        update(AssetPackSnapshot(packName = packName, phase = AssetPackPhase.NOT_INSTALLED))
    }

    override fun showConfirmationDialog(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean =
        manager.showConfirmationDialog(launcher)

    override fun close() {
        if (closed.compareAndSet(false, true)) {
            manager.unregisterListener(listener)
        }
    }

    internal fun update(snapshot: AssetPackSnapshot) {
        _states.update { current -> current + (snapshot.packName to snapshot) }
    }

    private fun AssetPackState.toSnapshot(): AssetPackSnapshot {
        val phase = when (status()) {
            AssetPackStatus.NOT_INSTALLED -> AssetPackPhase.NOT_INSTALLED
            AssetPackStatus.PENDING -> AssetPackPhase.PENDING
            AssetPackStatus.DOWNLOADING -> AssetPackPhase.DOWNLOADING
            AssetPackStatus.TRANSFERRING -> AssetPackPhase.TRANSFERRING
            AssetPackStatus.WAITING_FOR_WIFI -> AssetPackPhase.WAITING_FOR_WIFI
            AssetPackStatus.REQUIRES_USER_CONFIRMATION -> AssetPackPhase.REQUIRES_USER_CONFIRMATION
            AssetPackStatus.COMPLETED -> AssetPackPhase.COMPLETED
            AssetPackStatus.FAILED -> AssetPackPhase.FAILED
            AssetPackStatus.CANCELED -> AssetPackPhase.CANCELED
            else -> AssetPackPhase.UNKNOWN
        }
        return AssetPackSnapshot(
            packName = name(),
            phase = phase,
            bytesDownloaded = bytesDownloaded(),
            totalBytesToDownload = totalBytesToDownload(),
            errorCode = errorCode(),
            assetsPath = manager.getPackLocation(name())?.assetsPath(),
        )
    }
}

private suspend fun <T> Task<T>.await(): T = suspendCancellableCoroutine { continuation ->
    addOnSuccessListener { result ->
        if (continuation.isActive) continuation.resume(result)
    }
    addOnFailureListener { error ->
        if (continuation.isActive) continuation.resumeWithException(error)
    }
    addOnCanceledListener {
        continuation.cancel()
    }
}
