package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.SystemClock
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ThermalBand {
    COOL, WARM, HOT, CRITICAL
}

/**
 * Quality of the thermal signal that produced the active [ThermalPolicy].
 *
 * - [OBSERVED] — at least one telemetry source reported on the latest
 *   sample (status from API 29+, headroom from API 30+). The 4-band
 *   policy table is trusted.
 * - [INFERRED] — no telemetry source returned data on this sample
 *   (Android 8 / 8.1, API 26-28, where neither
 *   [PowerManager.getCurrentThermalStatus] nor
 *   [PowerManager.getThermalHeadroom] exists). [computeBand] always
 *   resolves to COOL on these devices because there is literally no
 *   signal — but "COOL on no evidence" is not the same as "COOL on
 *   evidence." A conservative policy variant applies (shorter burst,
 *   non-zero rest, halved chunkTokens) so a telemetry-blind device
 *   under genuine thermal stress is not driven into shutdown by a
 *   sustained GPU burst.
 *
 * Defaults to [OBSERVED] so existing test fixtures that construct
 * [ThermalPolicy] with positional arguments continue to compile
 * unchanged. F-073.
 */
enum class ThermalConfidence { OBSERVED, INFERRED }

data class ThermalPolicy(
    val band: ThermalBand,
    val recommendedBackend: String,  // "GPU" or "CPU"
    val burstSeconds: Int,
    val restSeconds: Int,
    val chunkTokens: Int,
    /**
     * F-073: epistemic marker for the policy. [ThermalConfidence.OBSERVED]
     * on API 29+ where thermal telemetry is real; [ThermalConfidence.INFERRED]
     * on API 26-28 where the band defaults to COOL because no thermal
     * API exists. Conservative pacing is baked into the table when
     * [ThermalMonitor.policyForBand] is called with `INFERRED` — consumers
     * that read [burstSeconds], [restSeconds], and [chunkTokens] do not
     * need to branch on this field. Defaulted to OBSERVED for source
     * compatibility with positional fixtures across the test suite.
     */
    val confidence: ThermalConfidence = ThermalConfidence.OBSERVED,
)

data class ThermalSample(
    val status: Int,               // PowerManager.THERMAL_STATUS_*
    val headroomNow: Float?,       // getThermalHeadroom(0)
    val headroom10s: Float?,       // getThermalHeadroom(10)
    val timestampMs: Long,
    /**
     * `true` when at least one thermal telemetry source returned data on
     * this sample (status from API 29+, headroom from API 30+). `false`
     * on Android 8 / 8.1 (API 26-28) where neither API is available — on
     * those devices [computeBand] always returns COOL because there is
     * literally no signal.
     *
     * Lets consumers (dashboard, [RequestTrace], DiagnosticExporter,
     * future thermal policy) distinguish "actually cool" from "telemetry
     * unavailable", so an Android 8 device under genuine thermal stress
     * is not silently misread as a thermally-healthy device.
     *
     * F-070: defaults to `true` so existing test fixtures that pass
     * concrete `status` / `headroom` values continue to compile
     * unchanged; [ThermalMonitor.takeSample] always sets this explicitly.
     */
    val telemetryAvailable: Boolean = true,
)

/**
 * 4-band thermal controller that monitors device thermal state and emits
 * scheduling policies via [StateFlow].
 *
 * Bands: COOL → WARM → HOT → CRITICAL
 *
 * Uses both [PowerManager.getCurrentThermalStatus] (API 29+) and
 * [PowerManager.getThermalHeadroom] (API 30+) sampled at ~1 Hz.
 * Hysteresis on exit thresholds prevents rapid backend flapping, and a 30 s
 * cooldown guards GPU re-enable after thermal fallback.
 *
 * Consumers (e.g. InferenceOrchestrator) observe [currentPolicy] and apply
 * backend switches at request boundaries — never mid-stream.
 */
