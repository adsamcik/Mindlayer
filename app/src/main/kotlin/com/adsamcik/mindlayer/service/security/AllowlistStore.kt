package com.adsamcik.mindlayer.service.security

import android.content.Context
import androidx.annotation.VisibleForTesting
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.util.concurrent.ConcurrentHashMap

data class AllowlistEntry(
    val packageName: String,
    val signingCertSha256: String,
    val grantedAtMs: Long,
    val displayName: String? = null,
)

/**
 * Pending caller-approval row.
 *
 * - [previousSigSha256] (F-032): set when a package previously approved
 *   under a *different* signing certificate is now requesting approval again
 *   with a new sig. Triggers the cert-rotation banner in the UI. `null`
 *   means first-time approval (no prior trust). The field is additive in
 *   `pending.json` (`prevSig`) and round-trips via `optString` reads, so
 *   older on-disk files remain compatible.
 */
data class PendingApproval(
    val packageName: String,
    val signingCertSha256: String,
    val firstRequestedAtMs: Long,
    val displayName: String? = null,
    val previousSigSha256: String? = null,
)

/**
 * Thrown by [AllowlistStore.approve] when the live signing certificate for
 * the target package no longer matches the sig the user saw in the dashboard
 * row. F-031 — closes the TOCTOU window between display and tap.
 */
class CertificateMismatchException(
    val pkg: String,
    val expectedSig: String,
    val liveSig: String,
) : SecurityException(
    "Signing certificate for $pkg changed since approval was requested " +
        "(expected=${expectedSig.take(8)}…, live=${liveSig.take(8)}…)",
)

/**
 * File-backed allowlist of caller packages. Entries are keyed by `packageName`
 * and include the pinned signing-cert SHA-256 at approval time — a re-signed
 * package is implicitly rejected.
 *
 * ### Why not SharedPreferences?
 *
 * The dashboard UI lives in the main process while the AIDL service lives in
 * `:ml`. `SharedPreferences` caches state per-process and does not invalidate
 * on external file mutation (MODE_MULTI_PROCESS is deprecated and racy). If
 * the user approves a caller in the dashboard, the service process wouldn't
 * see the update until its own cache was invalidated.
 *
 * This implementation instead stores entries as a single JSON file in
 * `filesDir` and re-reads the file on every [isAllowed] check (the hot path
 * for external callers is at most 60 RPM by default, so disk cost is
 * negligible). Writes are serialised across processes with a [FileLock] on a
 * sidecar `.lock` file. The [entries] / [pending] StateFlows are maintained
 * per-process for UI observation and refreshed via [refresh].
 */
