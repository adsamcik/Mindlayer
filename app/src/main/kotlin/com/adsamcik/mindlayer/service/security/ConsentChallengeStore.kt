package com.adsamcik.mindlayer.service.security

import android.content.Context
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantLock
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Disk-backed registry of in-flight consent challenges, owned by the `:ml`
 * process. See `docs/architecture/CONSENT_ARCHITECTURE.md § ConsentChallengeStore`.
 *
 * A challenge is the server-side anchor that binds a caller's verified
 * identity (UID + package + signing cert, captured via real
 * `Binder.getCallingUid()` at `requestConsentChallenge` time) to an opaque
 * single-use nonce. The nonce is embedded in a `PendingIntent` that the
 * client fires to launch `ConsentActivity`; the activity calls
 * [lookup]/[consume] to retrieve the pinned identity and record the user's
 * decision. This is the only mechanism by which an external caller's
 * identity reaches the consent UI — `ConsentActivity` never trusts
 * Activity-layer caller APIs, which do not carry verifiable identity.
 *
 * ### Why disk-backed (not in-memory)
 *
 * The challenge MUST outlive the `:ml` *Service component*. The SDK helper
 * `MindlayerConsent.requestConsent` transient-binds `:ml`, calls
 * `requestConsentChallenge`, and immediately unbinds. With no remaining
 * binding Android tears the bound Service down (often the whole process)
 * before `ConsentActivity` — which lives in the service app's *main* process
 * — can bind `:ml` and call `lookupChallenge`. An in-memory store is wiped at
 * that teardown, so the nonce is always gone by the time the consent screen
 * resolves it and every flow shows "expired". (Found by on-device
 * validation; the earlier in-memory design's "harmless occasional retry"
 * assumption was wrong — the loss is deterministic, not occasional.)
 *
 * Persisting the record to a single HMAC-signed JSON file in the service's
 * private `filesDir` decouples the two binds: the record survives Service
 * teardown and process death until it is consumed or its TTL expires. The
 * 5-minute TTL remains the security contract; durability is what makes the
 * flow actually reachable.
 *
 * Storage mirrors [AllowlistStore] / [ConsentAttemptStore]: written under a
 * cross-process [FileLock] with atomic rename, re-read on every operation,
 * and HMAC-authenticated (domain-separated by the `challenges` array key) so
 * a nonce→identity binding cannot be forged by an offline editor.
 *
 * ### Concurrency
 *
 * Every operation runs under [withFileLock] (an intra-process
 * [ReentrantLock] plus an inter-process [FileLock]). [consume] is therefore
 * an atomic read-remove-write: exactly one caller wins the single-use race
 * even if two threads / two `ConsentActivity` instances call
 * `completeConsent` with the same nonce simultaneously. [lookup] is a
 * non-mutating read (it only prunes an already-expired entry) and may be
 * called any number of times before consume.
 */
