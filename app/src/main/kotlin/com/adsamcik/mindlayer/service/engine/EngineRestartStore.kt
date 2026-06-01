package com.adsamcik.mindlayer.service.engine

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Persisted "please re-init the engine on the next process start" intent.
 *
 * Background: LiteRT-LM bug
 * [#2028](https://github.com/google-ai-edge/LiteRT-LM/issues/2028) means
 * `Engine.close()` followed by a fresh `Engine()` in the same process
 * SIGSEGVs the second time. Gemma 4 E2B on CPU + an isolated service
 * process (`android:process=":ml"`) is the documented trigger; we hit it
 * reproducibly during multimodal bag-photo scans.
 *
 * To recover from thermal-driven backend switches and memory-pressure
 * unloads WITHOUT in-process Engine recreation, [EngineManager] now
 * records a restart intent here, drains in-flight work, and calls
 * `Process.killProcess(myPid())`. The service is automatically restarted
 * by Android on the next bind; the new process reads the intent from
 * this store and starts engine warmup against the recorded target
 * backend, then [clear]s the intent on successful init.
 *
 * ### Why not SharedPreferences
 *
 * Per the repo convention documented in `AllowlistStore`,
 * `MlHealthRecorder`, `DbKeyProvider`, and `OcrAcceleratorFailureCache`:
 * `SharedPreferences` caches state per-process and does not invalidate
 * on external file mutation. The dashboard UI process needs to read this
 * state to render any "engine restart pending" banner, while the `:ml`
 * service process writes the record. `filesDir` + atomic-rename +
 * [FileLock] is the cross-process-coherent pattern this repo uses.
 *
 * ### Loop prevention
 *
 * Each [record] increments [RestartIntent.attemptCount] if the previous
 * intent (still on disk because the prior restart's engine init also
 * failed before [clear]ing) targets the same backend. [consume] returns
 * `null` once [attemptCount] reaches [MAX_RESTART_ATTEMPTS] — the service
 * then falls back to the default backend chain (GPU → CPU) without
 * burning further restart cycles on a wedged target.
 *
 * ### Privacy
 *
 * The on-disk record carries the reason string, the target backend, the
 * `maxTokens` budget, and a timestamp. No prompt text, no model output,
 * no `Throwable.message` content.
 */
class EngineRestartStore @VisibleForTesting internal constructor(
    private val baseDir: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    constructor(
        context: Context,
        clock: () -> Long = { System.currentTimeMillis() },
    ) : this(
        baseDir = File(context.applicationContext.filesDir, DEFAULT_DIR_NAME),
        clock = clock,
    )

    init {
        runCatching { baseDir.mkdirs() }
    }

    private val stateFile: File = File(baseDir, STATE_FILE_NAME)
    private val lockFile: File = File(baseDir, LOCK_FILE_NAME)

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Returns the on-disk restart intent, or `null` if no intent is
     * recorded, the file is unreadable / malformed / from a future schema
     * version, or the intent has reached [MAX_RESTART_ATTEMPTS]. Always
     * re-reads from disk so a cross-process reader (the main-process
     * dashboard) sees the latest value.
     */
    fun peek(): RestartIntent? = readSnapshotUnlocked()?.takeIf { it.attemptCount < MAX_RESTART_ATTEMPTS }

    /**
     * Read + clear the recorded intent in one atomic step. Returns `null`
     * if no intent is present or it has already reached the attempt cap.
     * Callers should invoke this exactly once during service startup,
     * BEFORE engine warmup, and pass the returned [RestartIntent.targetBackend]
     * to [EngineManager.initialize] as the preferred backend.
     */
    fun consume(): RestartIntent? {
        var consumed: RestartIntent? = null
        try {
            withFileLock {
                val existing = readSnapshotLocked() ?: return@withFileLock
                if (existing.attemptCount >= MAX_RESTART_ATTEMPTS) {
                    // Honour the loop guard: clear without returning so the
                    // service falls back to the default backend chain.
                    deleteStateFileLocked()
                    return@withFileLock
                }
                consumed = existing
                deleteStateFileLocked()
            }
        } catch (t: Throwable) {
            MindlayerLog.w(
                TAG,
                "Engine restart-store consume failed: ${t.safeLabel()}",
                throwable = null,
            )
        }
        return consumed
    }

    /**
     * Persist a new restart intent. If an intent already exists for the
     * same [targetBackend], [RestartIntent.attemptCount] is incremented;
     * otherwise the record is replaced with a fresh one (count = 1).
     * Returns the persisted intent.
     *
     * Writes are wrapped in a broad catch — a store write failure must
     * never block the [Process.killProcess] caller from proceeding. If
     * persistence fails, the post-restart engine starts on the default
     * backend chain, which is the worst-case fallback and is safe.
     *
     * `reason` is a short opaque label (e.g. `"thermal_switch"`,
     * `"memory_pressure"`, `"manual"`) — it must not contain prompt text
     * or user content. The caller is responsible for redaction; this
     * method does NOT inspect the string.
     */
    fun record(
        reason: String,
        targetBackend: String?,
        maxTokens: Int,
    ): RestartIntent? {
        var written: RestartIntent? = null
        try {
            withFileLock {
                val now = clock()
                val existing = readSnapshotLocked()
                val nextCount = if (existing != null && existing.targetBackend == targetBackend) {
                    (existing.attemptCount + 1).coerceAtMost(Int.MAX_VALUE)
                } else {
                    1
                }
                val intent = RestartIntent(
                    schemaVersion = CURRENT_SCHEMA_VERSION,
                    reason = reason,
                    targetBackend = targetBackend,
                    maxTokens = maxTokens,
                    recordedAtMs = now,
                    attemptCount = nextCount,
                )
                writeSnapshotLocked(intent)
                written = intent
            }
        } catch (t: Throwable) {
            MindlayerLog.w(
                TAG,
                "Engine restart-store record failed: ${t.safeLabel()}",
                throwable = null,
            )
        }
        return written
    }

    /**
     * Force-clear the persisted intent without consuming it. Useful from
     * a test seam or from a dashboard "cancel pending restart" affordance.
     */
    fun clear() {
        try {
            withFileLock { deleteStateFileLocked() }
        } catch (t: Throwable) {
            MindlayerLog.w(
                TAG,
                "Engine restart-store clear failed: ${t.safeLabel()}",
                throwable = null,
            )
        }
    }

    // ---- Persistence -----------------------------------------------------

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

    private fun deleteStateFileLocked() {
        if (stateFile.exists() && !stateFile.delete()) {
            // Best-effort overwrite-then-delete in case the OS held the
            // file open. An empty file is preferable to a stale record.
            try { stateFile.writeText("") } catch (_: Throwable) { /* best-effort */ }
        }
    }

    private fun readSnapshotLocked(): RestartIntent? = readSnapshotUnlocked()

    private fun readSnapshotUnlocked(): RestartIntent? {
        if (!stateFile.exists()) return null
        val raw = try {
            stateFile.readText()
        } catch (_: IOException) {
            return null
        }
        if (raw.isBlank()) return null
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            val version = obj["schemaVersion"]?.jsonPrimitive?.intOrNull
            if (version != CURRENT_SCHEMA_VERSION) return null
            val reason = obj["reason"]?.jsonPrimitive?.contentOrNull ?: return null
            val target = obj["targetBackend"]?.jsonPrimitive?.contentOrNull
            val maxTokens = obj["maxTokens"]?.jsonPrimitive?.intOrNull ?: return null
            val ts = obj["recordedAtMs"]?.jsonPrimitive?.longOrNull ?: return null
            val attempts = obj["attemptCount"]?.jsonPrimitive?.intOrNull ?: 1
            RestartIntent(
                schemaVersion = version,
                reason = reason,
                targetBackend = target,
                maxTokens = maxTokens,
                recordedAtMs = ts,
                attemptCount = attempts,
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeSnapshotLocked(intent: RestartIntent) {
        val obj: JsonObject = buildJsonObject {
            // schemaVersion first per the documented forward-compat contract.
            put("schemaVersion", intent.schemaVersion)
            put("reason", intent.reason)
            intent.targetBackend?.let { put("targetBackend", it) }
            put("maxTokens", intent.maxTokens)
            put("recordedAtMs", intent.recordedAtMs)
            put("attemptCount", intent.attemptCount)
        }
        atomicWrite(stateFile, obj.toString())
    }

    /**
     * Atomic-rename + fsync write. Mirrors `OcrAcceleratorFailureCache.atomicWrite`.
     * A crash mid-write must never leave a half-written file that parses
     * as "no intent" — the caller would then init the wrong backend and
     * never get a second chance at the intended one.
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

    /**
     * On-disk restart intent. `schemaVersion` is included in the JSON so
     * a future v2 reader can reject older payloads without parsing the
     * rest; the v1 reader equivalently rejects v2 payloads.
     */
    data class RestartIntent(
        val schemaVersion: Int,
        /** Short opaque reason label (e.g. `"thermal_switch"`, `"memory_pressure"`). */
        val reason: String,
        /** Target backend to prefer on the post-restart init; `null` = default chain. */
        val targetBackend: String?,
        /** KV-cache budget to apply on the post-restart init. */
        val maxTokens: Int,
        /** Wall-clock ms when the intent was recorded. */
        val recordedAtMs: Long,
        /**
         * Number of consecutive restart attempts toward [targetBackend].
         * [consume] returns `null` once this hits [MAX_RESTART_ATTEMPTS].
         */
        val attemptCount: Int,
    )

    companion object {
        private const val TAG = "EngineRestartStore"
        const val DEFAULT_DIR_NAME = "engine_restart"
        const val STATE_FILE_NAME = "restart_intent.json"
        const val LOCK_FILE_NAME = "restart_intent.lock"

        /**
         * On-disk schema version. Bumping requires either (a) a migration
         * from the older format or (b) accepting that older processes
         * will see the file as "no intent" until they upgrade.
         */
        const val CURRENT_SCHEMA_VERSION = 1

        /**
         * Maximum consecutive restart attempts toward the same target
         * backend before [consume] starts returning `null` (silent
         * fallback to the default backend chain). Three is enough to
         * cover transient init failures while preventing an infinite
         * restart loop on a wedged backend (e.g. broken NPU driver).
         */
        const val MAX_RESTART_ATTEMPTS = 3
    }
}
