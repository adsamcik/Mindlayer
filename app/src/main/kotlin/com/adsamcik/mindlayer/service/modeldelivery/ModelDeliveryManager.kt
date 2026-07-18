package com.adsamcik.mindlayer.service.modeldelivery

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.StatFs
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.adsamcik.mindlayer.service.BuildConfig
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.coroutines.ContinuationInterceptor

sealed interface ModelDeliveryState {
    data object Checking : ModelDeliveryState
    data object NotInstalled : ModelDeliveryState
    data object Pending : ModelDeliveryState
    data class Downloading(val downloadedBytes: Long, val totalBytes: Long) : ModelDeliveryState {
        val progressPercent: Int = if (totalBytes > 0) {
            ((downloadedBytes * 100L) / totalBytes).toInt().coerceIn(0, 100)
        } else {
            0
        }
    }
    data object Transferring : ModelDeliveryState
    data object WaitingForWifi : ModelDeliveryState
    data object RequiresConfirmation : ModelDeliveryState
    data object Provisioning : ModelDeliveryState
    data object Installed : ModelDeliveryState
    data object Activating : ModelDeliveryState
    data object InstalledWithActivationError : ModelDeliveryState
    data object Removing : ModelDeliveryState
    data object Quiescing : ModelDeliveryState
    data class RemovalFailed(val message: String) : ModelDeliveryState
    data class Failed(val message: String) : ModelDeliveryState
    data object Unsupported : ModelDeliveryState
}

/**
 * Owns the dashboard's on-demand delivery lifecycle. It is process-local:
 * closing it merely unregisters the listener; Play continues a requested
 * download independently and a later [refresh] reconnects to its state.
 */
