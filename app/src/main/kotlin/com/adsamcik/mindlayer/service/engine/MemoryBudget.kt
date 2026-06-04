package com.adsamcik.mindlayer.service.engine

import android.app.ActivityManager
import android.content.ComponentCallbacks2
import android.content.Context
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Pressure levels ordered by severity.  Consumers compare ordinals to decide
 * how aggressively to shed load.
 */
enum class MemoryPressure {
    /** Plenty of headroom — operate normally. */
    NORMAL,
    /** Available RAM is getting low — restrict new session creation. */
    WARNING,
    /** Available RAM is scarce — evict idle sessions. */
    CRITICAL,
    /** Near-OOM — evict aggressively, force 2 048-token context limit. */
    EMERGENCY,
}

/**
 * Static device tier derived from **total** RAM. Computed once and cached.
 *
 * Tier table revised in this PR based on empirical KV-cache measurements
 * of Gemma 4 E2B on LiteRT-LM 0.12.0 — see `docs/MEMORY_TIERS_EMPIRICS.md`
 * for the methodology and raw data.
 *
 * Measured cost per configured token: **9 208 bytes** (R²=0.9990, 95% CI
 * [8 886, 9 736]). Fixed engine+model footprint: ~474 MiB. Process
 * baseline: ~127 MiB. Mindlayer steady-state PSS at N configured tokens
 * is therefore approximated by `127 + 474 + 9.5 × N / 1024` MiB (the 9.5
 * factor rounds up from measured 9.2 for safety).
 *
 * Tier formula:
 *
 *   budget_mib  = (totalMem - 700 [foreground app]) × 0.85 [fragmentation]
 *                 - 256 [LMK runway]
 *   N_max       = floor(((budget_mib - 256 [peak transient] - 601 [fixed])
 *                 × 1024) / 9.5)
 *   maxMaxTokens = N_max capped at 131 072 (Gemma 4 E2B model max)
 *
 * **`maxSessions` is 1 across every tier today** — LiteRT-LM 0.12.0
 * enforces "at most one Conversation per Engine at a time" at the JNI
 * layer (see `WarmConversationSlot` and the analysis writeup). The
 * `maxSessions` field reflects the *concurrent native conversation* cap,
 * not the *open-session* cap. A subsequent PR will wire up hot-swap and
 * expose a separate `maxOpenSessions` field.
 *
 * | totalMem | maxSessions | defaultMaxTokens | maxMaxTokens |
 * |----------|-------------|------------------|--------------|
 * | ≤ 4 GB   | 1           | 8 192            | 32 768       |
 * | ≤ 6 GB   | 1           | 16 384           | 65 536       |
 * | ≤ 8 GB   | 1           | 32 768           | 131 072      |
 * | ≤ 12 GB  | 1           | 65 536           | 131 072      |
 * | > 12 GB  | 1           | 131 072          | 131 072      |
 */
data class DeviceTier(
    val maxSessions: Int,
    val defaultMaxTokens: Int,
    val maxMaxTokens: Int,
    val deviceRamMb: Long,
)

/**
 * Point-in-time reading of system memory state together with the policy
 * recommendation derived from it.
 */
data class MemorySnapshot(
    val availableMb: Long,
    val totalMb: Long,
    val lowMemory: Boolean,
    val pressure: MemoryPressure,
    /** Token ceiling that should be enforced at this pressure level. */
    val recommendedMaxTokens: Int,
)

/**
 * Runtime memory monitor that periodically polls [ActivityManager] and emits
 * [MemoryPressure] levels via [StateFlow].
 *
 * Follows the same poll-and-emit pattern as [ThermalMonitor]:
 *   - ~10 s polling interval (memory changes slower than thermals)
 *   - Hysteresis on exit thresholds to prevent flapping
 *   - Immediate re-evaluation on [onTrimMemory] callbacks
 *
 * Absolute thresholds for available RAM:
 *   EMERGENCY  < 400 MB  (or system `lowMemory` flag)
 *   CRITICAL   < 800 MB
 *   WARNING    < 1 200 MB
 *   NORMAL     ≥ 1 200 MB
 *
 * Exit thresholds sit 100 MB above entry to avoid rapid cycling.
 */
