package com.adsamcik.mindlayer.service.engine

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.AtomicMoveNotSupportedException

/**
 * Persisted cache of the most recent non-CPU OCR accelerator init failure.
 *
 * Background: every Mindlayer `:ml` cold-start and every memory-pressure
 * unload+reload of the OCR engine tries the resolver-picked accelerator
 * (typically GPU) first; the sister graceful-fallback path then retries
 * with CPU when init throws. On devices where the GPU delegate consistently
 * fails to compile the PP-OCRv5 mobile model (no OpenCL, missing op support,
 * insufficient driver, etc.) this wastes 1-2s of guaranteed-doomed GPU
 * init + battery on every reload.
 *
 * This cache remembers the most recent failure (backend + safe label + age)
 * and lets [LiteRtPaddleOcrBackend.initialize] short-circuit straight to
 * CPU during the [cooldownMs] window. Successful init on the formerly-failing
 * backend clears the record so a transient failure is not sticky for the
 * full cooldown.
 *
 * ### Why not SharedPreferences?
 *
 * The dashboard UI lives in the main process while the AIDL service lives
 * in `:ml`. Both processes need to read this state (the dashboard renders
 * the cooldown banner; `:ml` writes the failure record). `SharedPreferences`
 * caches state per-process and does not invalidate on external file
 * mutation. This implementation uses the same `filesDir` + atomic-rename +
 * [FileLock] pattern as `AllowlistStore` and `MlHealthRecorder` — the
 * documented repo convention for any state under `filesDir` that more than
 * one process may touch.
 *
 * ### Privacy
 *
 * The on-disk file contains NO recognized text and NO `Throwable.message`
 * — only the [safeLabel] string (exception class chain). This matches the
 * redaction discipline of every `MindlayerLog` call on the inference path.
 *
 * ### Schema
 *
 * `version` is intentionally first in the JSON object so a future v2 reader
 * can short-circuit before parsing the rest of the document. A v1 reader
 * that encounters a `schemaVersion` it does not know returns `null` from
 * [snapshot] (forward-compat: a newer service that has written v2 will not
 * be misread as v1 data by an older process).
 *
 * @param cooldownMs the duration after a recorded failure during which
 *   non-CPU init attempts will be skipped. Single named constant by design
 *   — bumping it is a code change. Default: 24 h.
 */
