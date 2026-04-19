package com.adsamcik.mindlayer.service.security

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.channels.FileLock

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

    private val _entries = MutableStateFlow(readEntries())
    val entries: StateFlow<List<AllowlistEntry>> = _entries.asStateFlow()

    private val _pending = MutableStateFlow(readPending())
    val pending: StateFlow<List<PendingApproval>> = _pending.asStateFlow()

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
            })
        }
        atomicWrite(pendingFile, array.toString())
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
                        )
                    )
                }
            }
        } catch (_: Throwable) {
            emptyList()
        }
    }

    companion object {
        const val DEFAULT_DIR_NAME = "mindlayer_allowlist"
    }
}