class MemoryBudget(
    private val context: Context,
    private val scope: CoroutineScope,
    private val logRepository: com.adsamcik.mindlayer.service.logging.LogRepository? = null,
) {

    companion object {
        private const val TAG = "MemoryBudget"
        private const val POLL_INTERVAL_MS = 10_000L

        // Entry thresholds (available MB)
        private const val EMERGENCY_ENTER_MB = 400L
        private const val CRITICAL_ENTER_MB = 800L
        private const val WARNING_ENTER_MB = 1200L

        // Exit thresholds — higher than entry to add hysteresis
        private const val EMERGENCY_EXIT_MB = 500L
        private const val CRITICAL_EXIT_MB = 900L
        private const val WARNING_EXIT_MB = 1300L

        private const val EMERGENCY_MAX_TOKENS = 2048
    }

    private val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager

    /** Static device tier (total RAM never changes at runtime). */
    val deviceTier: DeviceTier by lazy { computeDeviceTier() }

    private val _pressure = MutableStateFlow(MemoryPressure.NORMAL)
    val pressure: StateFlow<MemoryPressure> = _pressure.asStateFlow()

    private val _snapshot = MutableStateFlow<MemorySnapshot?>(null)
    val snapshot: StateFlow<MemorySnapshot?> = _snapshot.asStateFlow()

    private var pollJob: Job? = null

    // ---- Public API --------------------------------------------------------

    fun start() {
        // R-21: idempotent — a second start() without stop() would orphan
        // the prior poll job (stop() could then never reach it).
        if (pollJob != null) {
            MindlayerLog.w(TAG, "MemoryBudget.start() ignored — already running")
            return
        }
        pollJob = scope.launch(Dispatchers.Default) {
            while (isActive) {
                evaluate()
                delay(POLL_INTERVAL_MS)
            }
        }
        MindlayerLog.i(TAG, "Memory monitor started (tier=${deviceTier})")
    }

    fun stop() {
        pollJob?.cancel()
        pollJob = null
        MindlayerLog.i(TAG, "Memory monitor stopped")
    }

    /**
     * Force an immediate re-evaluation.  Called from
     * [MindlayerMlService.onTrimMemory] so we don't wait for the next poll.
     *
     * F-070: the previous implementation used a binary `>=` cascade
     * (only `RUNNING_CRITICAL` and `RUNNING_LOW` were named), which had
     * three flaws:
     *
     *  1. `TRIM_MEMORY_RUNNING_MODERATE` (5) fell through silently — no
     *     pre-emptive action even when the system warned us early.
     *  2. `TRIM_MEMORY_UI_HIDDEN` (20), `TRIM_MEMORY_BACKGROUND` (40),
     *     `TRIM_MEMORY_MODERATE` (60), and `TRIM_MEMORY_COMPLETE` (80)
     *     collapsed into EMERGENCY because they are all `>= 15`.
     *     `UI_HIDDEN` / `BACKGROUND` are informational ("you've moved
     *     to the LRU list") and do not warrant emergency token-cap
     *     clamping.
     *  3. The trailing call to `evaluate()` could silently downgrade a
     *     just-applied escalation: with abundant free RAM the snapshot
     *     would compute NORMAL and overwrite an EMERGENCY hint emitted
     *     in the same call. The poll loop is the right place for
     *     de-escalation; `onTrimMemory` is reactive to system signals
     *     and must not suppress them.
     *
     * The explicit `when` mapping below treats foreground-pressure
     * hints proportionally and background-LRU hints as gentle warnings
     * until the system escalates to `MODERATE` or `COMPLETE`. Snapshot
     * refresh is decoupled from pressure recomputation: `currentSnapshot()`
     * updates `_snapshot.value` for the dashboard but does NOT touch
     * `_pressure`, so the system hint always wins for the duration of
     * this call.
     */
    @Suppress("DEPRECATION")
    fun onTrimMemory(level: Int) {
        val target = when (level) {
            // Foreground pressure: process is running and the system
            // is signalling proportional pressure on us specifically.
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> MemoryPressure.WARNING
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW      -> MemoryPressure.CRITICAL
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> MemoryPressure.EMERGENCY
            // Background hints: process is on the LRU list and the
            // system is choosing what to keep around.
            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN  -> MemoryPressure.WARNING
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> MemoryPressure.WARNING
            ComponentCallbacks2.TRIM_MEMORY_MODERATE   -> MemoryPressure.CRITICAL
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE   -> MemoryPressure.EMERGENCY
            // Unknown / future level: refresh snapshot but don't auto-escalate.
            else -> null
        }
        if (target != null) {
            escalateToAtLeast(target)
        }
        // Refresh _snapshot for the dashboard without re-running
        // computePressure — the poll loop handles natural de-escalation.
        currentSnapshot()
    }

    /**
     * Take a fresh [MemorySnapshot].  Useful for one-shot checks (e.g. before
     * creating a session) without waiting for the next poll.
     */
    fun currentSnapshot(): MemorySnapshot {
        val snap = sample()
        _snapshot.value = snap
        return snap
    }

    // ---- Internal ----------------------------------------------------------

    private fun evaluate() {
        val snap = sample()
        val previous = _pressure.value
        val next = computePressure(snap, previous)

        _snapshot.value = snap

        if (next != previous) {
            MindlayerLog.w(
                TAG, "Pressure: $previous → $next " +
                    "(avail=${snap.availableMb} MB, low=${snap.lowMemory})"
            )
            _pressure.value = next
            logRepository?.logMemoryPressure(next.name, snap.availableMb, snap.totalMb)
        }
    }

    private fun sample(): MemorySnapshot {
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)

        val availMb = memInfo.availMem / (1024L * 1024L)
        val totalMb = memInfo.totalMem / (1024L * 1024L)
        val pressure = computePressure(availMb, memInfo.lowMemory, _pressure.value)
        val recommended = recommendedMaxTokens(pressure)

        return MemorySnapshot(
            availableMb = availMb,
            totalMb = totalMb,
            lowMemory = memInfo.lowMemory,
            pressure = pressure,
            recommendedMaxTokens = recommended,
        )
    }

    /**
     * Determine the target [MemoryPressure] from a snapshot and the current
     * level, applying hysteresis on the exit side.
     */
    private fun computePressure(snap: MemorySnapshot, current: MemoryPressure): MemoryPressure =
        computePressure(snap.availableMb, snap.lowMemory, current)

    private fun computePressure(
        availMb: Long,
        lowMemory: Boolean,
        current: MemoryPressure,
    ): MemoryPressure {
        // --- EMERGENCY (highest severity) ---
        if (lowMemory || availMb < EMERGENCY_ENTER_MB) return MemoryPressure.EMERGENCY
        if (current == MemoryPressure.EMERGENCY && availMb < EMERGENCY_EXIT_MB) {
            return MemoryPressure.EMERGENCY
        }

        // --- CRITICAL ---
        if (availMb < CRITICAL_ENTER_MB) return MemoryPressure.CRITICAL
        if (current == MemoryPressure.CRITICAL && availMb < CRITICAL_EXIT_MB) {
            return MemoryPressure.CRITICAL
        }

        // --- WARNING ---
        if (availMb < WARNING_ENTER_MB) return MemoryPressure.WARNING
        if (current == MemoryPressure.WARNING && availMb < WARNING_EXIT_MB) {
            return MemoryPressure.WARNING
        }

        return MemoryPressure.NORMAL
    }

    /**
     * Token ceiling recommended for the given pressure level.
     * Falls back gracefully towards [EMERGENCY_MAX_TOKENS].
     */
    private fun recommendedMaxTokens(pressure: MemoryPressure): Int {
        val tier = deviceTier
        return when (pressure) {
            MemoryPressure.NORMAL   -> tier.maxMaxTokens
            MemoryPressure.WARNING  -> tier.defaultMaxTokens
            MemoryPressure.CRITICAL -> (tier.defaultMaxTokens / 2).coerceAtLeast(EMERGENCY_MAX_TOKENS)
            MemoryPressure.EMERGENCY -> EMERGENCY_MAX_TOKENS
        }
    }

    /**
     * Monotonically escalate — never lower the pressure level from this path.
     * The normal poll loop can de-escalate once memory actually frees up.
     */
    private fun escalateToAtLeast(target: MemoryPressure) {
        if (target.ordinal > _pressure.value.ordinal) {
            _pressure.value = target
        }
    }

    private fun computeDeviceTier(): DeviceTier {
        val memInfo = ActivityManager.MemoryInfo()
        am.getMemoryInfo(memInfo)
        val totalMb = memInfo.totalMem / (1024L * 1024L)

        // See DeviceTier KDoc for the empirical derivation. maxSessions is
        // pinned at 1 across every tier because LiteRT-LM 0.12.0 enforces
        // one Conversation per Engine. A follow-up PR will lift this when
        // WarmConversationSlot is wired into createSession.
        return when {
            totalMb <= 4L * 1024L  -> DeviceTier(1, 8192,   32_768,  totalMb)
            totalMb <= 6L * 1024L  -> DeviceTier(1, 16_384, 65_536,  totalMb)
            totalMb <= 8L * 1024L  -> DeviceTier(1, 32_768, 131_072, totalMb)
            totalMb <= 12L * 1024L -> DeviceTier(1, 65_536, 131_072, totalMb)
            else                   -> DeviceTier(1, 131_072, 131_072, totalMb)
        }
    }
}
