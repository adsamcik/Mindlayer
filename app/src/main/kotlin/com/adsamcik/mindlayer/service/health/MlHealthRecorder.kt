package com.adsamcik.mindlayer.service.health

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException
import java.util.concurrent.atomic.AtomicLong

/**
 * F-074: cross-process crash-loop watchdog for the `:ml` service process.
 *
 * The `:ml` service is `START_NOT_STICKY` and has no system-managed restart
 * counter. If native LiteRT-LM repeatedly crashes — typically by tripping
 * the OOM-killer because every reconnect re-loads the 2.4 GB model — the
 * SDK's `ConnectionManager` goes into a binder-death + reconnect loop and
 * the system kills `:ml` again on each rebind. This recorder breaks the
 * loop by tracking abnormal deaths and throttling new binds for a cooldown
 * window once a threshold of deaths within a rolling window is reached.
 *
 * ### What counts as an "abnormal death"
 *
 *  - ✅ Uncaught exception in `:ml` — caught by
 *    [Thread.setDefaultUncaughtExceptionHandler] in
 *    `MindlayerMlService.onCreate` and dispatched to [recordAbnormalDeath]
 *    before the original handler fires.
 *  - ✅ OOM-killer (or any external `SIGKILL`) kills `:ml` — no JVM hooks
 *    fire, so we detect it on the next boot via "previous boot ran but
 *    neither [recordCleanShutdown] nor [recordAbnormalDeath] was recorded
 *    afterwards" (see [recordHealthyBoot]).
 *  - ❌ User-initiated stop via the dashboard `STOP` action — this routes
 *    through `MindlayerMlService.onDestroy` which calls
 *    [recordCleanShutdown].
 *  - ❌ Foreground exit after `activeInferenceCount == 0` — same path.
 *
 * ### Persistence
 *
 * The state lives in a single JSON file in `filesDir/ml_health/` so that
 * the main-process dashboard and the `:ml` service can both read it
 * (writes are exclusive to `:ml`). Cross-process coordination is by
 * [FileLock] on a sidecar `.lock` file plus atomic-rename on writes —
 * mirrors `AllowlistStore.kt`. We deliberately do **not** use
 * `SharedPreferences`, which caches per-process and would never invalidate
 * across the main↔`:ml` boundary.
 *
 * ### Read coupling
 *
 * The hot path is [shouldThrottleBinds], called inside
 * `ServiceBinder.authorizeCall` for every external AIDL invocation. The
 * read is unlocked because all writes are via atomic-rename: a concurrent
 * reader sees either the pre-write or post-write JSON, never a torn
 * state. The dashboard polls [peek] from the main process on the same
 * file, with the same race-tolerance.
 *
 * ### Decay
 *
 * Two decays exist:
 *  1. **Throttle window** ([THROTTLE_WINDOW_MS]) — a rolling 60 s window
 *     enforced by [shouldThrottleBinds]: even without a reboot, 60 s
 *     after the last recorded death the throttle naturally lifts.
 *  2. **Counter reset** ([HEALTHY_UPTIME_DECAY_MS]) — a 5-minute uptime
 *     after the last death zeroes the counter on the next
 *     [recordHealthyBoot] so a healthy service that has been running
 *     stably starts every boot from a clean slate.
 *
 * Both bounds are constants exposed via `companion object` so tests can
 * import them.
 *
 * @param baseDir directory that owns the JSON + lock files. Defaults to
 *   `filesDir/ml_health` under the app context.
 * @param clock injectable wall-clock; tests use a virtual clock so
 *   simulated decay does not depend on real time.
 */
