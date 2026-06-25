package com.adsamcik.mindlayer.service.security

import android.content.Context
import androidx.annotation.VisibleForTesting
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
 * The gate decision returned by [ConsentAttemptStore.checkGate].
 */
sealed interface ConsentGate {
    /** Caller may request a consent challenge. */
    data object Allow : ConsentGate

    /**
     * Caller is in a cooldown (its own dismiss escalation) or the
     * device-wide consent-prompt throttle is active. [untilMs] is the
     * wall-clock ms-since-epoch at which the block lifts; surfaced to the
     * SDK as `MindlayerErrorCode.CONSENT_DENIED` with `until=<untilMs>`.
     */
    data class Blocked(val untilMs: Long, val reason: String) : ConsentGate
}

/**
 * Persists per-`(packageName, signingCertSha256)` consent-attempt history and
 * a device-wide prompt throttle. See `docs/architecture/CONSENT_ARCHITECTURE.md
 * § ConsentAttemptStore`.
 *
 * Two anti-nag mechanisms:
 *
 *  1. **Per-(pkg,sig) dismiss escalation.** Each "Not now" dismiss increments
 *     a counter. After [DISMISS_COOLDOWN_1H_THRESHOLD] dismisses the next
 *     request is silently blocked for 1 hour; after
 *     [DISMISS_COOLDOWN_24H_THRESHOLD], 24 hours. A successful grant or an
 *     explicit 24h/permanent denial clears the counter (the denial itself
 *     then gates future requests via `AllowlistStore`).
 *
 *  2. **Device-wide prompt throttle** (crypto/adversarial review HIGH-2:
 *     sock-puppet fleets). At most [DEVICE_WIDE_MAX_PROMPTS] consent prompts
 *     may be *completed* within [DEVICE_WIDE_WINDOW_MS] across all callers.
 *     This bounds how many distinct attacker-controlled packages can nag the
 *     user in a short window, since each prompt the user dismisses feeds the
 *     throttle.
 *
 * Storage mirrors [AllowlistStore]: a single HMAC-signed JSON file in
 * `filesDir`, written under a cross-process [FileLock] with atomic rename,
 * re-read on every gate check. The HMAC pre-image is domain-separated by the
 * `attempts` array key so a row cannot be replayed into another signed file.
 */