class ConsentChallengeStore(
    context: Context,
    dirName: String = AllowlistStore.DEFAULT_DIR_NAME,
    private val maxOutstanding: Int = DEFAULT_MAX_OUTSTANDING,
    private val ttlMs: Long = DEFAULT_TTL_MS,
    private val timeSource: () -> Long = { System.currentTimeMillis() },
    private val secureRandom: SecureRandom = SecureRandom(),
) {

    private val appContext: Context = context.applicationContext
    // Filesystem handles are lazy so constructing the store is cheap and
    // touches no disk — important because ServiceBinder default-constructs
    // one and test fixtures inject a mock Context. Real I/O happens only on
    // the first consent operation.
    private val baseDir: File by lazy { File(appContext.filesDir, dirName).also { it.mkdirs() } }
    private val challengesFile: File by lazy { File(baseDir, "consent_challenges.json") }
    private val lockFile: File by lazy { File(baseDir, "consent_challenges.lock") }
    private val hmacKeyFile: File by lazy { File(baseDir, "consent_challenges.hmac") }

    private val processLock: ReentrantLock by lazy {
        PROCESS_LOCKS.computeIfAbsent(lockFile.absolutePath) { ReentrantLock() }
    }
    private val fileLockDepth = ThreadLocal.withInitial { 0 }

    /**
     * Server-side challenge record. Only [nonce], [expiresAtMs] and a
     * minted `PendingIntent` cross the wire (via the `ConsentChallenge`
     * parcelable); the rest of this record never leaves `:ml`.
     */
    data class ChallengeRecord(
        val nonce: String,
        val callerUid: Int,
        val packageName: String,
        val signingCertSha256: String,
        val displayName: String?,
        val installSource: String?,
        val previousSigSha256: String?,
        val createdAtMs: Long,
        val expiresAtMs: Long,
    )

    /**
     * Mint a fresh challenge for [callerUid]/[packageName]/[signingCertSha256]
     * and return its record (the caller — `ServiceBinder` — turns this into
     * a `ConsentChallenge` parcelable with a minted `PendingIntent`).
     *
     * Prunes expired entries first. If the live count would exceed
     * [maxOutstanding] after pruning, the oldest outstanding challenge is
     * evicted (FIFO) so a flood of unfinished consent flows cannot grow the
     * file without bound — the per-UID rate limit on `requestConsentChallenge`
     * is the primary defence; this is belt-and-braces.
     */
    fun issue(
        callerUid: Int,
        packageName: String,
        signingCertSha256: String,
        displayName: String?,
        installSource: String?,
        previousSigSha256: String?,
    ): ChallengeRecord {
        val now = timeSource()
        return withFileLock {
            val records = readRecords()
                .filter { now < it.expiresAtMs }
                .toMutableList()
            if (records.size >= maxOutstanding) {
                val oldest = records.minByOrNull { it.createdAtMs }
                if (oldest != null) {
                    records.remove(oldest)
                    MindlayerLog.w(
                        TAG,
                        "Evicted oldest outstanding consent challenge (cap=$maxOutstanding " +
                            "reached) for pkg=${oldest.packageName}",
                    )
                }
            }
            val record = ChallengeRecord(
                nonce = generateNonce(),
                callerUid = callerUid,
                packageName = packageName,
                signingCertSha256 = signingCertSha256,
                displayName = displayName,
                installSource = installSource,
                previousSigSha256 = previousSigSha256,
                createdAtMs = now,
                expiresAtMs = now + ttlMs,
            )
            records += record
            writeRecords(records)
            record
        }
    }

    /**
     * Non-mutating read of the challenge bound to [nonce]. Returns `null`
     * if the nonce is unknown or has expired (an expired entry is pruned
     * as a side effect). Safe to call multiple times — the nonce is NOT
     * consumed by a lookup. `ConsentActivity` uses this to populate its UI
     * before the user has made a decision.
     */
    fun lookup(nonce: String?): ChallengeRecord? {
        if (nonce.isNullOrEmpty()) return null
        val now = timeSource()
        return withFileLock {
            val records = readRecords()
            val record = records.firstOrNull { it.nonce == nonce } ?: return@withFileLock null
            if (now >= record.expiresAtMs) {
                writeRecords(records.filterNot { it.nonce == nonce })
                return@withFileLock null
            }
            record
        }
    }

    /**
     * Atomically consume the challenge bound to [nonce]. Returns the record
     * to the single winning caller and `null` to everyone else (unknown
     * nonce, already-consumed nonce, expired nonce). After a successful
     * consume the nonce can never be used again.
     *
     * `ConsentActivity` calls this exactly once, when the user commits a
     * decision (`Approve` / any `Deny` variant). The [withFileLock] critical
     * section makes the read-remove-write atomic across threads AND
     * processes, so a double-fired consent or a racing second
     * `ConsentActivity` instance cannot apply two different decisions for
     * the same nonce.
     */
    fun consume(nonce: String?): ChallengeRecord? {
        if (nonce.isNullOrEmpty()) return null
        val now = timeSource()
        return withFileLock {
            val records = readRecords()
            val record = records.firstOrNull { it.nonce == nonce }
            // Always remove the entry (consumed or expired); null return to
            // callers whose nonce was unknown/expired/already-taken.
            if (record != null) {
                writeRecords(records.filterNot { it.nonce == nonce })
            }
            record?.takeIf { now < it.expiresAtMs }
        }
    }

    /** Current count of outstanding (issued, not yet consumed/expired) challenges. */
    fun outstandingCount(): Int {
        val now = timeSource()
        return withFileLock {
            val live = readRecords().filter { now < it.expiresAtMs }
            // Opportunistically prune expired rows so the file does not grow.
            if (live.size != readRecords().size) writeRecords(live)
            live.size
        }
    }

    private fun generateNonce(): String {
        val bytes = ByteArray(NONCE_BYTES)
        secureRandom.nextBytes(bytes)
        // URL-safe Base64 without padding — opaque to the client, embeds
        // cleanly in a content URI for the per-nonce PendingIntent.
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    // ---- Persistence -----------------------------------------------------

    private inline fun <T> withFileLock(block: () -> T): T {
        processLock.lock()
        try {
            if ((fileLockDepth.get() ?: 0) > 0) {
                fileLockDepth.set((fileLockDepth.get() ?: 0) + 1)
                try {
                    return block()
                } finally {
                    fileLockDepth.set(((fileLockDepth.get() ?: 1) - 1).coerceAtLeast(0))
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

    private fun writeRecords(records: List<ChallengeRecord>) {
        val array = JSONArray()
        for (r in records.sortedBy { it.nonce }) {
            array.put(
                JSONObject().apply {
                    put("nonce", r.nonce)
                    put("uid", r.callerUid)
                    put("pkg", r.packageName)
                    put("sig", r.signingCertSha256)
                    put("displayName", r.displayName ?: JSONObject.NULL)
                    put("installSource", r.installSource ?: JSONObject.NULL)
                    put("previousSig", r.previousSigSha256 ?: JSONObject.NULL)
                    put("createdAtMs", r.createdAtMs)
                    put("expiresAtMs", r.expiresAtMs)
                },
            )
        }
        val envelope = JSONObject().apply {
            put("version", SIGNED_FILE_VERSION)
            put(CHALLENGES_KEY, array)
            put(MAC_KEY, hmac(canonicalPayload(array)))
        }
        atomicWrite(challengesFile, envelope.toString())
    }

    private fun readEnvelope(): JSONObject? {
        if (!challengesFile.exists()) return null
        val raw = try { challengesFile.readText() } catch (_: IOException) { return null }
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        return try {
            val envelope = JSONObject(trimmed)
            val version = envelope.optInt("version", -1)
            if (version != SIGNED_FILE_VERSION) {
                MindlayerLog.w(TAG, "Rejected unknown-version consent_challenges (version=$version)")
                return null
            }
            val challenges = envelope.optJSONArray(CHALLENGES_KEY) ?: JSONArray()
            val mac = envelope.optString(MAC_KEY)
            if (!verifyMac(canonicalPayload(challenges), mac)) {
                MindlayerLog.w(TAG, "Rejected tampered consent_challenges file")
                return null
            }
            envelope
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Failed to parse consent_challenges", throwable = t)
            null
        }
    }

    private fun readRecords(): List<ChallengeRecord> {
        val array = readEnvelope()?.optJSONArray(CHALLENGES_KEY) ?: return emptyList()
        return try {
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        ChallengeRecord(
                            nonce = o.getString("nonce"),
                            callerUid = o.getInt("uid"),
                            packageName = o.getString("pkg"),
                            signingCertSha256 = o.getString("sig"),
                            displayName = o.optNullableString("displayName"),
                            installSource = o.optNullableString("installSource"),
                            previousSigSha256 = o.optNullableString("previousSig"),
                            createdAtMs = o.optLong("createdAtMs", 0L),
                            expiresAtMs = o.optLong("expiresAtMs", 0L),
                        ),
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun JSONObject.optNullableString(key: String): String? =
        if (!has(key) || isNull(key)) null else opt(key)?.toString()

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            java.io.FileOutputStream(tmp).use { fos ->
                fos.write(content.toByteArray(Charsets.UTF_8))
                fos.flush()
                try { fos.fd.sync() } catch (_: Throwable) { }
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

    /**
     * Domain-separated canonical pre-image. Every field of every record is
     * authenticated so neither the nonce→identity binding nor the TTL can be
     * forged by an offline editor with filesystem access.
     */
    private fun canonicalPayload(challenges: JSONArray): String = buildString {
        append("v=").append(SIGNED_FILE_VERSION).append("|k=").append(CHALLENGES_KEY).append('|')
        append('[')
        for (i in 0 until challenges.length()) {
            if (i > 0) append(',')
            val o = challenges.getJSONObject(i)
            append('{')
            append("\"nonce\":").append(JSONObject.quote(o.getString("nonce")))
            append(",\"uid\":").append(o.getInt("uid"))
            append(",\"pkg\":").append(JSONObject.quote(o.getString("pkg")))
            append(",\"sig\":").append(JSONObject.quote(o.getString("sig")))
            append(",\"displayName\":").append(canonField(o, "displayName"))
            append(",\"installSource\":").append(canonField(o, "installSource"))
            append(",\"previousSig\":").append(canonField(o, "previousSig"))
            append(",\"createdAtMs\":").append(o.optLong("createdAtMs", 0L))
            append(",\"expiresAtMs\":").append(o.optLong("expiresAtMs", 0L))
            append('}')
        }
        append(']')
    }

    private fun canonField(o: JSONObject, key: String): String =
        if (!o.has(key) || o.isNull(key)) "null" else JSONObject.quote(o.getString(key))

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

    private fun loadOrCreateHmacKey(): ByteArray =
        if ((fileLockDepth.get() ?: 0) > 0) readOrCreateHmacKeyLocked()
        else withFileLock { readOrCreateHmacKeyLocked() }

    private fun readOrCreateHmacKeyLocked(): ByteArray {
        if (hmacKeyFile.exists()) {
            val encoded = try { hmacKeyFile.readText().trim() } catch (_: Throwable) { null }
            val decoded = encoded?.let { runCatching { Base64.getDecoder().decode(it) }.getOrNull() }
            if (decoded != null && decoded.size >= HMAC_KEY_BYTES) return decoded
        }
        val key = ByteArray(HMAC_KEY_BYTES)
        SecureRandom().nextBytes(key)
        atomicWrite(hmacKeyFile, Base64.getEncoder().encodeToString(key))
        return key
    }

    companion object {
        private const val TAG = "ConsentChallengeStore"

        private val PROCESS_LOCKS = ConcurrentHashMap<String, ReentrantLock>()

        private const val SIGNED_FILE_VERSION = 1
        private const val CHALLENGES_KEY = "challenges"
        private const val MAC_KEY = "mac"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val HMAC_KEY_BYTES = 32
        private val HEX_SHA256 = Regex("(?i)^[0-9a-f]{64}$")

        /** 256 bits of randomness — see crypto review (q/2^256 guess bound). */
        const val NONCE_BYTES = 32

        /** Default challenge TTL (5 minutes). Mirrors `ConsentChallenge.TTL_MS_DEFAULT`. */
        const val DEFAULT_TTL_MS = 5 * 60 * 1000L

        /**
         * Belt-and-braces cap on outstanding challenges. The per-UID
         * 10/hour rate limit on `requestConsentChallenge` is the primary
         * spam defence; this bounds the file even across many UIDs.
         */
        const val DEFAULT_MAX_OUTSTANDING = 256
    }
}
