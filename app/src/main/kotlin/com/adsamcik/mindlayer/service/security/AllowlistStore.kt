package com.adsamcik.mindlayer.service.security

import android.content.Context
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
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

data class AllowlistEntry(
    val packageName: String,
    val signingCertSha256: String,
    val grantedAtMs: Long,
    val displayName: String? = null,
)

data class PendingApproval(
    val packageName: String,
    val signingCertSha256: String,
    val firstRequestedAtMs: Long,
    val displayName: String? = null,
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
    private val hmacKeyFile: File = File(baseDir, "allowlist.hmac")

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

    fun approve(pkg: String, sigSha256: String, displayName: String? = null) {
        withFileLock {
            val now = System.currentTimeMillis()
            val current = readEntries()
            val updated = current.filterNot { it.packageName == pkg } +
                AllowlistEntry(pkg, sigSha256, now, displayName)
            writeEntries(updated)
            _entries.value = updated

            val pendingUpdated = readPending().filterNot { it.packageName == pkg }
            writePending(pendingUpdated)
            _pending.value = pendingUpdated
        }
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
     * Used by the dashboard UI so the user can approve/deny. Deduplicates by
     * packageName.
     */
    fun recordPending(pkg: String, sigSha256: String, displayName: String? = null) {
        withFileLock {
            val current = readPending()
            val existing = current.firstOrNull { it.packageName == pkg }
            if (existing != null && existing.signingCertSha256.equals(sigSha256, ignoreCase = true)) {
                return@withFileLock
            }
            val updated = current.filterNot { it.packageName == pkg } +
                PendingApproval(pkg, sigSha256, System.currentTimeMillis(), displayName)
            writePending(updated)
            _pending.value = updated
        }
    }

    fun denyPending(pkg: String) {
        withFileLock {
            val current = readPending()
            val updated = current.filterNot { it.packageName == pkg }
            if (updated.size == current.size) return@withFileLock
            writePending(updated)
            _pending.value = updated
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
            })
        }
        atomicWrite(pendingFile, signedEnvelope(PENDING_KEY, array).toString())
    }

    private fun atomicWrite(target: File, content: String) {
        val tmp = File(target.parentFile, target.name + ".tmp")
        try {
            tmp.writeText(content)
            if (!tmp.renameTo(target)) {
                // Fallback — some filesystems refuse cross-fs renames; do best-effort copy.
                target.writeText(content)
                tmp.delete()
            }
        } catch (e: IOException) {
            tmp.delete()
            throw e
        }
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
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    private fun readSignedArray(file: File, arrayKey: String): JSONArray? {
        if (!file.exists()) return null
        val raw = try { file.readText() } catch (_: IOException) { return null }
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        return try {
            if (trimmed.startsWith("[")) {
                MindlayerLog.w(TAG, "Rejected unsigned legacy allowlist file ${file.name}")
                return null
            } else {
                val envelope = JSONObject(trimmed)
                val array = envelope.optJSONArray(arrayKey) ?: return null
                val mac = envelope.optString(MAC_KEY)
                if (!verifyMac(canonicalArray(array, arrayKey), mac)) {
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

    private fun migrateLegacyArray(file: File, arrayKey: String) {
        if (!file.exists()) return
        val raw = try { file.readText() } catch (_: IOException) { return }
        val trimmed = raw.trim()
        if (!trimmed.startsWith("[")) return
        val array = try {
            JSONArray(trimmed)
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Failed to migrate legacy allowlist file ${file.name}", throwable = t)
            return
        }
        atomicWrite(file, signedEnvelope(arrayKey, array).toString())
    }

    private fun signedEnvelope(arrayKey: String, array: JSONArray): JSONObject {
        return JSONObject().apply {
            put("version", SIGNED_FILE_VERSION)
            put(arrayKey, array)
            put(MAC_KEY, hmac(canonicalArray(array, arrayKey)))
        }
    }

    private fun canonicalArray(array: JSONArray, arrayKey: String): String = buildString {
        append('[')
        for (i in 0 until array.length()) {
            if (i > 0) append(',')
            val item = array.getJSONObject(i)
            when (arrayKey) {
                ENTRIES_KEY -> appendCanonicalObject(
                    item,
                    timestampKey = "grantedAtMs",
                )
                PENDING_KEY -> appendCanonicalObject(
                    item,
                    timestampKey = "firstRequestedAtMs",
                )
                else -> throw IllegalArgumentException("Unknown allowlist array key: $arrayKey")
            }
        }
        append(']')
    }

    private fun StringBuilder.appendCanonicalObject(item: JSONObject, timestampKey: String) {
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

    private fun loadOrCreateHmacKey(): ByteArray {
        if (hmacKeyFile.exists()) {
            val encoded = hmacKeyFile.readText().trim()
            val decoded = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
            if (decoded != null && decoded.size >= HMAC_KEY_BYTES) return decoded
        }
        val key = ByteArray(HMAC_KEY_BYTES)
        SecureRandom().nextBytes(key)
        atomicWrite(hmacKeyFile, Base64.getEncoder().encodeToString(key))
        return key
    }

    companion object {
        private const val TAG = "AllowlistStore"
        const val DEFAULT_DIR_NAME = "mindlayer_allowlist"
        private const val SIGNED_FILE_VERSION = 1
        private const val ENTRIES_KEY = "entries"
        private const val PENDING_KEY = "pending"
        private const val MAC_KEY = "mac"
        private const val HMAC_ALGORITHM = "HmacSHA256"
        private const val HMAC_KEY_BYTES = 32
        private val HEX_SHA256 = Regex("(?i)^[0-9a-f]{64}$")
    }
}