class ConsentAttemptStore(
    context: Context,
    dirName: String = AllowlistStore.DEFAULT_DIR_NAME,
    private val timeSource: () -> Long = { System.currentTimeMillis() },
) {
    private val appContext: Context = context.applicationContext
    // Filesystem handles are lazy so constructing the store is cheap and
    // touches no disk — important because ServiceBinder default-constructs
    // one, and test fixtures inject a mock Context. Real I/O happens only
    // on the first consent operation.
    private val baseDir: File by lazy { File(appContext.filesDir, dirName).also { it.mkdirs() } }
    private val attemptsFile: File by lazy { File(baseDir, "consent_attempts.json") }
    private val lockFile: File by lazy { File(baseDir, "consent_attempts.lock") }
    private val hmacKeyFile: File by lazy { File(baseDir, "consent_attempts.hmac") }

    private val processLock: ReentrantLock by lazy {
        PROCESS_LOCKS.computeIfAbsent(lockFile.absolutePath) { ReentrantLock() }
    }
    private val fileLockDepth = ThreadLocal.withInitial { 0 }

    data class AttemptRecord(
        val packageName: String,
        val signingCertSha256: String,
        val firstSeenAtMs: Long,
        val lastShownAtMs: Long,
        val dismissCount: Int,
        val silentCooldownUntilMs: Long,
    )

    /**
     * Gate a `requestConsentChallenge` call. Returns [ConsentGate.Allow] when
     * the caller may proceed, or [ConsentGate.Blocked] (with the unblock
     * timestamp) when the caller is in dismiss-escalation cooldown or the
     * device-wide throttle is active.
     *
     * Pure read — does NOT record anything. Recording happens in
     * [recordPromptCompleted] / [recordDismiss] when a decision is committed.
     */
    fun checkGate(pkg: String, sig: String): ConsentGate {
        val now = timeSource()
        return withFileLock {
            val record = readAttempts().firstOrNull {
                it.packageName == pkg && it.signingCertSha256.equals(sig, ignoreCase = true)
            }
            if (record != null && now < record.silentCooldownUntilMs) {
                return@withFileLock ConsentGate.Blocked(
                    untilMs = record.silentCooldownUntilMs,
                    reason = "dismiss_cooldown",
                )
            }
            val recent = readDeviceWidePromptTimes().count { now - it < DEVICE_WIDE_WINDOW_MS }
            if (recent >= DEVICE_WIDE_MAX_PROMPTS) {
                val oldestInWindow = readDeviceWidePromptTimes()
                    .filter { now - it < DEVICE_WIDE_WINDOW_MS }
                    .minOrNull() ?: now
                return@withFileLock ConsentGate.Blocked(
                    untilMs = oldestInWindow + DEVICE_WIDE_WINDOW_MS,
                    reason = "device_wide_throttle",
                )
            }
            ConsentGate.Allow
        }
    }

    /**
     * Record that a consent prompt for [pkg]/[sig] was shown to the user.
     * Feeds the device-wide throttle and updates `lastShownAt`. Called from
     * `lookupChallenge` (right before `ConsentActivity` renders the prompt),
     * so a prompt that the user swipes away still counts toward the
     * device-wide throttle — closing the sock-puppet-fleet gap a
     * completeConsent-only counter would leave open.
     */
    fun recordPromptShown(pkg: String, sig: String) {
        val now = timeSource()
        withFileLock {
            // Append to the device-wide ring, pruned to the window.
            val times = (readDeviceWidePromptTimes() + now)
                .filter { now - it < DEVICE_WIDE_WINDOW_MS }
                .takeLast(DEVICE_WIDE_RING_CAP)
            // Touch the per-(pkg,sig) lastShownAt without changing dismiss state.
            val records = readAttempts().toMutableList()
            val idx = records.indexOfFirst {
                it.packageName == pkg && it.signingCertSha256.equals(sig, ignoreCase = true)
            }
            if (idx >= 0) {
                records[idx] = records[idx].copy(lastShownAtMs = now)
            }
            writeState(records, times)
        }
    }

    /**
     * Record a "Not now" dismiss for [pkg]/[sig] and apply escalation.
     * Returns the new dismiss count.
     */
    fun recordDismiss(pkg: String, sig: String): Int {
        val now = timeSource()
        return withFileLock {
            val records = readAttempts().toMutableList()
            val idx = records.indexOfFirst {
                it.packageName == pkg && it.signingCertSha256.equals(sig, ignoreCase = true)
            }
            val existing = idx.takeIf { it >= 0 }?.let { records[it] }
            val newCount = (existing?.dismissCount ?: 0) + 1
            val cooldownUntil = when {
                newCount >= DISMISS_COOLDOWN_24H_THRESHOLD -> now + COOLDOWN_24H_MS
                newCount >= DISMISS_COOLDOWN_1H_THRESHOLD -> now + COOLDOWN_1H_MS
                else -> 0L
            }
            val updated = AttemptRecord(
                packageName = pkg,
                signingCertSha256 = sig,
                firstSeenAtMs = existing?.firstSeenAtMs ?: now,
                lastShownAtMs = now,
                dismissCount = newCount,
                silentCooldownUntilMs = cooldownUntil,
            )
            if (idx >= 0) records[idx] = updated else records += updated
            writeState(records, readDeviceWidePromptTimes())
            if (cooldownUntil > 0L) {
                MindlayerLog.w(
                    TAG,
                    "Consent dismiss escalation for $pkg: count=$newCount, " +
                        "silent cooldown until $cooldownUntil",
                )
            }
            newCount
        }
    }

    /**
     * Clear all attempt tracking for [pkg]/[sig]. Called when the user grants
     * consent or commits an explicit (24h / permanent) denial — both
     * supersede the dismiss-escalation state.
     */
    fun clear(pkg: String, sig: String) {
        withFileLock {
            val records = readAttempts().filterNot {
                it.packageName == pkg && it.signingCertSha256.equals(sig, ignoreCase = true)
            }
            writeState(records, readDeviceWidePromptTimes())
        }
    }

    @VisibleForTesting
    internal fun dismissCountFor(pkg: String, sig: String): Int =
        readAttempts().firstOrNull {
            it.packageName == pkg && it.signingCertSha256.equals(sig, ignoreCase = true)
        }?.dismissCount ?: 0

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

    private fun writeState(records: List<AttemptRecord>, deviceWideTimes: List<Long>) {
        val attempts = JSONArray()
        for (r in records.sortedBy { it.packageName }) {
            attempts.put(
                JSONObject().apply {
                    put("pkg", r.packageName)
                    put("sig", r.signingCertSha256)
                    put("firstSeenAtMs", r.firstSeenAtMs)
                    put("lastShownAtMs", r.lastShownAtMs)
                    put("dismissCount", r.dismissCount)
                    put("silentCooldownUntilMs", r.silentCooldownUntilMs)
                },
            )
        }
        val times = JSONArray()
        for (t in deviceWideTimes.sorted()) times.put(t)
        val envelope = JSONObject().apply {
            put("version", SIGNED_FILE_VERSION)
            put(ATTEMPTS_KEY, attempts)
            put(DEVICE_WIDE_KEY, times)
            put(MAC_KEY, hmac(canonicalPayload(attempts, times)))
        }
        atomicWrite(attemptsFile, envelope.toString())
    }

    private fun readEnvelope(): JSONObject? {
        if (!attemptsFile.exists()) return null
        val raw = try { attemptsFile.readText() } catch (_: IOException) { return null }
        val trimmed = raw.trim()
        if (trimmed.isEmpty() || !trimmed.startsWith("{")) return null
        return try {
            val envelope = JSONObject(trimmed)
            val version = envelope.optInt("version", -1)
            if (version != SIGNED_FILE_VERSION) {
                MindlayerLog.w(TAG, "Rejected unknown-version consent_attempts (version=$version)")
                return null
            }
            val attempts = envelope.optJSONArray(ATTEMPTS_KEY) ?: JSONArray()
            val times = envelope.optJSONArray(DEVICE_WIDE_KEY) ?: JSONArray()
            val mac = envelope.optString(MAC_KEY)
            if (!verifyMac(canonicalPayload(attempts, times), mac)) {
                MindlayerLog.w(TAG, "Rejected tampered consent_attempts file")
                return null
            }
            envelope
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Failed to parse consent_attempts", throwable = t)
            null
        }
    }

    private fun readAttempts(): List<AttemptRecord> {
        val array = readEnvelope()?.optJSONArray(ATTEMPTS_KEY) ?: return emptyList()
        return try {
            buildList(array.length()) {
                for (i in 0 until array.length()) {
                    val o = array.getJSONObject(i)
                    add(
                        AttemptRecord(
                            packageName = o.getString("pkg"),
                            signingCertSha256 = o.getString("sig"),
                            firstSeenAtMs = o.optLong("firstSeenAtMs", 0L),
                            lastShownAtMs = o.optLong("lastShownAtMs", 0L),
                            dismissCount = o.optInt("dismissCount", 0),
                            silentCooldownUntilMs = o.optLong("silentCooldownUntilMs", 0L),
                        ),
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun readDeviceWidePromptTimes(): List<Long> {
        val array = readEnvelope()?.optJSONArray(DEVICE_WIDE_KEY) ?: return emptyList()
        return try {
            buildList(array.length()) {
                for (i in 0 until array.length()) add(array.getLong(i))
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

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
     * Domain-separated canonical pre-image. The `attempts` array and the
     * device-wide time ring are both authenticated so neither the dismiss
     * counters/cooldowns nor the throttle ring can be forged by an offline
     * editor.
     */
    private fun canonicalPayload(attempts: JSONArray, times: JSONArray): String = buildString {
        append("v=").append(SIGNED_FILE_VERSION).append("|k=").append(ATTEMPTS_KEY).append('|')
        append('[')
        for (i in 0 until attempts.length()) {
            if (i > 0) append(',')
            val o = attempts.getJSONObject(i)
            append('{')
            append("\"pkg\":").append(JSONObject.quote(o.getString("pkg")))
            append(",\"sig\":").append(JSONObject.quote(o.getString("sig")))
            append(",\"firstSeenAtMs\":").append(o.optLong("firstSeenAtMs", 0L))
            append(",\"lastShownAtMs\":").append(o.optLong("lastShownAtMs", 0L))
            append(",\"dismissCount\":").append(o.optInt("dismissCount", 0))
            append(",\"silentCooldownUntilMs\":").append(o.optLong("silentCooldownUntilMs", 0L))
            append('}')
        }
        append("]|dw=[")
        for (i in 0 until times.length()) {
            if (i > 0) append(',')
            append(times.getLong(i))
        }
        append(']')
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
        private const val TAG = "ConsentAttemptStore"

        private val PROCESS_LOCKS = ConcurrentHashMap<String, ReentrantLock>()

        private const val SIGNED_FILE_VERSION = 1
        private const val ATTEMPTS_KEY = "attempts"
        private const val DEVICE_WIDE_KEY = "deviceWidePromptTimes"
        private const val MAC_KEY = "mac"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val HMAC_KEY_BYTES = 32
        private val HEX_SHA256 = Regex("(?i)^[0-9a-f]{64}$")

        /** Dismiss count at which the next request earns a 1-hour silent cooldown. */
        const val DISMISS_COOLDOWN_1H_THRESHOLD = 3

        /** Dismiss count at which the next request earns a 24-hour silent cooldown. */
        const val DISMISS_COOLDOWN_24H_THRESHOLD = 4

        const val COOLDOWN_1H_MS = 60L * 60 * 1000
        const val COOLDOWN_24H_MS = 24L * 60 * 60 * 1000

        /** Device-wide throttle: max completed prompts per window across all callers. */
        const val DEVICE_WIDE_MAX_PROMPTS = 3
        const val DEVICE_WIDE_WINDOW_MS = 10L * 60 * 1000

        /** Hard cap on the persisted device-wide time ring. */
        private const val DEVICE_WIDE_RING_CAP = 32
    }
}