class ThermalMonitor(
    context: Context,
    private val scope: CoroutineScope,
    private val logRepository: com.adsamcik.mindlayer.service.logging.LogRepository? = null,
    private val clock: () -> Long = { SystemClock.uptimeMillis() },
    /**
     * F-070: SDK version provider, mockable for tests.
     *
     * `Build.VERSION.SDK_INT` is a `static final int` and the Kotlin
     * compiler inlines reads as compile-time constants from the
     * `android.jar` stub (where it is `0`). That makes static-field
     * reflection unreliable for asserting API-level branches —
     * EngineManagerTest's similar pattern only "passes" because its
     * assertions are negative (`assertFalse`) which match the inlined
     * fallback. This indirection lets the
     * [ThermalSampleTelemetryTest] reliably exercise the API 26-28
     * "telemetry unavailable" path without touching production
     * dispatch behaviour in production callers, who keep the default.
     */
    private val sdkInt: () -> Int = { Build.VERSION.SDK_INT },
) {

    companion object {
        private const val TAG = "ThermalMonitor"
        private const val SAMPLE_PERIOD_MS = 1000L

        // Hysteresis thresholds — enter
        private const val WARM_ENTER_HEADROOM = 0.80f
        private const val HOT_ENTER_HEADROOM = 0.92f
        private const val CRITICAL_ENTER_HEADROOM = 1.00f

        // Hysteresis thresholds — exit (lower than enter to prevent flapping)
        private const val WARM_EXIT_HEADROOM = 0.70f
        private const val HOT_EXIT_HEADROOM = 0.82f
        private const val CRITICAL_EXIT_HEADROOM = 0.90f

        // GPU re-enable cooldown after thermal fallback
        private const val GPU_REENABLE_COOLDOWN_MS = 30_000L

        // MODERATE status must persist this long before escalating to HOT
        private const val MODERATE_HOLD_MS = 3_000L
    }

    private val pm = context.getSystemService(PowerManager::class.java)

    private val _currentBand = MutableStateFlow(ThermalBand.COOL)
    val currentBand: StateFlow<ThermalBand> = _currentBand.asStateFlow()

    private val _currentPolicy = MutableStateFlow(policyForBand(ThermalBand.COOL))
    val currentPolicy: StateFlow<ThermalPolicy> = _currentPolicy.asStateFlow()

    private val _latestSample = MutableStateFlow<ThermalSample?>(null)
    val latestSample: StateFlow<ThermalSample?> = _latestSample.asStateFlow()

    private var pollJob: Job? = null
    private var statusListener: Any? = null  // typed as Any for API gating

    private var lastGpuDisableTimeMs: Long = 0
    private var moderateEntryTimeMs: Long = 0

    // ---- Public API --------------------------------------------------------

    fun start() {
        // Register status listener (API 29+)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val listener = PowerManager.OnThermalStatusChangedListener { status ->
                MindlayerLog.d(TAG, "Thermal status callback: $status")
                processSample()
            }
            pm.addThermalStatusListener(listener)
            statusListener = listener
        }

        // Poll headroom at ~1 Hz
        pollJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                processSample()
                delay(SAMPLE_PERIOD_MS)
            }
        }

        MindlayerLog.i(TAG, "Thermal monitor started")
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && statusListener != null) {
            pm.removeThermalStatusListener(
                statusListener as PowerManager.OnThermalStatusChangedListener
            )
            statusListener = null
        }

        MindlayerLog.i(TAG, "Thermal monitor stopped")
    }

    /**
     * Check whether the GPU can be re-enabled.
     * Returns `true` only when the band is COOL *and* the 30 s cooldown has
     * elapsed since the last GPU disable event.
     */
    fun canReenableGpu(): Boolean {
        if (_currentBand.value != ThermalBand.COOL) return false
        if (lastGpuDisableTimeMs == 0L) return true
        return clock() - lastGpuDisableTimeMs >= GPU_REENABLE_COOLDOWN_MS
    }

    /** Record the timestamp when the GPU backend was disabled for cooldown tracking. */
    fun recordGpuDisabled() {
        lastGpuDisableTimeMs = clock()
    }

    // ---- Internal ----------------------------------------------------------

    private fun processSample() {
        val sample = takeSample()
        _latestSample.value = sample

        val newBand = computeBand(sample, _currentBand.value)
        // F-073: derive policy confidence from the sample's telemetry signal.
        // On API 26-28 there is no thermal API, so `telemetryAvailable` is
        // false and the conservative variant of `policyForBand` applies.
        val newConfidence = if (sample.telemetryAvailable) {
            ThermalConfidence.OBSERVED
        } else {
            ThermalConfidence.INFERRED
        }
        val newPolicy = policyForBand(newBand, newConfidence)

        if (newBand != _currentBand.value) {
            val oldBand = _currentBand.value
            MindlayerLog.i(
                TAG, "Thermal band: $oldBand → $newBand " +
                    "(status=${sample.status}, headroom10s=${sample.headroom10s}, " +
                    "confidence=$newConfidence)"
            )
            logRepository?.logThermalBandChange(
                oldBand.name, newBand.name, _currentPolicy.value.recommendedBackend
            )

            // Track GPU disable timestamp when escalating past WARM
            if (newBand.ordinal > ThermalBand.WARM.ordinal &&
                _currentBand.value.ordinal <= ThermalBand.WARM.ordinal
            ) {
                recordGpuDisabled()
            }

            _currentBand.value = newBand
        }

        // F-073: update the policy on every sample where it changed —
        // including transitions of `confidence` alone (e.g. an OEM
        // driver starts reporting mid-session). Previously we only
        // refreshed `_currentPolicy` on band transitions, which would
        // have left a stale OBSERVED policy in place after a flip to
        // INFERRED on the same band.
        if (newPolicy != _currentPolicy.value) {
            _currentPolicy.value = newPolicy
        }
    }

    private fun takeSample(): ThermalSample {
        val statusAvailable = sdkInt() >= Build.VERSION_CODES.Q
        val status = if (statusAvailable) {
            pm.currentThermalStatus
        } else {
            PowerManager.THERMAL_STATUS_NONE
        }

        val headroomNow = if (sdkInt() >= Build.VERSION_CODES.R) {
            pm.getThermalHeadroom(0).takeIf { it.isFinite() }
        } else null

        val headroom10s = if (sdkInt() >= Build.VERSION_CODES.R) {
            pm.getThermalHeadroom(10).takeIf { it.isFinite() }
        } else null

        // F-070: distinguish "device reported COOL" from "device cannot
        // report at all" so consumers can choose a conservative policy
        // on telemetry-blind hardware (Android 8 / 8.1) instead of
        // assuming the silence means everything is fine.
        val telemetryAvailable = statusAvailable || headroomNow != null || headroom10s != null

        return ThermalSample(
            status = status,
            headroomNow = headroomNow,
            headroom10s = headroom10s,
            timestampMs = clock(),
            telemetryAvailable = telemetryAvailable,
        )
    }

    /**
     * Determine the target [ThermalBand] given the latest [sample] and
     * [currentBand].  Uses entry/exit hysteresis and a hold timer for MODERATE.
     */
    internal fun computeBand(sample: ThermalSample, currentBand: ThermalBand): ThermalBand {
        val status = sample.status
        val h = sample.headroom10s ?: Float.NaN
        val now = clock()

        // --- CRITICAL (highest priority) ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (status >= PowerManager.THERMAL_STATUS_SEVERE) return ThermalBand.CRITICAL
        }
        if (h.isFinite() && h >= CRITICAL_ENTER_HEADROOM) return ThermalBand.CRITICAL

        // Exit CRITICAL with hysteresis
        if (currentBand == ThermalBand.CRITICAL) {
            if (h.isFinite() && h >= CRITICAL_EXIT_HEADROOM) return ThermalBand.CRITICAL
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q &&
                status >= PowerManager.THERMAL_STATUS_MODERATE
            ) return ThermalBand.HOT
        }

        // --- HOT ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (status >= PowerManager.THERMAL_STATUS_MODERATE) {
                if (moderateEntryTimeMs == 0L) moderateEntryTimeMs = now
                if (now - moderateEntryTimeMs >= MODERATE_HOLD_MS) return ThermalBand.HOT
            } else {
                moderateEntryTimeMs = 0
            }
        }
        if (h.isFinite() && h >= HOT_ENTER_HEADROOM) return ThermalBand.HOT

        // Exit HOT with hysteresis
        if (currentBand == ThermalBand.HOT) {
            if (h.isFinite() && h >= HOT_EXIT_HEADROOM) return ThermalBand.HOT
        }

        // --- WARM ---
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            if (status >= PowerManager.THERMAL_STATUS_LIGHT) return ThermalBand.WARM
        }
        if (h.isFinite() && h >= WARM_ENTER_HEADROOM) return ThermalBand.WARM

        // Exit WARM with hysteresis
        if (currentBand == ThermalBand.WARM) {
            if (h.isFinite() && h >= WARM_EXIT_HEADROOM) return ThermalBand.WARM
        }

        return ThermalBand.COOL
    }

    /**
     * Map a [band] to its [ThermalPolicy], honouring [confidence].
     *
     * - [ThermalConfidence.OBSERVED] returns the canonical 4-band table.
     *   These values are pinned by 35 [ThermalBandTest] cases and
     *   [ThermalStateMachineTest]'s `policyMapping_allBands`; do not
     *   change them without updating the locked tests.
     * - [ThermalConfidence.INFERRED] returns a conservative variant
     *   that shortens burst, lengthens rest, and halves `chunkTokens`
     *   so a telemetry-blind device cannot accumulate enough sustained
     *   GPU burst heat to hit thermal shutdown. F-073.
     *
     * Backward compatibility: the single-argument overload below
     * preserves the original signature so existing callers (the
     * `_currentPolicy` initialiser, internal debug helpers) keep
     * compiling unchanged. All production updates flow through the
     * two-argument form via [processSample].
     */
    internal fun policyForBand(
        band: ThermalBand,
        confidence: ThermalConfidence,
    ): ThermalPolicy = when (confidence) {
        ThermalConfidence.OBSERVED -> when (band) {
            ThermalBand.COOL ->
                ThermalPolicy(band, "GPU", burstSeconds = 12, restSeconds = 0, chunkTokens = 128)
            ThermalBand.WARM ->
                ThermalPolicy(band, "GPU", burstSeconds = 8, restSeconds = 3, chunkTokens = 64)
            ThermalBand.HOT ->
                ThermalPolicy(band, "CPU", burstSeconds = 4, restSeconds = 5, chunkTokens = 32)
            ThermalBand.CRITICAL ->
                ThermalPolicy(band, "CPU", burstSeconds = 0, restSeconds = 8, chunkTokens = 16)
        }
        ThermalConfidence.INFERRED -> when (band) {
            // In practice INFERRED only ever pairs with COOL — on
            // telemetry-blind devices `computeBand` has no signal to
            // elevate. The other bands are filled in defensively so a
            // future code path that forces an elevation under inferred
            // confidence does not return a null policy.
            ThermalBand.COOL ->
                ThermalPolicy(
                    band, "GPU", burstSeconds = 6, restSeconds = 4, chunkTokens = 64,
                    confidence = ThermalConfidence.INFERRED,
                )
            ThermalBand.WARM ->
                ThermalPolicy(
                    band, "GPU", burstSeconds = 4, restSeconds = 5, chunkTokens = 32,
                    confidence = ThermalConfidence.INFERRED,
                )
            ThermalBand.HOT ->
                ThermalPolicy(
                    band, "CPU", burstSeconds = 3, restSeconds = 6, chunkTokens = 16,
                    confidence = ThermalConfidence.INFERRED,
                )
            ThermalBand.CRITICAL ->
                ThermalPolicy(
                    band, "CPU", burstSeconds = 0, restSeconds = 10, chunkTokens = 16,
                    confidence = ThermalConfidence.INFERRED,
                )
        }
    }

    /** Backward-compatible overload — defaults to [ThermalConfidence.OBSERVED]. */
    private fun policyForBand(band: ThermalBand): ThermalPolicy =
        policyForBand(band, ThermalConfidence.OBSERVED)
}