class MlHealthRecorder @VisibleForTesting internal constructor(
    private val baseDir: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    constructor(context: Context) : this(
        baseDir = File(context.applicationContext.filesDir, DEFAULT_DIR_NAME),
    )

    private val stateFile: File = File(baseDir, "abnormal_deaths.json")
    private val lockFile: File = File(baseDir, "abnormal_deaths.lock")

    private val json = Json { ignoreUnknownKeys = true }

    // Hot-path cache of the on-disk counters. The on-disk snapshot is the
    // source of truth (so values survive process death); these AtomicLongs
    // are seeded from disk in `init` and kept in sync on every write so
    // intra-process reads avoid a file round-trip.
    private val deferredSubmitCount = AtomicLong(0)
    private val deferredCompletionCount = AtomicLong(0)

    init {
        baseDir.mkdirs()
        // Seed the in-memory cache from disk so a fresh recorder reflects
        // counters accumulated by a previous process.
        val seed = readStateUnlocked()
        deferredSubmitCount.set(seed.deferredSubmits)
        deferredCompletionCount.set(seed.deferredCompletions)
    }

    /**
     * Increments the deferred-submit diagnostic counter and persists the
     * new value atomically under the file lock so it survives process
     * death. The on-disk snapshot is the source of truth; the in-memory
     * [AtomicLong] cache is updated to match.
     */
    fun recordDeferredSubmit() {
        withFileLock {
            val s = readStateLocked()
            val next = s.deferredSubmits + 1
            writeStateLocked(s.copy(deferredSubmits = next))
            deferredSubmitCount.set(next)
        }
    }

    /**
     * Increments the deferred-completion diagnostic counter and persists
     * the new value atomically. See [recordDeferredSubmit] for the
     * cache/source-of-truth contract.
     */
    fun recordDeferredCompletion() {
        withFileLock {
            val s = readStateLocked()
            val next = s.deferredCompletions + 1
            writeStateLocked(s.copy(deferredCompletions = next))
            deferredCompletionCount.set(next)
        }
    }

    /**
     * Returns the persisted deferred-submit count. Reads the latest disk
     * state via [peek] so the dashboard sees writes from `:ml` on every
     * call (atomic-rename guarantees a torn read is impossible).
     */
    fun deferredSubmits(): Long = peek().deferredSubmits

    /** See [deferredSubmits]. */
    fun deferredCompletions(): Long = peek().deferredCompletions

    /**
     * Snapshot of the persisted state. Exposed via [peek] so the
     * dashboard can render the throttle UI without going through AIDL.
     *
     * `deferredSubmits` / `deferredCompletions` are diagnostic counters
     * for the deferred-task pipeline, persisted on every record so they
     * survive `:ml` process death. They are intentionally untouched by
     * the decay path in [recordHealthyBoot] — they are not crash-loop
     * state.
     */
    data class Snapshot(
        val lastBootAt: Long,
        val lastDeathAt: Long,
        val deathCount: Int,
        val lastResetAt: Long,
        val lastCleanShutdownAt: Long,
        val deferredSubmits: Long = 0L,
        val deferredCompletions: Long = 0L,
    ) {
        companion object {
            val EMPTY = Snapshot(0L, 0L, 0, 0L, 0L, 0L, 0L)
        }
    }

    /**
     * Called from `MindlayerMlService.onCreate`. Performs three things,
     * in order:
     *  1. **Decay** — if the counter is non-zero and the last recorded
     *     death is older than [HEALTHY_UPTIME_DECAY_MS], reset the
     *     counter so a stable service starts each boot fresh.
     *  2. **Missed-death detection** — if the previous boot recorded
     *     `lastBootAt` but no later [recordCleanShutdown] *and* no later
     *     [recordAbnormalDeath] was logged, the previous run was killed
     *     externally (OOM, SIGKILL). Bump the counter so the throttle
     *     can engage on the next bind.
     *  3. **Mark this boot** — persist the new [lastBootAt] so the
     *     *next* boot can run the missed-death check against it.
     *
     * The three steps are atomic under the file lock.
     */
    fun recordHealthyBoot() {
        withFileLock {
            val now = clock()
            val s = readStateLocked()
            val prevBoot = s.lastBootAt

            var deathCount = s.deathCount
            var lastDeathAt = s.lastDeathAt
            var lastResetAt = s.lastResetAt

            if (deathCount > 0 &&
                lastDeathAt > 0L &&
                (now - lastDeathAt) > HEALTHY_UPTIME_DECAY_MS
            ) {
                deathCount = 0
                lastResetAt = now
            }

            if (prevBoot > 0L &&
                prevBoot > s.lastCleanShutdownAt &&
                prevBoot > s.lastDeathAt
            ) {
                deathCount += 1
                lastDeathAt = now
            }

            writeStateLocked(
                s.copy(
                    lastBootAt = now,
                    lastDeathAt = lastDeathAt,
                    deathCount = deathCount,
                    lastResetAt = lastResetAt,
                ),
                // deferredSubmits / deferredCompletions are intentionally
                // omitted from the copy — they are diagnostic counters,
                // not crash-loop state, and must survive the boot decay.
            )
        }
    }

    /**
     * Called from a [Thread.UncaughtExceptionHandler] before the
     * original handler is invoked. Bumps [Snapshot.deathCount] and
     * stamps [Snapshot.lastDeathAt] so the watchdog can engage on the
     * next bind.
     *
     * Exceptions thrown inside this method are swallowed by the
     * caller — never block the death path with an I/O failure.
     */
    fun recordAbnormalDeath() {
        withFileLock {
            val now = clock()
            val s = readStateLocked()
            writeStateLocked(
                s.copy(
                    deathCount = s.deathCount + 1,
                    lastDeathAt = now,
                ),
            )
        }
    }

    /**
     * Called from `MindlayerMlService.onDestroy` when the service exits
     * cleanly (user STOP action, idle-foreground exit, etc.). Stamps
     * [Snapshot.lastCleanShutdownAt] so the next [recordHealthyBoot]
     * will skip the missed-death bump.
     */
    fun recordCleanShutdown() {
        withFileLock {
            val now = clock()
            val s = readStateLocked()
            writeStateLocked(s.copy(lastCleanShutdownAt = now))
        }
    }

    /**
     * Hot-path predicate consulted in `ServiceBinder.authorizeCall`.
     * Returns `true` when [Snapshot.deathCount] is at or above
     * [DEATH_COUNT_THRESHOLD] and the most recent death is within the
     * rolling [THROTTLE_WINDOW_MS] window. Reads are unlocked (atomic
     * rename guarantees no torn state).
     */
    fun shouldThrottleBinds(): Boolean {
        val s = peek()
        if (s.deathCount < DEATH_COUNT_THRESHOLD) return false
        if (s.lastDeathAt <= 0L) return false
        return (clock() - s.lastDeathAt) < THROTTLE_WINDOW_MS
    }

    /**
     * Wall-clock timestamp (UTC ms) at which the throttle window
     * naturally expires. Computed as `lastDeathAt + THROTTLE_WINDOW_MS`.
     * Returns 0 when no death has been recorded.
     */
    fun cooldownEndsAt(): Long {
        val s = peek()
        if (s.lastDeathAt <= 0L) return 0L
        return s.lastDeathAt + THROTTLE_WINDOW_MS
    }

    /**
     * Snapshot read for the dashboard / UI. Unlocked by design — atomic
     * rename guarantees the reader sees either the pre-write or
     * post-write JSON, never a half-written file. Also serves as the
     * read path for [deferredSubmits] / [deferredCompletions] so a
     * cross-process reader (the main-process dashboard) always sees the
     * latest counter written by `:ml`.
     */
    fun peek(): Snapshot = readStateUnlocked()

    // ---- Persistence ---------------------------------------------------

    private inline fun <T> withFileLock(block: () -> T): T {
        RandomAccessFile(lockFile, "rw").use { raf ->
            raf.channel.use { ch ->
                val lock: FileLock = ch.lock()
                try {
                    return block()
                } finally {
                    try { lock.release() } catch (_: Throwable) { /* best-effort */ }
                }
            }
        }
    }

    private fun readStateLocked(): Snapshot = readStateUnlocked()

    private fun readStateUnlocked(): Snapshot {
        if (!stateFile.exists()) return Snapshot.EMPTY
        val raw = try {
            stateFile.readText()
        } catch (_: IOException) {
            return Snapshot.EMPTY
        }
        if (raw.isEmpty()) return Snapshot.EMPTY
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            Snapshot(
                lastBootAt = obj.longField("lastBootAt"),
                lastDeathAt = obj.longField("lastDeathAt"),
                deathCount = obj.intField("deathCount"),
                lastResetAt = obj.longField("lastResetAt"),
                lastCleanShutdownAt = obj.longField("lastCleanShutdownAt"),
                deferredSubmits = obj.longField("deferredSubmits"),
                deferredCompletions = obj.longField("deferredCompletions"),
            )
        } catch (_: Throwable) {
            Snapshot.EMPTY
        }
    }

    private fun writeStateLocked(state: Snapshot) {
        val obj: JsonObject = buildJsonObject {
            put("lastBootAt", state.lastBootAt)
            put("lastDeathAt", state.lastDeathAt)
            put("deathCount", state.deathCount)
            put("lastResetAt", state.lastResetAt)
            put("lastCleanShutdownAt", state.lastCleanShutdownAt)
            put("deferredSubmits", state.deferredSubmits)
            put("deferredCompletions", state.deferredCompletions)
        }
        atomicWrite(stateFile, obj.toString())
    }

    /**
     * Atomic-rename + fsync write — mirrors `AllowlistStore.atomicWrite`.
     * A crash mid-write must never leave a half-written file that parses
     * as `Snapshot.EMPTY` (silent reset of the watchdog).
     */
    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            FileOutputStream(tmp).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                try { fos.fd.sync() } catch (_: Throwable) { /* fsync best-effort */ }
            }
            try {
                Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                if (!tmp.renameTo(target)) {
                    Files.move(
                        tmp.toPath(),
                        target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    private fun JsonObject.longField(key: String): Long =
        this[key]?.jsonPrimitive?.longOrNull ?: 0L

    private fun JsonObject.intField(key: String): Int =
        this[key]?.jsonPrimitive?.intOrNull ?: 0

    companion object {
        const val DEFAULT_DIR_NAME = "ml_health"

        /**
         * Rolling window for [shouldThrottleBinds]. Once
         * [DEATH_COUNT_THRESHOLD] deaths fall inside this window,
         * external binds are rejected with `SERVICE_THROTTLED` until
         * the window naturally expires.
         */
        const val THROTTLE_WINDOW_MS: Long = 60_000L

        /**
         * Stable-uptime decay threshold consulted by
         * [recordHealthyBoot]. After a service has been alive past this
         * since the last recorded death, the counter resets so a long-
         * lived healthy run starts every boot from a clean slate.
         */
        const val HEALTHY_UPTIME_DECAY_MS: Long = 300_000L

        /**
         * Number of abnormal deaths inside [THROTTLE_WINDOW_MS] that
         * trips the throttle. Three is generous enough for two binder
         * deaths from a flaky native init while still catching a real
         * crash-loop on the third reload.
         */
        const val DEATH_COUNT_THRESHOLD: Int = 3
    }
}