class OcrAcceleratorFailureCache @VisibleForTesting internal constructor(
    private val baseDir: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
    val cooldownMs: Long = DEFAULT_COOLDOWN_MS,
) {

    constructor(
        context: Context,
        clock: () -> Long = { System.currentTimeMillis() },
        cooldownMs: Long = DEFAULT_COOLDOWN_MS,
    ) : this(
        baseDir = File(context.applicationContext.filesDir, DEFAULT_DIR_NAME),
        clock = clock,
        cooldownMs = cooldownMs,
    )

    init {
        runCatching { baseDir.mkdirs() }
    }

    private val stateFile: File = File(baseDir, STATE_FILE_NAME)
    private val lockFile: File = File(baseDir, LOCK_FILE_NAME)

    private val json = Json { ignoreUnknownKeys = true }

    private val _snapshot = MutableStateFlow(readSnapshotUnlocked())

    /**
     * Observable snapshot of the most recent failure record, or `null` if no
     * failure is currently cached. Updated in-process by [recordFailure] and
     * [clear]; cross-process observers should call [snapshot] (which always
     * reads from disk) rather than relying on this Flow.
     */
    val snapshotFlow: StateFlow<FailureRecord?> = _snapshot.asStateFlow()

    /**
     * Returns the on-disk failure record, or `null` if no failure is cached
     * or the file is unreadable / malformed / from a future schema version.
     *
     * Always re-reads from disk so a cross-process reader (the main-process
     * dashboard reading state written by `:ml`) sees the latest value.
     * Atomic-rename guarantees a torn read is impossible.
     */
    fun snapshot(): FailureRecord? {
        val fresh = readSnapshotUnlocked()
        if (fresh != _snapshot.value) {
            _snapshot.value = fresh
        }
        return fresh
    }

    /**
     * True iff a non-CPU backend failure is recorded AND its age is below
     * [cooldownMs]. CPU records are never returned by [recordFailure], so in
     * practice this collapses to "an unexpired record exists".
     */
    fun isInCooldown(): Boolean {
        val record = snapshot() ?: return false
        return record.isInCooldown(clock = clock, cooldownMs = cooldownMs)
    }

    /**
     * Persist a new failure. If a record already exists for the same backend,
     * [FailureRecord.failureCount] is incremented; otherwise the record is
     * replaced with a fresh one (count = 1). Writes are wrapped in a broad
     * catch — a cache-file write failure must NEVER block engine init.
     *
     * `safeLabel` must come from [Throwable.safeLabel] — the class-name chain
     * with no `message` content. The caller is responsible for redaction;
     * this method does NOT inspect the string. Callers that pass a raw
     * `Throwable.message` are introducing a privacy violation.
     */
    fun recordFailure(backend: String, safeLabel: String) {
        if (backend == "CPU") {
            // CPU is the last-resort backend — skipping it via cooldown would
            // disable OCR entirely. Defensive guard: never let a CPU failure
            // poison the cache, even if a caller mis-wires this method.
            MindlayerLog.w(
                TAG,
                "OCR accelerator failure cache ignored CPU record (would disable OCR)",
                throwable = null,
            )
            return
        }
        try {
            withFileLock {
                val now = clock()
                val existing = readSnapshotLocked()
                val nextCount = if (existing != null && existing.lastFailedBackend == backend) {
                    existing.failureCount + 1
                } else {
                    1
                }
                val record = FailureRecord(
                    schemaVersion = CURRENT_SCHEMA_VERSION,
                    lastFailedBackend = backend,
                    lastFailedAtMs = now,
                    lastFailedSafeLabel = safeLabel,
                    failureCount = nextCount,
                )
                writeSnapshotLocked(record)
                _snapshot.value = record
            }
        } catch (t: Throwable) {
            MindlayerLog.w(
                TAG,
                "OCR accelerator failure cache write failed: ${t.safeLabel()}",
                throwable = null,
            )
        }
    }

    /**
     * Clear the cached failure record. Called after a successful non-CPU
     * init (so a transient failure is not sticky for [cooldownMs]) or from
     * the dashboard "Retry now" button. Wrapped in the same broad catch as
     * [recordFailure].
     */
    fun clear() {
        try {
            withFileLock {
                if (stateFile.exists() && !stateFile.delete()) {
                    // Best-effort overwrite-then-delete in case the OS held the
                    // file open. Writing an empty file is preferable to leaving
                    // a stale record on disk.
                    stateFile.writeText("")
                }
                _snapshot.value = null
            }
        } catch (t: Throwable) {
            MindlayerLog.w(
                TAG,
                "OCR accelerator failure cache clear failed: ${t.safeLabel()}",
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

    private fun readSnapshotLocked(): FailureRecord? = readSnapshotUnlocked()

    private fun readSnapshotUnlocked(): FailureRecord? {
        if (!stateFile.exists()) return null
        val raw = try {
            stateFile.readText()
        } catch (_: IOException) {
            return null
        }
        if (raw.isBlank()) return null
        return try {
            val obj = json.parseToJsonElement(raw).jsonObject
            // Read version FIRST so forward-incompatible schemas can be
            // rejected without touching the rest of the payload.
            val version = obj["schemaVersion"]?.jsonPrimitive?.intOrNull
            if (version != CURRENT_SCHEMA_VERSION) {
                return null
            }
            val backend = obj["lastFailedBackend"]?.jsonPrimitive?.contentOrNull ?: return null
            val ts = obj["lastFailedAtMs"]?.jsonPrimitive?.longOrNull ?: return null
            val label = obj["lastFailedSafeLabel"]?.jsonPrimitive?.contentOrNull ?: return null
            val count = obj["failureCount"]?.jsonPrimitive?.intOrNull ?: 1
            FailureRecord(
                schemaVersion = version,
                lastFailedBackend = backend,
                lastFailedAtMs = ts,
                lastFailedSafeLabel = label,
                failureCount = count,
            )
        } catch (_: Throwable) {
            null
        }
    }

    private fun writeSnapshotLocked(record: FailureRecord) {
        val obj: JsonObject = buildJsonObject {
            // schemaVersion first per the documented forward-compat contract.
            put("schemaVersion", record.schemaVersion)
            put("lastFailedBackend", record.lastFailedBackend)
            put("lastFailedAtMs", record.lastFailedAtMs)
            put("lastFailedSafeLabel", record.lastFailedSafeLabel)
            put("failureCount", record.failureCount)
        }
        atomicWrite(stateFile, obj.toString())
    }

    /**
     * Atomic-rename + fsync write — mirrors `MlHealthRecorder.atomicWrite`
     * and `AllowlistStore.atomicWrite`. A crash mid-write must never leave a
     * half-written file that parses as "no record" (silent cooldown reset).
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
     * On-disk failure record. `schemaVersion` is included in the JSON so a
     * future v2 reader can reject older payloads without parsing the rest;
     * the v1 reader equivalently rejects v2 payloads with this field set
     * to anything other than [CURRENT_SCHEMA_VERSION].
     */
    data class FailureRecord(
        val schemaVersion: Int,
        val lastFailedBackend: String,
        val lastFailedAtMs: Long,
        val lastFailedSafeLabel: String,
        val failureCount: Int,
    ) {
        fun isInCooldown(clock: () -> Long, cooldownMs: Long): Boolean {
            val now = clock()
            // Guard against clock going backwards (NTP adjust, manual reset):
            // age < 0 means the failure is "in the future" — treat as expired
            // rather than honour an indefinite cooldown.
            val age = now - lastFailedAtMs
            return age in 0 until cooldownMs
        }
    }

    companion object {
        private const val TAG = "OcrAcceleratorFailureCache"
        const val DEFAULT_DIR_NAME = "ocr_accelerator"
        const val STATE_FILE_NAME = "accelerator_failure.json"
        const val LOCK_FILE_NAME = "accelerator_failure.lock"

        /** Cooldown after a recorded failure — 24 h. */
        const val DEFAULT_COOLDOWN_MS = 24L * 60L * 60L * 1000L

        /**
         * On-disk schema version. Bumping requires either (a) a migration
         * from the older format or (b) accepting that older processes will
         * see the file as "no record" until they upgrade.
         */
        const val CURRENT_SCHEMA_VERSION = 1
    }
}
