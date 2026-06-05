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
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class AllowlistEntry(
    val packageName: String,
    val signingCertSha256: String,
    val grantedAtMs: Long,
    val displayName: String? = null,
)

/**
 * Thrown by [AllowlistStore.approve] when the live signing certificate for
 * the target package no longer matches the sig captured for approval.
 * F-031 — closes the TOCTOU window between display and grant.
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
 * A package that was explicitly denied via the consent flow or revoked via
 * [AllowlistStore.revoke]. While [expiresAtMs] is in the future, the caller is
 * blocked from receiving a fresh consent challenge or using existing service
 * access.
 *
 * If [permanent] is `true`, the entry is treated as a sticky tombstone of an
 * explicit user decision: it is never pruned by expiry. For persistence and
 * back-compat, permanent entries set [expiresAtMs] to [Long.MAX_VALUE] so older
 * readers that ignore the `permanent` field still treat them as "never expires".
 */
data class DeniedEntry(
    val packageName: String,
    /**
     * Lowercase hex SHA-256 of the signer that was denied. `null` when
     * [scope] is [DenialScope.PACKAGE_WIDE] — a package-wide block does
     * not pin a specific cert, so cert rotation cannot bypass it.
     */
    val signingCertSha256: String?,
    val deniedAtMs: Long,
    val expiresAtMs: Long,
    val permanent: Boolean = false,
    /**
     * v0.10: the scope of the denial. [DenialScope.CERT_PAIR] (default,
     * legacy) for `(pkg, sig)`-keyed denials. [DenialScope.PACKAGE_WIDE]
     * for the "Block permanently" consent flow which prevents cert
     * rotation from bypassing the user's clear "no" — applies to any
     * cert under the package name.
     */
    val scope: DenialScope = DenialScope.CERT_PAIR,
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
 * sidecar `.lock` file. The [entries] StateFlow is maintained per-process
 * for UI observation and refreshed via [refresh].
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
    private val deniedFile: File = File(baseDir, "denied.json")
    private val lockFile: File = File(baseDir, "allowlist.lock")
    private val hmacKeyFile: File = File(baseDir, "allowlist.hmac")

    /**
     * S-8: AndroidKeyStore wrapper for the HMAC key. The key file is no
     * longer plaintext base64 on devices with a working Keystore — the
     * random HMAC key is wrapped (AES-256-GCM) so a filesDir-write attacker
     * cannot read it and forge a valid allowlist/denial signature. Aliased
     * per store dir so independent stores (test fixtures) don't collide.
     */
    private val hmacKeyWrapper: KeystoreSecretWrapper =
        KeystoreSecretWrapper(alias = "mindlayer.allowlist.hmac.${baseDir.name}")

    /**
     * JVM-wide mutex that serialises threads in this process before they
     * race for the kernel-level [java.nio.channels.FileLock] on
     * [lockFile]. Java NIO `FileChannel.lock()` is a **process-wide**
     * advisory lock, not a thread-wide one — concurrent acquire attempts
     * from two threads in the same JVM throw
     * [java.nio.channels.OverlappingFileLockException] from
     * `SharedFileLockTable.checkList`, which we previously swallowed
     * inside [readSignedArray] and returned `null` for the parsed entries
     * — making `isAllowed` falsely return `false` for an already-approved
     * caller (Bug #5). The canonical first-party startup pattern
     * (`registerClient` racing `getCapabilities` on two binder threads)
     * hits this every time.
     *
     * Keyed by the absolute lock-file path through
     * [PROCESS_LOCKS] so two `AllowlistStore` instances in the same
     * process — e.g. test fixtures that recreate the store with a fresh
     * temp dir — get independent mutexes and don't false-share. The
     * outer mutex is a [ReentrantLock] so a single thread can still
     * legitimately re-enter (e.g. [revoke] -> [writeDenied] -> [readSignedArray]).
     */
    private val processLock: ReentrantLock =
        PROCESS_LOCKS.computeIfAbsent(lockFile.absolutePath) { ReentrantLock() }

    /**
     * Tracks how many times the current thread is inside [withFileLock]. Java NIO
     * [java.nio.channels.FileChannel.lock] does NOT support same-JVM re-entry on the
     * same file region — a nested call throws [java.nio.channels.OverlappingFileLockException].
     * [loadOrCreateHmacKey] uses this depth to skip the inner lock acquisition when
     * it is invoked transitively from code that already holds the lock.
     *
     * Must be declared before [_entries] so it is initialised before the
     * property initialiser calls [readEntries], which transitively calls
     * [loadOrCreateHmacKey].
     */
    private val fileLockDepth = ThreadLocal.withInitial { 0 }

    private val _entries = MutableStateFlow(readEntries())
    val entries: StateFlow<List<AllowlistEntry>> = _entries.asStateFlow()

    init {
        withFileLock {
            migrateLegacyArray(entriesFile, ENTRIES_KEY)
        }
        refresh()
    }

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

    /** Re-read approved entries and update the StateFlow. Call this in dashboard pollers. */
    fun refresh() {
        _entries.value = readEntries()
    }

    /**
     * F-031: production approval entry point — re-verifies the live signing
     * certificate against [expectedSigSha256] under the file lock and writes
     * the entry only if it matches. Closes the TOCTOU window between
     * dashboard render and the user's tap (or, in the v0.10 consent flow,
     * between [com.adsamcik.mindlayer.IMindlayerService.requestConsentChallenge]
     * and the user's `completeConsent(GRANT)` in `ConsentActivity`).
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
     * v0.10: atomic consent-grant. Combines the in-effect-denial check and the
     * F-031 [approve] write into a SINGLE [withFileLock] critical section, so a
     * concurrent `deny()` cannot slip between a separate denial-check and the
     * approve (which clears denials). Closes the grant/deny race flagged by the
     * security review: two challenges for the same app can be issued before any
     * denial exists, the user denies one prompt and approves the other, and a
     * stale GRANT would otherwise wipe the user's most recent "no".
     *
     * Returns the blocking [DeniedEntry] (leaving the denial intact, NO approval
     * written) if an in-effect denial exists; `null` after a successful approve.
     * Cert-mismatch / package-gone still throw exactly as [approve] does.
     */
    fun approveFromConsent(
        context: Context,
        pkg: String,
        expectedSigSha256: String,
        displayName: String? = null,
    ): DeniedEntry? {
        val now = System.currentTimeMillis()
        return withFileLock {
            val blocking = readDenied().firstOrNull { entry ->
                entry.packageName == pkg && (entry.permanent || entry.expiresAtMs > now) &&
                    when (entry.scope) {
                        DenialScope.PACKAGE_WIDE -> true
                        DenialScope.CERT_PAIR ->
                            entry.signingCertSha256?.equals(expectedSigSha256, ignoreCase = true) == true
                    }
            }
            if (blocking != null) return@withFileLock blocking
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
            writeDenied(readDenied().filterNot { it.packageName == pkg })
            null
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
    }

    fun revoke(pkg: String) {
        withFileLock {
            val current = readEntries()
            val target = current.firstOrNull { it.packageName == pkg } ?: return@withFileLock
            val updated = current.filterNot { it.packageName == pkg }
            writeEntries(updated)
            _entries.value = updated

            // Persist a *permanent* denial: explicit user revoke is a sticky
            // decision. permanent=true tells the writer to skip the
            // expiry-pruning step; we also pin expiresAtMs to Long.MAX_VALUE
            // so older readers that ignore the `permanent` field still treat
            // the row as "never expires".
            val now = System.currentTimeMillis()
            val deniedUpdated = readDeniedIncludingExpired().filterNot { it.packageName == pkg } +
                DeniedEntry(
                    packageName = pkg,
                    signingCertSha256 = target.signingCertSha256,
                    deniedAtMs = now,
                    expiresAtMs = Long.MAX_VALUE,
                    permanent = true,
                )
            writeDenied(deniedUpdated)
        }
    }

    /**
     * Returns true if [pkg]/[sigSha256] has been denied and the denial is
     * still in effect. Honours [DenialScope]:
     *  - [DenialScope.CERT_PAIR] — matches when both pkg AND sig match.
     *  - [DenialScope.PACKAGE_WIDE] — matches any cert under [pkg].
     *    Cert rotation does NOT bypass.
     */
    fun isDenied(pkg: String, sigSha256: String): Boolean {
        val now = System.currentTimeMillis()
        return readDenied().any { entry ->
            entry.packageName == pkg && (entry.permanent || entry.expiresAtMs > now) &&
                when (entry.scope) {
                    DenialScope.PACKAGE_WIDE -> true
                    DenialScope.CERT_PAIR ->
                        entry.signingCertSha256?.equals(sigSha256, ignoreCase = true) == true
                }
        }
    }

    /**
     * True iff [pkg] has a [DenialScope.PACKAGE_WIDE] block in effect.
     * `ConsentActivity` uses this to short-circuit a permanently-blocked
     * package with `RESULT_CANCELED` before rendering any UI — no cert
     * comparison needed because the block applies to any cert under the
     * package.
     */
    fun isPermanentlyDenied(pkg: String): Boolean {
        val now = System.currentTimeMillis()
        return readDenied().any { entry ->
            entry.packageName == pkg &&
                entry.scope == DenialScope.PACKAGE_WIDE &&
                (entry.permanent || entry.expiresAtMs > now)
        }
    }

    /**
     * v0.10: the in-effect [DeniedEntry] for `(pkg, sigSha256)`, or `null` if
     * the caller is not currently denied. Honours [DenialScope] exactly like
     * [isDenied] but returns the row so callers can surface the unblock time
     * (`expiresAtMs`) and permanence.
     *
     * `requestConsentChallenge` uses this to refuse minting a fresh consent
     * `PendingIntent` for an app the user has already told "no" — without it,
     * a denied app could re-trigger the prompt immediately and the user's
     * 24h / permanent choice would only suppress inference calls (via
     * `authorizeCall`'s [isDenied] gate), not the re-prompt itself.
     */
    fun denialFor(pkg: String, sigSha256: String): DeniedEntry? {
        val now = System.currentTimeMillis()
        return readDenied().firstOrNull { entry ->
            entry.packageName == pkg && (entry.permanent || entry.expiresAtMs > now) &&
                when (entry.scope) {
                    DenialScope.PACKAGE_WIDE -> true
                    DenialScope.CERT_PAIR ->
                        entry.signingCertSha256?.equals(sigSha256, ignoreCase = true) == true
                }
        }
    }

    /**
     * v0.10: returns the full [AllowlistEntry] for an approved `(pkg, sig)`
     * pair, or `null` if not allowed. Useful for the dashboard when it
     * needs richer info than the boolean [isAllowed] check returns
     * (e.g., displayName + grantedAtMs for a per-app detail row).
     */
    fun lookupAllowed(pkg: String, sigSha256: String): AllowlistEntry? {
        val entry = readEntries().firstOrNull { it.packageName == pkg } ?: return null
        return if (entry.signingCertSha256.equals(sigSha256, ignoreCase = true)) entry else null
    }

    /**
     * v0.10: unified denial entry point dispatched from `ConsentActivity`
     * per the user's [com.adsamcik.mindlayer.ConsentDecision] kind.
     *
     *  - [com.adsamcik.mindlayer.ConsentDecision.KIND_DENY_24H]
     *    writes a [DenialScope.CERT_PAIR] row with `expiresAt = now + 24h`.
     *    Requires non-null [sigSha256].
     *  - [com.adsamcik.mindlayer.ConsentDecision.KIND_DENY_PERMANENT]
     *    writes a [DenialScope.PACKAGE_WIDE] row with `permanent = true`
     *    and `expiresAt = Long.MAX_VALUE`. [sigSha256] is ignored — the
     *    block applies to any cert under [pkg] so cert rotation cannot
     *    bypass it.
     *
     * Both variants upsert: any prior denial row for the same package is
     * replaced (single-row-per-package invariant). Use [revoke] to remove
     * a prior approval at the same time as denying.
     */
    fun deny(pkg: String, sigSha256: String?, kind: Int) {
        val now = System.currentTimeMillis()
        val entry = when (kind) {
            com.adsamcik.mindlayer.ConsentDecision.KIND_DENY_24H -> {
                requireNotNull(sigSha256) { "DENY_24H requires non-null sigSha256" }
                DeniedEntry(
                    packageName = pkg,
                    signingCertSha256 = sigSha256,
                    deniedAtMs = now,
                    expiresAtMs = now + DENY_24H_TTL_MS,
                    permanent = false,
                    scope = DenialScope.CERT_PAIR,
                )
            }
            com.adsamcik.mindlayer.ConsentDecision.KIND_DENY_PERMANENT -> DeniedEntry(
                packageName = pkg,
                signingCertSha256 = null,
                deniedAtMs = now,
                expiresAtMs = Long.MAX_VALUE,
                permanent = true,
                scope = DenialScope.PACKAGE_WIDE,
            )
            else -> throw IllegalArgumentException("Unsupported deny kind: $kind")
        }
        withFileLock {
            val updated = readDeniedIncludingExpired().filterNot { it.packageName == pkg } + entry
            writeDenied(updated)
        }
    }

    // ---- Persistence -----------------------------------------------------

    private inline fun <T> withFileLock(block: () -> T): T {
        // Bug #5: gate THREADS in this JVM before the FileLock acquire so
        // that `FileChannel.lock()` only ever sees one acquirer at a time
        // per lock-file path. Without this, two concurrent isAllowed calls
        // race in `SharedFileLockTable` and one of them throws
        // OverlappingFileLockException, which readSignedArray swallows to
        // null and authorizeCall then mis-classifies an approved caller
        // as un-approved. ReentrantLock so a single thread that re-enters
        // (e.g. revoke -> writeDenied -> readSignedArray) is fine.
        processLock.lock()
        try {
            // Reentrant call from the same thread — caller already holds the
            // FileLock, so skip the second kernel acquire (would throw
            // OverlappingFileLockException). This preserves the existing
            // fileLockDepth contract used by `loadOrCreateHmacKey`.
            if (fileLockDepth.get() > 0) {
                fileLockDepth.set(fileLockDepth.get() + 1)
                try {
                    return block()
                } finally {
                    fileLockDepth.set((fileLockDepth.get() - 1).coerceAtLeast(0))
                }
            }
            return RandomAccessFile(lockFile, "rw").use { raf ->
                raf.channel.use { ch ->
                    val lock: FileLock = ch.lock()
                    fileLockDepth.set((fileLockDepth.get() ?: 0) + 1)
                    try {
                        block()
                    } finally {
                        fileLockDepth.set(((fileLockDepth.get() ?: 1) - 1).coerceAtLeast(0))
                        try { lock.release() } catch (_: Throwable) { }
                    }
                }
            }
        } finally {
            processLock.unlock()
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

    private fun writeDenied(list: List<DeniedEntry>) {
        val now = System.currentTimeMillis()
        // Permanent rows (set by revoke() / consent "Block permanently") are
        // never pruned by expiry — they are sticky tombstones of an explicit
        // user decision. Time-bounded rows from consent "Deny for 24h"
        // still expire at expiresAtMs.
        val pruned = list.filter { it.permanent || it.expiresAtMs > now }
        val array = JSONArray()
        for (e in pruned.sortedBy { it.packageName }) {
            array.put(JSONObject().apply {
                put("pkg", e.packageName)
                // v3: sig may be null for DenialScope.PACKAGE_WIDE — cert
                // rotation must not be able to bypass a package-wide block.
                e.signingCertSha256?.let { put("sig", it) }
                put("deniedAtMs", e.deniedAtMs)
                put("expiresAtMs", e.expiresAtMs)
                if (e.permanent) put("permanent", true)
                put("scope", e.scope.name)
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
                            signingCertSha256 = o.optString("sig").ifEmpty { null },
                            deniedAtMs = o.optLong("deniedAtMs", 0L),
                            expiresAtMs = o.optLong("expiresAtMs", 0L),
                            permanent = o.optBoolean("permanent", false),
                            scope = parseDenialScope(o.optString("scope")),
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
                    val permanent = o.optBoolean("permanent", false)
                    if (permanent || expires > now) {
                        add(
                            DeniedEntry(
                                packageName = o.getString("pkg"),
                                signingCertSha256 = o.optString("sig").ifEmpty { null },
                                deniedAtMs = o.optLong("deniedAtMs", 0L),
                                expiresAtMs = expires,
                                permanent = permanent,
                                scope = parseDenialScope(o.optString("scope")),
                            )
                        )
                    }
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /**
     * Parse a [DenialScope] from its serialized name. Empty / absent is
     * treated as [DenialScope.CERT_PAIR] — that is the v2→v3 migration
     * default for legacy denied entries that pre-date the scope field
     * (legacy revokes always wrote a cert-pair denial).
     * Unknown future values fall back to [DenialScope.CERT_PAIR] (the
     * narrower scope) so a corrupted or forged scope cannot widen a
     * denial into a package-wide block.
     */
    private fun parseDenialScope(value: String): DenialScope = when (value) {
        "" -> DenialScope.CERT_PAIR
        DenialScope.CERT_PAIR.name -> DenialScope.CERT_PAIR
        DenialScope.PACKAGE_WIDE.name -> DenialScope.PACKAGE_WIDE
        else -> DenialScope.CERT_PAIR
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
     * grant consent again. The renamed file is preserved for forensic
     * inspection but never read by the runtime.
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
                MindlayerLog.w(TAG, "Renamed unsigned legacy allowlist ${file.name} to ${renamed.name}; user must re-consent callers")
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
     * be replayed as v2/denied. See [readSignedArray] for the verifier
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
                    ENTRIES_KEY -> appendCanonicalEntry(item, timestampKey = "grantedAtMs", version = version)
                    DENIED_KEY -> appendCanonicalDenied(item, version = version)
                    else -> throw IllegalArgumentException("Unknown allowlist array key: $arrayKey")
                }
            }
            append(']')
        }

    private fun StringBuilder.appendCanonicalEntry(item: JSONObject, timestampKey: String, version: Int) {
        append('{')
        append("\"pkg\":").append(JSONObject.quote(item.getString("pkg")))
        append(",\"sig\":").append(JSONObject.quote(item.getString("sig")))
        append(",\"").append(timestampKey).append("\":").append(item.optLong(timestampKey, 0L))
        val displayName = item.optString("displayName").ifEmpty { null }
        if (displayName != null) {
            append(",\"displayName\":").append(JSONObject.quote(displayName))
        }
        // S-9 (v3+): bind the persisted, trusted `prevSig` (cert-rotation
        // metadata) into the HMAC pre-image so a filesDir-tamper attacker
        // cannot rewrite it without breaking the signature. Version-gated so
        // existing v2 files still verify under their original canonical form.
        if (version >= SIGNED_FILE_VERSION_METADATA) {
            val prevSig = item.optString("prevSig").ifEmpty { null }
            if (prevSig != null) {
                append(",\"prevSig\":").append(JSONObject.quote(prevSig))
            }
        }
        append('}')
    }

    private fun StringBuilder.appendCanonicalDenied(item: JSONObject, version: Int) {
        append('{')
        append("\"pkg\":").append(JSONObject.quote(item.getString("pkg")))
        // v2: sig is always present (no PACKAGE_WIDE scope existed).
        // v3+: sig may be absent for DenialScope.PACKAGE_WIDE.
        val sig = item.optString("sig").ifEmpty { null }
        if (version >= 3) {
            if (sig != null) {
                append(",\"sig\":").append(JSONObject.quote(sig))
            }
            // else: omit sig field entirely for PACKAGE_WIDE rows
        } else {
            // v2 legacy: sig was non-nullable; emit empty string if somehow missing
            append(",\"sig\":").append(JSONObject.quote(sig ?: ""))
        }
        append(",\"deniedAtMs\":").append(item.optLong("deniedAtMs", 0L))
        append(",\"expiresAtMs\":").append(item.optLong("expiresAtMs", 0L))
        if (version >= SIGNED_FILE_VERSION_METADATA) {
            // v3 (security-review S-9 + rubber-duck Issue #11): the `permanent`
            // tombstone flag AND the denial `scope` are HMAC-authenticated so a
            // filesDir-tamper attacker cannot (a) flip a sticky permanent revoke
            // into an expirable one and re-allow a revoked app after the TTL,
            // nor (b) widen a cert-pair denial to package-wide. Pre-v3 neither
            // was authenticated.
            append(",\"permanent\":").append(item.optBoolean("permanent", false))
            val scope = item.optString("scope").ifEmpty { DenialScope.CERT_PAIR.name }
            append(",\"scope\":").append(JSONObject.quote(scope))
        }
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
     * signalling that callers must grant consent again (audit H8 / M15).
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
            // S-8: wrapped-key path (mlks1:...). Unwrap via the Keystore.
            if (hmacKeyWrapper.isWrapped(encoded)) {
                val unwrapped = hmacKeyWrapper.unwrap(encoded)
                if (unwrapped != null && unwrapped.size >= HMAC_KEY_BYTES) return unwrapped
                // Wrapped blob present but Keystore key gone/invalid (user
                // cleared credentials, key migrated off device). Regenerate;
                // affected callers must be re-approved — the same recovery
                // semantics as a corrupt key.
                quarantineCorruptHmacKey("keystore unwrap failed")
                return generateAndPersistHmacKey()
            }
            // Legacy plaintext base64 path.
            val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            if (decoded != null && decoded.size >= HMAC_KEY_BYTES) {
                // S-8 migration: if the Keystore is now available, re-persist
                // the existing key in wrapped form so it stops sitting in
                // plaintext. Keep the SAME key bytes so existing signatures
                // still verify. Best-effort — a write failure leaves the
                // plaintext file in place and we retry next launch.
                if (hmacKeyWrapper.isAvailable) {
                    val wrapped = hmacKeyWrapper.wrap(decoded)
                    if (wrapped != null) {
                        runCatching { atomicWrite(hmacKeyFile, wrapped) }
                            .onSuccess { MindlayerLog.i(TAG, "Migrated plaintext allowlist HMAC key to Keystore-wrapped form") }
                    }
                }
                return decoded
            }
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
        // S-8: persist Keystore-wrapped when possible; fall back to legacy
        // plaintext base64 only when the Keystore is unavailable (a broken/
        // rooted host where the filesDir-write threat is already moot).
        val wrapped = hmacKeyWrapper.wrap(key)
        if (wrapped != null) {
            atomicWrite(hmacKeyFile, wrapped)
        } else {
            atomicWrite(hmacKeyFile, Base64.getEncoder().encodeToString(key))
        }
        return key
    }

    companion object {
        private const val TAG = "AllowlistStore"
        const val DEFAULT_DIR_NAME = "mindlayer_allowlist"

        /**
         * Bug #5: per-lock-file JVM-wide mutex that serialises threads in
         * this process before they race for the kernel-level
         * [java.nio.channels.FileLock]. Keyed by absolute path so two
         * `AllowlistStore` instances in the same process — typically test
         * fixtures with fresh temp dirs — get independent mutexes.
         *
         * The map grows monotonically until process death. In `:ml`
         * there is exactly one production store, so growth is bounded.
         * Test code that uses many temp dirs would leak a small
         * `ReentrantLock` per dir (~96 B), accepted in exchange for
         * correctness and the lack of any other reasonable lifecycle
         * hook for `AllowlistStore`.
         */
        private val PROCESS_LOCKS = ConcurrentHashMap<String, ReentrantLock>()

        // Bumped to 3 (v0.10 consent-architecture + security-review S-9):
        //  - DeniedEntry gained `scope` (cert-pair vs package-wide), and the
        //    canonical denied payload now binds both `permanent` and `scope`
        //    so an offline attacker can no longer flip a 24h denial to
        //    permanent or widen a cert-pair denial to package-wide
        //    (rubber-duck Issue #11);
        //  - the entry canonical binds the trusted `prevSig` cert-rotation
        //    metadata (S-9).
        // Bumped to 2 previously when canonicalPayload was changed to include
        // the version+arrayKey domain separator (audit M6).
        //
        // Entry shape (ENTRIES_KEY) is otherwise unchanged in v3 — v2 entries
        // read back as v3 without migration. The verifier accepts
        // MIN_SUPPORTED_VERSION..SIGNED_FILE_VERSION so legacy v2 files still
        // verify; canonicalPayload() is version-aware so the v3 pre-image picks
        // up the new fields only when the file declares v3.
        private const val SIGNED_FILE_VERSION = 3
        private const val MIN_SUPPORTED_VERSION = 2

        /** First version whose canonical pre-image binds `prevSig`/`permanent`. */
        private const val SIGNED_FILE_VERSION_METADATA = 3
        private const val ENTRIES_KEY = "entries"
        private const val DENIED_KEY = "denied"
        private const val MAC_KEY = "mac"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val HMAC_KEY_BYTES = 32
        private val HEX_SHA256 = Regex("(?i)^[0-9a-f]{64}$")

        /** v0.10: TTL for the "Deny for 24 hours" consent decision. */
        const val DENY_24H_TTL_MS = 24L * 60 * 60 * 1000
    }
}
