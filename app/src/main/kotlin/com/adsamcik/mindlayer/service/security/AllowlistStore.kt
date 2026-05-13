package com.adsamcik.mindlayer.service.security

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.nio.file.Files
import java.nio.file.LinkOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

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
 * A package that was explicitly denied (via [AllowlistStore.denyPending]) or
 * recently revoked. Suppresses re-creation of pending entries until [expiresAtMs].
 */
data class DeniedEntry(
    val packageName: String,
    val signingCertSha256: String,
    val deniedAtMs: Long,
    val expiresAtMs: Long,
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
    private val logRepository: LogRepository? = null,
) {
    private val appContext: Context = context.applicationContext
    private val baseDir: File = File(appContext.filesDir, dirName).also {
        it.mkdirs()
    }
    private val entriesFile: File = File(baseDir, "entries.json")
    private val pendingFile: File = File(baseDir, "pending.json")
    private val deniedFile: File = File(baseDir, "denied.json")
    private val lockFile: File = File(baseDir, "allowlist.lock")
    private val hmacKeyFile: File = File(baseDir, "allowlist.hmac")

    /**
     * Tracks how many times the current thread is inside [withFileLock]. Java NIO
     * [java.nio.channels.FileChannel.lock] does NOT support same-JVM re-entry on the
     * same file region — a nested call throws [java.nio.channels.OverlappingFileLockException].
     * [loadOrCreateHmacKey] uses this depth to skip the inner lock acquisition when
     * it is invoked transitively from code that already holds the lock.
     *
     * Must be declared before [_entries] / [_pending] so it is initialised before
     * the property initialisers call [readEntries] / [readPending], which transitively
     * call [loadOrCreateHmacKey].
     */
    private val fileLockDepth = ThreadLocal.withInitial { 0 }

    private val _entries = MutableStateFlow(readEntries())
    val entries: StateFlow<List<AllowlistEntry>> = _entries.asStateFlow()

    private val _pending = MutableStateFlow(readPending())
    val pending: StateFlow<List<PendingApproval>> = _pending.asStateFlow()

    init {
        withFileLock {
            migrateLegacyArray(entriesFile, ENTRIES_KEY)
            migrateLegacyArray(pendingFile, PENDING_KEY)
        }
        refresh()
    }

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

    /**
     * Seeds co-signed first-party callers only when the allowlist is empty.
     *
     * Each seed is verified against the currently installed APK signer before
     * insertion. If a package was previously denied/revoked, it is skipped so a
     * service restart cannot silently undo that decision.
     */
    fun seedIfEmpty(entries: List<AllowlistEntry>) {
        seedVerified(entries, requireEmpty = true, action = "seed_first_party")
    }

    /**
     * Seed verified installed packages while preserving explicit deny/revoke decisions.
     */
    internal fun seedVerified(entries: List<AllowlistEntry>, requireEmpty: Boolean, action: String) {
        if (entries.isEmpty()) return
        withFileLock {
            val current = readEntries()
            if (requireEmpty && current.isNotEmpty()) return@withFileLock
            val denied = readDeniedIncludingExpired()
            val now = System.currentTimeMillis()
            val seeded = current.toMutableList()
            var changed = false
            for (entry in entries) {
                if (denied.any { it.packageName == entry.packageName }) {
                    MindlayerLog.i(TAG, "Skipped allowlist seed for previously denied package ${entry.packageName}")
                    continue
                }
                val live = CallerVerifier.identifyByPackage(appContext, entry.packageName)
                if (live == null || !live.signingCertSha256.equals(entry.signingCertSha256, ignoreCase = true)) {
                    MindlayerLog.w(TAG, "Rejected allowlist seed for ${entry.packageName}: signer mismatch or package missing")
                    continue
                }
                val seededEntry = AllowlistEntry(
                    packageName = entry.packageName,
                    signingCertSha256 = live.signingCertSha256,
                    grantedAtMs = now,
                    displayName = CallerVerifier.sanitizeLabel(live.displayName ?: entry.displayName),
                )
                val existingIndex = seeded.indexOfFirst { it.packageName == seededEntry.packageName }
                if (existingIndex >= 0 && seeded[existingIndex].signingCertSha256.equals(seededEntry.signingCertSha256, ignoreCase = true)) {
                    continue
                }
                if (existingIndex >= 0) {
                    seeded[existingIndex] = seededEntry
                } else {
                    seeded += seededEntry
                }
                changed = true
                logRepository?.logSecurityDecision(
                    action = action,
                    packageName = seededEntry.packageName,
                    sigShaPrefix = seededEntry.signingCertSha256.take(8),
                )
            }
            if (changed) {
                writeEntries(seeded)
                _entries.value = seeded
            }
        }
    }

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
            // Clear any denial when explicitly approved.
            writeDenied(readDenied().filterNot { it.packageName == pkg })
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
            val target = current.firstOrNull { it.packageName == pkg } ?: return@withFileLock
            val updated = current.filterNot { it.packageName == pkg }
            writeEntries(updated)
            _entries.value = updated

            // Persist a denial so the revoked package cools down for one TTL.
            val now = System.currentTimeMillis()
            val deniedUpdated = readDenied().filterNot { it.packageName == pkg } +
                DeniedEntry(pkg, target.signingCertSha256, now, now + DENIAL_TTL_MS)
            writeDenied(deniedUpdated)
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
     *
     * Silently ignored if [pkg] is within its denial TTL or if [pkg] exceeds [MAX_PACKAGE_NAME_LENGTH].
     */
    fun recordPending(pkg: String, sigSha256: String, displayName: String? = null) {
        if (pkg.length > MAX_PACKAGE_NAME_LENGTH) return
        val key = DedupKey(pkg, sigSha256.lowercase())
        val now = System.currentTimeMillis()
        val prev = recentPendingDedup[key]
        if (prev != null && now - prev < DEDUP_TTL_MS) return
        recentPendingDedup[key] = now
        if (recentPendingDedup.size > DEDUP_MAP_SOFT_CAP) {
            recentPendingDedup.entries.removeIf { now - it.value > DEDUP_TTL_MS }
        }

        withFileLock {
            // Suppress if the package is within its denial TTL.
            val denied = readDenied()
            if (denied.any {
                    it.packageName == pkg &&
                        it.signingCertSha256.equals(sigSha256, ignoreCase = true) &&
                        it.expiresAtMs > now
                }) {
                return@withFileLock
            }

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
            val entry = current.firstOrNull { it.packageName == pkg } ?: return@withFileLock
            // Persist the denial with TTL so re-trigger is suppressed.
            val now = System.currentTimeMillis()
            val deniedUpdated = readDenied().filterNot { it.packageName == pkg } +
                DeniedEntry(pkg, entry.signingCertSha256, now, now + DENIAL_TTL_MS)
            writeDenied(deniedUpdated)
            val updatedPending = current.filterNot { it.packageName == pkg }
            writePending(updatedPending)
            _pending.value = updatedPending
            recentPendingDedup.keys.removeIf { it.pkg == pkg }
        }
    }

    /**
     * Returns true if [pkg]/[sigSha256] has been denied and the [DENIAL_TTL_MS] has not elapsed.
     */
    fun isDenied(pkg: String, sigSha256: String): Boolean {
        val now = System.currentTimeMillis()
        return readDenied().any {
            it.packageName == pkg &&
                it.signingCertSha256.equals(sigSha256, ignoreCase = true) &&
                it.expiresAtMs > now
        }
    }

    // ---- Persistence -----------------------------------------------------

    private inline fun <T> withFileLock(block: () -> T): T {
        RandomAccessFile(lockFile, "rw").use { raf ->
            raf.channel.use { ch ->
                val lock: FileLock = ch.lock()
                fileLockDepth.set((fileLockDepth.get() ?: 0) + 1)
                try {
                    return block()
                } finally {
                    fileLockDepth.set(((fileLockDepth.get() ?: 1) - 1).coerceAtLeast(0))
                    try { lock.release() } catch (_: Throwable) { }
                }
            }
        }
    }

    private fun writeEntries(list: List<AllowlistEntry>) {
        val array = JSONArray()
        for (e in list.sortedBy { it.packageName }) {
            array.put(JSONObject().apply {
                put("pkg", e.packageName)
                put("sig", e.signingCertSha256)
                put("grantedAtMs", e.grantedAtMs)
                e.displayName?.let { put("displayName", it) }
            })
        }
        atomicWrite(entriesFile, signedEnvelope(ENTRIES_KEY, array).toString())
    }

    private fun writePending(list: List<PendingApproval>) {
        val array = JSONArray()
        for (e in list.sortedBy { it.packageName }) {
            array.put(JSONObject().apply {
                put("pkg", e.packageName)
                put("sig", e.signingCertSha256)
                put("firstRequestedAtMs", e.firstRequestedAtMs)
                e.displayName?.let { put("displayName", it) }
                e.previousSigSha256?.let { put("prevSig", it) }
            })
        }
        atomicWrite(pendingFile, signedEnvelope(PENDING_KEY, array).toString())
    }

    private fun writeDenied(list: List<DeniedEntry>) {
        val now = System.currentTimeMillis()
        val pruned = list.filter { it.expiresAtMs > now }
        val array = JSONArray()
        for (e in pruned.sortedBy { it.packageName }) {
            array.put(JSONObject().apply {
                put("pkg", e.packageName)
                put("sig", e.signingCertSha256)
                put("deniedAtMs", e.deniedAtMs)
                put("expiresAtMs", e.expiresAtMs)
            })
        }
        atomicWrite(deniedFile, signedEnvelope(DENIED_KEY, array).toString())
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
        assertRegularFileIfExists(target)
        val tmp = File(target.parentFile, target.name + ".tmp")
        assertRegularFileIfExists(tmp)
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

    private fun assertRegularFileIfExists(file: File) {
        val path = file.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw SecurityException("Refusing to use non-regular allowlist file: ${file.absolutePath}")
        }
    }

    private fun isRegularFileForRead(file: File): Boolean {
        val path = file.toPath()
        val ok = Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        if (!ok) {
            MindlayerLog.w(TAG, "Rejected non-regular allowlist file ${file.name}")
        }
        return ok
    }
    private fun readEntries(): List<AllowlistEntry> {
        val array = readSignedArray(entriesFile, ENTRIES_KEY) ?: return emptyList()
        return try {
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
        val array = readSignedArray(pendingFile, PENDING_KEY) ?: return emptyList()
        return try {
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

    private fun readDeniedIncludingExpired(): List<DeniedEntry> {
        if (!deniedFile.exists()) return emptyList()
        if (!isRegularFileForRead(deniedFile)) return emptyList()
        val raw = try { deniedFile.readText() } catch (_: IOException) { return emptyList() }
        return try {
            val envelope = JSONObject(raw.trim())
            val version = envelope.optInt("version", -1)
            val array = envelope.optJSONArray(DENIED_KEY) ?: return emptyList()
            val mac = envelope.optString(MAC_KEY)
            if (version !in MIN_SUPPORTED_VERSION..SIGNED_FILE_VERSION ||
                !verifyMac(canonicalPayload(version, DENIED_KEY, array), mac)
            ) return emptyList()
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        DeniedEntry(
                            packageName = o.getString("pkg"),
                            signingCertSha256 = o.getString("sig"),
                            deniedAtMs = o.optLong("deniedAtMs", 0L),
                            expiresAtMs = o.optLong("expiresAtMs", 0L),
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }
    private fun readDenied(): List<DeniedEntry> {
        val array = readSignedArray(deniedFile, DENIED_KEY) ?: return emptyList()
        val now = System.currentTimeMillis()
        return try {
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    val expires = o.optLong("expiresAtMs", 0L)
                    if (expires > now) {
                        add(
                            DeniedEntry(
                                packageName = o.getString("pkg"),
                                signingCertSha256 = o.getString("sig"),
                                deniedAtMs = o.optLong("deniedAtMs", 0L),
                                expiresAtMs = expires,
                            )
                        )
                    }
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun readSignedArray(file: File, arrayKey: String): JSONArray? {
        if (!file.exists()) return null
        if (!isRegularFileForRead(file)) return null
        val raw = try { file.readText() } catch (_: IOException) { return null }
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return try {
            if (trimmed.startsWith("[")) {
                MindlayerLog.w(TAG, "Rejected unsigned legacy allowlist file ${file.name}")
                return null
            } else {
                val envelope = JSONObject(trimmed)
                val version = envelope.optInt("version", -1)
                if (version !in MIN_SUPPORTED_VERSION..SIGNED_FILE_VERSION) {
                    MindlayerLog.w(TAG, "Rejected unknown-version allowlist file ${file.name} (version=$version)")
                    return null
                }
                val array = envelope.optJSONArray(arrayKey) ?: return null
                val mac = envelope.optString(MAC_KEY)
                if (!verifyMac(canonicalPayload(version, arrayKey, array), mac)) {
                    MindlayerLog.w(TAG, "Rejected tampered allowlist file ${file.name}")
                    return null
                }
                array
            }
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Failed to parse allowlist file ${file.name}", throwable = t)
            null
        }
    }

    /**
     * Old behaviour silently re-signed any legacy unsigned array as if it were
     * trusted — that's a forgery primitive for an offline attacker who could
     * write to the data dir between releases (see security audit M7). New
     * behaviour: rename the file to `.legacy-rejected-<ts>` so the user must
     * re-approve callers via the dashboard. The renamed file is preserved for
     * forensic inspection but never read by the runtime.
     */
    private fun migrateLegacyArray(file: File, arrayKey: String) {
        if (!file.exists()) return
        if (!isRegularFileForRead(file)) return
        val raw = try { file.readText() } catch (_: IOException) { return }
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[")) return
        val ts = System.currentTimeMillis()
        val renamed = File(file.parentFile, "${file.name}.legacy-rejected-$ts")
        try {
            if (file.renameTo(renamed)) {
                MindlayerLog.w(TAG, "Renamed unsigned legacy allowlist ${file.name} to ${renamed.name}; user must re-approve callers")
            } else {
                file.delete()
                MindlayerLog.w(TAG, "Deleted unsigned legacy allowlist ${file.name} (rename failed)")
            }
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Failed to quarantine legacy allowlist file ${file.name}", throwable = t)
        }
    }

    private fun signedEnvelope(arrayKey: String, array: JSONArray): JSONObject {
        return JSONObject().apply {
            put("version", SIGNED_FILE_VERSION)
            put(arrayKey, array)
            put(MAC_KEY, hmac(canonicalPayload(SIGNED_FILE_VERSION, arrayKey, array)))
        }
    }

    /**
     * Build the canonical pre-image used for HMAC. Includes the file [version]
     * and [arrayKey] as a domain separator so a v1-signed entries blob can NOT
     * be replayed as v2/pending/denied. See [readSignedArray] for the verifier
     * that reconstructs this.
     */
    private fun canonicalPayload(version: Int, arrayKey: String, array: JSONArray): String =
        buildString {
            append("v=").append(version).append("|k=").append(arrayKey).append('|')
            append('[')
            for (i in 0 until array.length()) {
                if (i > 0) append(',')
                val item = array.getJSONObject(i)
                when (arrayKey) {
                    ENTRIES_KEY -> appendCanonicalEntry(item, timestampKey = "grantedAtMs")
                    PENDING_KEY -> appendCanonicalEntry(item, timestampKey = "firstRequestedAtMs")
                    DENIED_KEY -> appendCanonicalDenied(item)
                    else -> throw IllegalArgumentException("Unknown allowlist array key: $arrayKey")
                }
            }
            append(']')
        }

    private fun StringBuilder.appendCanonicalEntry(item: JSONObject, timestampKey: String) {
        append('{')
        append("\"pkg\":").append(JSONObject.quote(item.getString("pkg")))
        append(",\"sig\":").append(JSONObject.quote(item.getString("sig")))
        append(",\"").append(timestampKey).append("\":").append(item.optLong(timestampKey, 0L))
        val displayName = item.optString("displayName").ifEmpty { null }
        if (displayName != null) {
            append(",\"displayName\":").append(JSONObject.quote(displayName))
        }
        append('}')
    }

    private fun StringBuilder.appendCanonicalDenied(item: JSONObject) {
        append('{')
        append("\"pkg\":").append(JSONObject.quote(item.getString("pkg")))
        append(",\"sig\":").append(JSONObject.quote(item.getString("sig")))
        append(",\"deniedAtMs\":").append(item.optLong("deniedAtMs", 0L))
        append(",\"expiresAtMs\":").append(item.optLong("expiresAtMs", 0L))
        append('}')
    }

    private fun hmac(payload: String): String {
        val mac = Mac.getInstance(HMAC_ALGORITHM)
        mac.init(SecretKeySpec(loadOrCreateHmacKey(), HMAC_ALGORITHM))
        return mac.doFinal(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun verifyMac(payload: String, actualHex: String): Boolean {
        if (!HEX_SHA256.matches(actualHex)) return false
        return MessageDigest.isEqual(
            hmac(payload).toByteArray(Charsets.US_ASCII),
            actualHex.lowercase().toByteArray(Charsets.US_ASCII),
        )
    }

    /**
     * Returns the HMAC key. If the calling thread is already inside [withFileLock]
     * the key is read directly without acquiring the lock again, avoiding
     * [java.nio.channels.OverlappingFileLockException] on re-entrant calls from
     * [hmac] / [verifyMac] invoked transitively during write operations.
     *
     * Caching is intentionally omitted so that a corrupt key file is detected on
     * every call — once the file is quarantined and a new key generated, subsequent
     * HMAC verifications against entries signed with the old key will correctly fail,
     * signalling that callers must be re-approved (audit H8 / M15).
     */
    private fun loadOrCreateHmacKey(): ByteArray =
        if ((fileLockDepth.get() ?: 0) > 0) readOrCreateHmacKeyLocked()
        else withFileLock { readOrCreateHmacKeyLocked() }

    private fun readOrCreateHmacKeyLocked(): ByteArray {
        if (hmacKeyFile.exists()) {
            if (!isRegularFileForRead(hmacKeyFile)) return generateAndPersistHmacKey()
            val encoded = try {
                hmacKeyFile.readText().trim()
            } catch (t: Throwable) {
                quarantineCorruptHmacKey("read failed: ${t.javaClass.simpleName}")
                return generateAndPersistHmacKey()
            }
            val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            if (decoded != null && decoded.size >= HMAC_KEY_BYTES) return decoded
            quarantineCorruptHmacKey("decode failed or wrong length (${decoded?.size ?: -1})")
        }
        return generateAndPersistHmacKey()
    }

    private fun quarantineCorruptHmacKey(reason: String) {
        val ts = System.currentTimeMillis()
        val quarantined = File(hmacKeyFile.parentFile, "${hmacKeyFile.name}.bad-$ts")
        val moved = try { hmacKeyFile.renameTo(quarantined) } catch (_: Throwable) { false }
        MindlayerLog.e(
            TAG,
            "HMAC key file corrupt ($reason); ${if (moved) "quarantined to ${quarantined.name}" else "could not quarantine"}; allowlist will be reset",
        )
    }

    private fun generateAndPersistHmacKey(): ByteArray {
        val key = ByteArray(HMAC_KEY_BYTES)
        SecureRandom().nextBytes(key)
        atomicWrite(hmacKeyFile, Base64.getEncoder().encodeToString(key))
        return key
    }

    companion object {
        private const val TAG = "AllowlistStore"
        const val DEFAULT_DIR_NAME = "mindlayer_allowlist"

        /**
         * F-033: cap pending-approval rows to bound disk growth on a flooder.
         * FIFO eviction: oldest rows fall off first, so a real user-initiated
         * pending request stays at the top of the list.
         */
        const val MAX_PENDING_ROWS = 32

        /** Alias retained for source-compat with security tests. */
        const val MAX_PENDING = MAX_PENDING_ROWS

        /** F-033: in-memory dedup TTL. Per-process; lives in `:ml`. */
        @VisibleForTesting
        internal const val DEDUP_TTL_MS: Long = 30_000L

        private const val DEDUP_MAP_SOFT_CAP = 256

        // Bumped to 2 when canonicalPayload was changed to include version+arrayKey
        // domain separator (audit M6). Verifier accepts MIN_SUPPORTED_VERSION..SIGNED_FILE_VERSION.
        private const val SIGNED_FILE_VERSION = 2
        private const val MIN_SUPPORTED_VERSION = 2
        private const val ENTRIES_KEY = "entries"
        private const val PENDING_KEY = "pending"
        private const val DENIED_KEY = "denied"
        private const val MAC_KEY = "mac"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val HMAC_KEY_BYTES = 32
        private val HEX_SHA256 = Regex("(?i)^[0-9a-f]{64}$")

        // Anti-DoS caps for the pending list (audit H2/F-B-O-1).
        const val MAX_PACKAGE_NAME_LENGTH = 255
        // Length of denial cooldown after denyPending / revoke (audit M8).
        const val DENIAL_TTL_MS = 7L * 24 * 60 * 60 * 1000
    }
}