class ModelDeliveryManager internal constructor(
    context: Context,
    private val client: AssetPackClient = PlayAssetPackClient(context),
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
    private val materializer: ModelArtifactMaterializer = VerifiedModelMaterializer(
        filesDir = context.filesDir,
        releaseBuild = (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) == 0,
        pinnedSha256 = ::pinnedSha256,
    ),
    private val runtimeControl: ModelRuntimeControl = BroadcastModelRuntimeControl(context),
    private val availableBytes: () -> Long = {
        StatFs(context.filesDir.absolutePath).availableBytes
    },
    private val blockingDispatcher: CoroutineDispatcher =
        scope.coroutineContext[ContinuationInterceptor] as? CoroutineDispatcher ?: Dispatchers.IO,
    private val removalIntentRecorder: ((ModelFamily) -> Unit)? = null,
    private val beforeInitialCallbackHandoff: suspend () -> Unit = {},
    private val beforeInitialCatchUp: suspend () -> Unit = {},
) : AutoCloseable {
    private val appContext = context.applicationContext
    private val intentStore = ModelDeliveryIntentStore(appContext.filesDir)
    private val _states = MutableStateFlow(
        ModelFamily.entries.associateWith<ModelFamily, ModelDeliveryState> { ModelDeliveryState.Checking },
    )
    val states: StateFlow<Map<ModelFamily, ModelDeliveryState>> = _states.asStateFlow()
    private val stateCollection: Job
    private val familyMutexes = ModelFamily.entries.associateWith { Mutex() }
    private val callbackLock = Any()
    private var stateCallbacksEnabled = false
    private var lastObservedPackStates = client.states.value
    private val dirtyFamilies = mutableSetOf<ModelFamily>()
    private val operationLock = Any()
    private var refreshJob: Job? = null
    private val activationRecoveryJobs = mutableMapOf<ModelFamily, Job>()
    private val activationAttempts = mutableMapOf<ModelFamily, Deferred<RuntimeActivationResult>>()

    init {
        stateCollection = scope.launch {
            client.states.collect { snapshots ->
                val shouldReconcile = synchronized(callbackLock) {
                    val changedFamilies = changedFamilies(lastObservedPackStates, snapshots)
                    lastObservedPackStates = snapshots
                    if (stateCallbacksEnabled) {
                        changedFamilies.isNotEmpty()
                    } else {
                        dirtyFamilies += changedFamilies
                        false
                    }
                }
                if (shouldReconcile) {
                    ModelFamily.entries.forEach { family -> reconcile(family, provisionIfReady = true) }
                }
            }
        }
    }

    fun start() {
        refresh()
    }

    fun refresh() {
        synchronized(operationLock) {
            if (refreshJob?.isActive == true) return
            val job = scope.launch(start = CoroutineStart.LAZY) {
                performRefresh()
            }
            refreshJob = job
            job.invokeOnCompletion {
                synchronized(operationLock) {
                    if (refreshJob === job) refreshJob = null
                }
            }
            job.start()
        }
    }

    private suspend fun performRefresh() {
        val ocrInstalledAtRefreshStart = familyMutex(ModelFamily.OCR).withLock {
            materializer.isMarkedInstalled(ModelFamily.OCR)
        }
        try {
            reconcileIntentMarkers()
            resumePendingRemovals()
            var ocrBytesRemainInstalled = false
            ModelFamily.entries.forEach { family ->
                val installed = forceValidateInstalledBytes(family)
                if (family == ModelFamily.OCR) {
                    ocrBytesRemainInstalled = installed
                }
            }
            val refreshed = runCatching {
                client.refresh(ModelFamily.entries.flatMap { ModelDeliveryCatalog.family(it).packNames })
            }.onFailure(::recordFailure).isSuccess
            if (refreshed) {
                ModelFamily.entries.forEach { family -> reconcile(family, provisionIfReady = true) }
            }
            if (ocrInstalledAtRefreshStart && ocrBytesRemainInstalled) {
                familyMutex(ModelFamily.OCR).withLock {
                    if (materializer.isMarkedInstalled(ModelFamily.OCR)) {
                        activateInstalledLocked(ModelFamily.OCR)
                    }
                }
            }
        } finally {
            try {
                if (!callbacksEnabled()) {
                    beforeInitialCallbackHandoff()
                }
            } finally {
                enableCallbacksAndReconcileDirtyFamilies()
            }
        }
    }

    fun download(family: ModelFamily) {
        scope.launch {
            familyMutex(family).withLock {
                val required = ModelDeliveryCatalog.family(family).minimumFreeBytes
                val available = runCatching(availableBytes).getOrDefault(0L)
                if (available < required) {
                    updateState(
                        family,
                        ModelDeliveryState.Failed(
                            "At least ${required / 1_000_000_000} GB free space is required",
                        ),
                    )
                    return@withLock
                }
                val intentRecorded = runCatching {
                    intentStore.recordDownload(family)
                }.onFailure { error ->
                    MindlayerLog.w(
                        TAG,
                        "Could not persist model download intent: ${error.safeLabel()}",
                        throwable = null,
                    )
                    updateState(
                        family,
                        ModelDeliveryState.Failed("Model download could not be started. Retry."),
                    )
                }.isSuccess
                if (!intentRecorded) return@withLock
                updateState(family, ModelDeliveryState.Pending)
                val fetched = runCatching {
                    client.fetch(ModelDeliveryCatalog.family(family).packNames)
                }.onFailure { recordFailure(it, family) }.isSuccess
                if (!fetched) return@withLock
                reconcileLocked(family, provisionIfReady = true)
            }
        }
    }

    suspend fun remove(family: ModelFamily) {
        withContext(blockingDispatcher + NonCancellable) {
            familyMutex(family).withLock {
                updateState(family, ModelDeliveryState.Removing)
                if (!persistRemovalIntent(family)) {
                    return@withLock
                }
                scope.launch {
                    familyMutex(family).withLock {
                        removeLocked(family)
                    }
                }
            }
        }
    }

    suspend fun retryActivation(family: ModelFamily) {
        if (family == ModelFamily.OCR) {
            activationRecoveryJob(family).join()
        }
    }

    fun showConfirmationDialog(launcher: ActivityResultLauncher<IntentSenderRequest>): Boolean =
        client.showConfirmationDialog(launcher)

    override fun close() {
        stateCollection.cancel()
        client.close()
        scope.cancel()
    }

    private suspend fun reconcile(family: ModelFamily, provisionIfReady: Boolean) {
        familyMutex(family).withLock {
            reconcileLocked(family, provisionIfReady)
        }
    }

    private suspend fun reconcileLocked(family: ModelFamily, provisionIfReady: Boolean) {
        intentStore.reconcileMarkers(family)
        val removalPending = removalMarker(family).exists()
        if (ModelDeliveryFileLock.isRemovalAuthoritative(appContext.filesDir, family)) {
            updateState(
                family,
                if (removalPending) {
                    when (_states.value[family]) {
                        ModelDeliveryState.Removing, ModelDeliveryState.Quiescing -> _states.value.getValue(family)
                        else -> ModelDeliveryState.RemovalFailed(
                            "Model removal was interrupted. Retry removal.",
                        )
                    }
                } else {
                    ModelDeliveryState.NotInstalled
                },
            )
            return
        }
        if (materializer.isMarkedInstalled(family)) {
            markInstalledPreservingActivationState(family)
            return
        }
        val spec = ModelDeliveryCatalog.family(family)
        val snapshots = spec.packNames.map { client.states.value[it] }
        if (snapshots.any { it == null }) {
            updateState(
                family,
                if (materializer.isMarkedInstalled(family)) {
                    when (_states.value[family]) {
                        ModelDeliveryState.Activating -> ModelDeliveryState.Activating
                        ModelDeliveryState.InstalledWithActivationError ->
                            ModelDeliveryState.InstalledWithActivationError
                        else -> ModelDeliveryState.Installed
                    }
                } else {
                    ModelDeliveryState.NotInstalled
                },
            )
            return
        }
        val packs = snapshots.filterNotNull()
        val phase = when {
            packs.any { it.phase == AssetPackPhase.FAILED || it.phase == AssetPackPhase.CANCELED } ->
                ModelDeliveryState.Failed("Google Play could not deliver this model")
            packs.any { it.phase == AssetPackPhase.REQUIRES_USER_CONFIRMATION } ->
                ModelDeliveryState.RequiresConfirmation
            packs.any { it.phase == AssetPackPhase.WAITING_FOR_WIFI } -> ModelDeliveryState.WaitingForWifi
            packs.any { it.phase == AssetPackPhase.TRANSFERRING } -> ModelDeliveryState.Transferring
            packs.any { it.phase == AssetPackPhase.DOWNLOADING } -> ModelDeliveryState.Downloading(
                downloadedBytes = packs.sumOf(AssetPackSnapshot::bytesDownloaded),
                totalBytes = packs.sumOf(AssetPackSnapshot::totalBytesToDownload),
            )
            packs.any { it.phase == AssetPackPhase.PENDING } -> ModelDeliveryState.Pending
            packs.all { it.phase == AssetPackPhase.COMPLETED } && provisionIfReady -> {
                if (!intentStore.provisioningAllowed(family)) {
                    updateState(family, ModelDeliveryState.NotInstalled)
                    return
                }
                provision(family, packs)
                return
            }
            else -> ModelDeliveryState.NotInstalled
        }
        updateState(family, phase)
    }

    private suspend fun provision(family: ModelFamily, packs: List<AssetPackSnapshot>) {
        updateState(family, ModelDeliveryState.Provisioning)
        val sourceDirectories = runCatching {
            packs.associate { pack ->
                pack.packName to requireNotNull(pack.assetsPath).let(::File)
            }
        }.getOrElse { error ->
            recordFailure(error, family)
            return
        }
        val result = runCatching {
            withContext(blockingDispatcher) {
                check(intentStore.provisioningAllowed(family)) {
                    "Model provisioning is disabled by removal intent"
                }
                materializer.materialize(family, sourceDirectories)
            }
        }.getOrElse { error ->
            recordFailure(error, family)
            return
        }
        when (result) {
            MaterializationResult.Installed -> activateInstalledLocked(family)
            MaterializationResult.AlreadyInstalled -> markInstalledPreservingActivationState(family)
            is MaterializationResult.Failed ->
                updateState(family, ModelDeliveryState.Failed(result.reason))
        }
    }

    private suspend fun activateInstalledLocked(family: ModelFamily) {
        if (family != ModelFamily.OCR) {
            updateState(family, ModelDeliveryState.Installed)
            return
        }
        updateState(family, ModelDeliveryState.Activating)
        val activation = activationAttempt(family)
        val result = try {
            withTimeoutOrNull(RUNTIME_CONTROL_TIMEOUT_MS) {
                activation.await()
            }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Throwable) {
            RuntimeActivationResult.Failed(RuntimeControlFailure.ACTIVATION_FAILED)
        }
        when (result) {
            RuntimeActivationResult.Activated,
            RuntimeActivationResult.NotRunning,
            -> updateState(family, ModelDeliveryState.Installed)
            is RuntimeActivationResult.Failed, null ->
                updateState(family, ModelDeliveryState.InstalledWithActivationError)
        }
    }

    private fun activationAttempt(family: ModelFamily): Deferred<RuntimeActivationResult> =
        synchronized(operationLock) {
            activationAttempts[family]?.takeIf { it.isActive }?.let { return@synchronized it }
            val attempt = scope.async(start = CoroutineStart.LAZY) {
                runtimeControl.activate(family)
            }
            activationAttempts[family] = attempt
            attempt.invokeOnCompletion { error ->
                if (error == null) {
                    scope.launch(start = CoroutineStart.UNDISPATCHED) {
                        val result = attempt.await()
                        if (
                            result == RuntimeActivationResult.Activated ||
                            result == RuntimeActivationResult.NotRunning
                        ) {
                            _states.update { current ->
                                when (current[family]) {
                                    ModelDeliveryState.Activating,
                                    ModelDeliveryState.InstalledWithActivationError,
                                    -> current + (family to ModelDeliveryState.Installed)
                                    else -> current
                                }
                            }
                        }
                        synchronized(operationLock) {
                            if (activationAttempts[family] === attempt) {
                                activationAttempts.remove(family)
                            }
                        }
                    }
                } else {
                    synchronized(operationLock) {
                        if (activationAttempts[family] === attempt) {
                            activationAttempts.remove(family)
                        }
                    }
                }
            }
            attempt.start()
            attempt
        }

    private fun activationRecoveryJob(family: ModelFamily): Job =
        synchronized(operationLock) {
            activationRecoveryJobs[family]?.takeIf { it.isActive }?.let {
                return@synchronized it
            }
            val job = scope.launch(start = CoroutineStart.LAZY) {
                familyMutex(family).withLock {
                    if (
                        family != ModelFamily.OCR ||
                        _states.value[family] != ModelDeliveryState.InstalledWithActivationError
                    ) {
                        return@withLock
                    }
                    if (!materializer.isMarkedInstalled(family)) {
                        updateState(family, ModelDeliveryState.NotInstalled)
                        return@withLock
                    }
                    activateInstalledLocked(family)
                }
            }
            activationRecoveryJobs[family] = job
            job.invokeOnCompletion {
                synchronized(operationLock) {
                    if (activationRecoveryJobs[family] === job) {
                        activationRecoveryJobs.remove(family)
                    }
                }
            }
            job.start()
            job
        }

    private fun recordFailure(error: Throwable, family: ModelFamily? = null) {
        MindlayerLog.w(TAG, "Model delivery operation failed: ${error.safeLabel()}", throwable = null)
        if (family != null) {
            updateState(
                family,
                ModelDeliveryState.Failed("Model delivery failed. Retry from Google Play."),
            )
        } else {
            _states.update { current ->
                current.mapValues { (_, state) ->
                    if (
                        state == ModelDeliveryState.Installed ||
                        state == ModelDeliveryState.InstalledWithActivationError ||
                        state == ModelDeliveryState.Activating
                    ) {
                        state
                    } else {
                        ModelDeliveryState.Unsupported
                    }
                }
            }
        }
    }

    private suspend fun resumePendingRemovals() {
        ModelFamily.entries.forEach { family ->
            if (removalMarker(family).exists()) {
                familyMutex(family).withLock {
                    if (removalMarker(family).exists()) {
                        removeLocked(family)
                    }
                }
            }
        }
    }

    private suspend fun reconcileIntentMarkers() {
        ModelFamily.entries.forEach { family ->
            familyMutex(family).withLock {
                intentStore.reconcileMarkers(family)
            }
        }
    }

    private suspend fun forceValidateInstalledBytes(family: ModelFamily): Boolean =
        familyMutex(family).withLock {
            val installed = materializer.isMarkedInstalled(family, forceValidation = true)
            if (installed) {
                markInstalledPreservingActivationState(family)
            } else if (
                _states.value[family] == ModelDeliveryState.Installed ||
                _states.value[family] == ModelDeliveryState.Activating ||
                _states.value[family] == ModelDeliveryState.InstalledWithActivationError
            ) {
                updateState(family, ModelDeliveryState.Checking)
            }
            installed
        }

    private suspend fun removeLocked(family: ModelFamily) {
        val marker = removalMarker(family)
        if (!marker.exists() && _states.value[family] == ModelDeliveryState.NotInstalled) {
            return
        }
        updateState(family, ModelDeliveryState.Removing)
        try {
            val packs = ModelDeliveryCatalog.family(family).packNames
            val cancellationFailure = runCatching {
                client.cancel(packs)
            }.exceptionOrNull()
            updateState(family, ModelDeliveryState.Quiescing)
            val release = withTimeoutOrNull(RUNTIME_CONTROL_TIMEOUT_MS) {
                runtimeControl.quiesce(family)
            }
            check(
                release == RuntimeReleaseResult.Released ||
                    release == RuntimeReleaseResult.NotRunning,
            ) {
                "Model runtime did not acknowledge release"
            }
            cancellationFailure?.let { throw it }
            updateState(family, ModelDeliveryState.Removing)
            for (packName in packs) {
                client.removePack(packName)
            }
            materializer.remove(family)
            check(marker.delete() || !marker.exists()) {
                "Could not clear pending model removal"
            }
            updateState(family, ModelDeliveryState.NotInstalled)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            MindlayerLog.w(TAG, "Model removal failed: ${error.safeLabel()}", throwable = null)
            updateState(
                family,
                ModelDeliveryState.RemovalFailed("Model removal failed. Retry removal."),
            )
        }
    }

    private fun removalMarker(family: ModelFamily): File =
        ModelDeliveryFileLock.pendingRemovalMarker(appContext.filesDir, family)

    private fun persistRemovalIntent(family: ModelFamily): Boolean {
        return try {
            removalIntentRecorder?.invoke(family) ?: intentStore.recordRemoval(family)
            true
        } catch (error: Throwable) {
            MindlayerLog.w(TAG, "Could not persist model removal: ${error.safeLabel()}", throwable = null)
            updateState(
                family,
                ModelDeliveryState.RemovalFailed("Model removal could not be started. Retry removal."),
            )
            false
        }
    }

    private fun familyMutex(family: ModelFamily): Mutex = checkNotNull(familyMutexes[family])

    private fun callbacksEnabled(): Boolean =
        synchronized(callbackLock) {
            stateCallbacksEnabled
        }

    private suspend fun enableCallbacksAndReconcileDirtyFamilies() {
        val families = synchronized(callbackLock) {
            stateCallbacksEnabled = true
            ModelFamily.entries.filter { family -> dirtyFamilies.remove(family) }
        }
        if (families.isNotEmpty()) {
            beforeInitialCatchUp()
        }
        families.forEach { family -> reconcile(family, provisionIfReady = true) }
    }

    private fun changedFamilies(
        previous: Map<String, AssetPackSnapshot>,
        current: Map<String, AssetPackSnapshot>,
    ): Set<ModelFamily> =
        ModelFamily.entries.filterTo(mutableSetOf()) { family ->
            ModelDeliveryCatalog.family(family).packNames.any { packName ->
                previous[packName] != current[packName]
            }
        }

    private fun markInstalledPreservingActivationState(family: ModelFamily) {
        when (_states.value[family]) {
            ModelDeliveryState.Activating,
            ModelDeliveryState.InstalledWithActivationError,
            -> Unit
            else -> updateState(family, ModelDeliveryState.Installed)
        }
    }

    private fun updateState(family: ModelFamily, state: ModelDeliveryState) {
        _states.update { current -> current + (family to state) }
    }

    private companion object {
        const val TAG = "ModelDelivery"
        const val RUNTIME_CONTROL_TIMEOUT_MS = 10_000L

        fun pinnedSha256(filename: String): String? = when (filename) {
            "gemma-4-E2B-it.litertlm" -> BuildConfig.MODEL_SHA256
            "embedding-gemma-300m-v1.tflite" -> BuildConfig.EMBEDDING_MODEL_SHA256
            "embedding-gemma-300m-v1.spm.model" -> BuildConfig.EMBEDDING_TOKENIZER_SHA256
            "paddleocr-ppocrv5-mobile-det.tflite" -> BuildConfig.PADDLE_OCR_DET_SHA256
            "paddleocr-ppocrv5-mobile-rec.tflite" -> BuildConfig.PADDLE_OCR_REC_SHA256
            "paddleocr-ppocrv5-mobile-cls.tflite" -> BuildConfig.PADDLE_OCR_CLS_SHA256
            "paddleocr-ppocrv5-mobile-dict.txt" -> BuildConfig.PADDLE_OCR_DICT_SHA256
            else -> null
        }
    }
}