class AllowlistStore(
    context: Context,
    dirName: String = DEFAULT_DIR_NAME,
) {
    private val baseDir: File = File(context.applicationContext.filesDir, dirName).also {
        it.mkdirs()
    }
    private val entriesFile: File = File(baseDir, "entries.json")
    private val pendingFile: File = File(baseDir, "pending.json")
    private val lockFile: File = File(baseDir, "allowlist.lock")

    private val _entries = MutableStateFlow(readEntries())
    val entries: StateFlow<List<AllowlistEntry>> = _entries.asStateFlow()

    private val _pending = MutableStateFlow(readPending())
    val pending: StateFlow<List<PendingApproval>> = _pending.asStateFlow()

    /**
     * F-033: per-process in-memory dedup keyed by `(pkg, sig)`. A hostile
     * caller that already has a pending row should not be able to re-acquire
     * the FileLock + re-fsync the JSON on every blocked request. Lives only
     * in `:ml` (the dashboard process never calls [recordPending]).
     */
    private val recentPendingDedup = ConcurrentHashMap<DedupKey, Long>()

    /**
     * Fast path — always reads from disk so a dashboard approval in the main
     * process is visible to the `:ml` service's next check. File I/O cost is
     * tolerable since the caller-authorization path is already rate-limited.
     */
    fun isAllowed(pkg: String, sigSha256: String): Boolean {
        val entry = readEntries().firstOrNull { it.packageName == pkg } ?: return false
        return entry.signingCertSha256.equals(sigSha256, ignoreCase = true)
    }

    fun list(): List<AllowlistEntry> = readEntries().also { _entries.value = it }

    fun listPending(): List<PendingApproval> = readPending().also { _pending.value = it }

    /** Re-read both files and update the StateFlows. Call this in dashboard pollers. */
    fun refresh() {
        _entries.value = readEntries()
        _pending.value = readPending()
    }

    /**
     * F-031: production approval entry point — re-verifies the live signing
     * certificate against [expectedSigSha256] under the file lock and writes
     * the entry only if it matches. Closes the TOCTOU window between
     * dashboard render and the user's tap.
     *
     * @throws CertificateMismatchException if the live signer disagrees with
     *   what the user saw on screen.
     * @throws SecurityException if the package is no longer installed or its
     *   signing info cannot be resolved.
     */
    fun approve(
        context: Context,
        pkg: String,
        expectedSigSha256: String,
        displayName: String? = null,
    ) {
        withFileLock {
            val live = CallerVerifier.identifyByPackage(context, pkg)
                ?: throw SecurityException("Package $pkg no longer installed or signer unresolved")
            if (!live.signingCertSha256.equals(expectedSigSha256, ignoreCase = true)) {
                throw CertificateMismatchException(
                    pkg = pkg,
                    expectedSig = expectedSigSha256,
                    liveSig = live.signingCertSha256,
                )
            }
            val sanitized = CallerVerifier.sanitizeLabel(live.displayName ?: displayName)
            writeApprovalLocked(pkg, live.signingCertSha256, sanitized)
        }
    }

    /**
     * F-031 / data-layer: direct write of an approval entry without sig
     * re-verify. **Production code must use the [approve] overload that
     * takes a [Context]** — this entry point is intended for tests and
     * recovery paths that already hold the verified identity.
     */
    @VisibleForTesting
    internal fun approveDirect(pkg: String, sigSha256: String, displayName: String? = null) {
        withFileLock {
            writeApprovalLocked(pkg, sigSha256, CallerVerifier.sanitizeLabel(displayName))
        }
    }

    private fun writeApprovalLocked(pkg: String, sigSha256: String, displayName: String?) {
        val now = System.currentTimeMillis()
        val current = readEntries()
        val updated = current.filterNot { it.packageName == pkg } +
            AllowlistEntry(pkg, sigSha256, now, displayName)
        writeEntries(updated)
        _entries.value = updated

        // F-031: only remove the matching pending row. Sig-swap pending rows
        // (a different sig on the same pkg) are kept so the user can still
        // see them in the dashboard.
        val pendingUpdated = readPending().filterNot {
            it.packageName == pkg && it.signingCertSha256.equals(sigSha256, ignoreCase = true)
        }
        writePending(pendingUpdated)
        _pending.value = pendingUpdated
        // Drop any dedup entries for this pkg — a fresh approval cycle
        // should be observable again.
        recentPendingDedup.keys.removeIf { it.pkg == pkg }
    }

    fun revoke(pkg: String) {
        withFileLock {
            val current = readEntries()
            val updated = current.filterNot { it.packageName == pkg }
            if (updated.size == current.size) return@withFileLock
            writeEntries(updated)
            _entries.value = updated
        }
    }

    /**
     * Record a caller that attempted to connect but is not on the allowlist.
     * Used by the dashboard UI so the user can approve/deny.
     *
     * F-031: append-only across cert mismatches — if a previously-pending
     * package now requests approval with a *different* sig, both rows are
     * kept so the user can see the sig change.
     *
     * F-032: the new row carries [PendingApproval.previousSigSha256] when an
     * already-approved entry exists for the same package under a different
     * sig — the UI uses that to render the cert-rotation banner.
     *
     * F-033: short-circuits via an in-memory dedup TTL keyed by `(pkg, sig)`
     * so a hammering caller does not even reacquire the FileLock; capped at
     * [MAX_PENDING_ROWS] (FIFO) to bound on-disk growth.
     */
    fun recordPending(pkg: String, sigSha256: String, displayName: String? = null) {
        val key = DedupKey(pkg, sigSha256.lowercase())
        val now = System.currentTimeMillis()
        val prev = recentPendingDedup[key]
        if (prev != null && now - prev < DEDUP_TTL_MS) return
        recentPendingDedup[key] = now
        if (recentPendingDedup.size > DEDUP_MAP_SOFT_CAP) {
            recentPendingDedup.entries.removeIf { now - it.value > DEDUP_TTL_MS }
        }

        withFileLock {
            // F-054 (related): re-check entries.json under the lock — if the
            // pkg is already approved with this sig, skip writing a pending
            // row at all.
            val approved = readEntries().firstOrNull { it.packageName == pkg }
            if (approved != null && approved.signingCertSha256.equals(sigSha256, ignoreCase = true)) {
                return@withFileLock
            }

            val current = readPending()
            // Exact dup — same (pkg, sig) already pending — no-op.
            if (current.any {
                    it.packageName == pkg &&
                        it.signingCertSha256.equals(sigSha256, ignoreCase = true)
                }
            ) {
                return@withFileLock
            }

            // F-032: was this pkg previously approved under a different sig?
            val prevSig = approved
                ?.signingCertSha256
                ?.takeIf { !it.equals(sigSha256, ignoreCase = true) }

            // F-031 + F-033: append (no overwrite of prior sig rows for this
            // pkg) and FIFO-cap at MAX_PENDING_ROWS.
            val sanitized = CallerVerifier.sanitizeLabel(displayName)
            val appended = current + PendingApproval(
                packageName = pkg,
                signingCertSha256 = sigSha256,
                firstRequestedAtMs = now,
                displayName = sanitized,
                previousSigSha256 = prevSig,
            )
            val capped = if (appended.size > MAX_PENDING_ROWS) {
                appended.subList(appended.size - MAX_PENDING_ROWS, appended.size)
            } else {
                appended
            }
            writePending(capped)
            _pending.value = capped
        }
    }

    fun denyPending(pkg: String) {
        withFileLock {
            val current = readPending()
            val updated = current.filterNot { it.packageName == pkg }
            if (updated.size == current.size) return@withFileLock
            writePending(updated)
            _pending.value = updated
            recentPendingDedup.keys.removeIf { it.pkg == pkg }
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
                    try { lock.release() } catch (_: Throwable) { }
                }
            }
        }
    }

    private fun writeEntries(list: List<AllowlistEntry>) {
        val array = JSONArray()
        for (e in list) {
            array.put(JSONObject().apply {
                put("pkg", e.packageName)
                put("sig", e.signingCertSha256)
                put("grantedAtMs", e.grantedAtMs)
                e.displayName?.let { put("displayName", it) }
            })
        }
        atomicWrite(entriesFile, array.toString())
    }

    private fun writePending(list: List<PendingApproval>) {
        val array = JSONArray()
        for (e in list) {
            array.put(JSONObject().apply {
                put("pkg", e.packageName)
                put("sig", e.signingCertSha256)
                put("firstRequestedAtMs", e.firstRequestedAtMs)
                e.displayName?.let { put("displayName", it) }
                e.previousSigSha256?.let { put("prevSig", it) }
            })
        }
        atomicWrite(pendingFile, array.toString())
    }

    /**
     * F-025: atomic write with fsync.
     *
     * Writes to a `.tmp` sibling, fsyncs the contents, then renames over
     * the target with [java.nio.file.StandardCopyOption.ATOMIC_MOVE].
     * This guarantees that a crash mid-write cannot leave a half-written
     * file that subsequently parses as an empty allowlist (silent
     * revocation of every approval).
     *
     * If the platform refuses ATOMIC_MOVE (e.g. cross-filesystem rename
     * on some emulators), we fall back to a plain rename — but never to
     * "write directly to target" because that is the failure mode we are
     * defending against.
     */
    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            // Write + fsync the bytes before any rename.
            java.io.FileOutputStream(tmp).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                try { fos.fd.sync() } catch (_: Throwable) { /* fsync best effort */ }
            }
            try {
                java.nio.file.Files.move(
                    tmp.toPath(),
                    target.toPath(),
                    java.nio.file.StandardCopyOption.ATOMIC_MOVE,
                    java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
                if (!tmp.renameTo(target)) {
                    // Last-resort REPLACE_EXISTING without atomicity. We
                    // accept this only after ATOMIC_MOVE explicitly
                    // failed; we still avoid the "write directly to
                    // target" anti-pattern.
                    java.nio.file.Files.move(
                        tmp.toPath(),
                        target.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                    )
                }
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
    }

    private fun readEntries(): List<AllowlistEntry> {
        if (!entriesFile.exists()) return emptyList()
        val raw = try { entriesFile.readText() } catch (_: IOException) { return emptyList() }
        if (raw.isEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        AllowlistEntry(
                            packageName = o.getString("pkg"),
                            signingCertSha256 = o.getString("sig"),
                            grantedAtMs = o.optLong("grantedAtMs", 0L),
                            displayName = o.optString("displayName").ifEmpty { null },
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun readPending(): List<PendingApproval> {
        if (!pendingFile.exists()) return emptyList()
        val raw = try { pendingFile.readText() } catch (_: IOException) { return emptyList() }
        if (raw.isEmpty()) return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        PendingApproval(
                            packageName = o.getString("pkg"),
                            signingCertSha256 = o.getString("sig"),
                            firstRequestedAtMs = o.optLong("firstRequestedAtMs", 0L),
                            displayName = o.optString("displayName").ifEmpty { null },
                            previousSigSha256 = o.optString("prevSig").ifEmpty { null },
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private data class DedupKey(val pkg: String, val sig: String)

    companion object {
        const val DEFAULT_DIR_NAME = "mindlayer_allowlist"

        /**
         * F-033: cap pending-approval rows to bound disk growth on a flooder.
         * FIFO eviction: oldest rows fall off first, so a real user-initiated
         * pending request stays at the top of the list.
         */
        const val MAX_PENDING_ROWS = 32

        /** F-033: in-memory dedup TTL. Per-process; lives in `:ml`. */
        @VisibleForTesting
        internal const val DEDUP_TTL_MS: Long = 30_000L

        private const val DEDUP_MAP_SOFT_CAP = 256
    }
}
