package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale

/**
 * Discovers installed LLM model files on the device.
 *
 * Mindlayer may detect multiple candidates internally, but runtime behavior is
 * single-model only: the best available model is selected once and exposed to
 * clients as the device's single installed model.
 */
object ModelRegistry {

    private const val TAG = "ModelRegistry"
    private const val MODEL_EXTENSION = ".litertlm"
    private const val INTEGRITY_MANIFEST = "model_integrity.json"
    private val SHA256_REGEX = Regex("(?i)^[0-9a-f]{64}$")

    /**
     * Scan device directories for `.litertlm` model files.
     *
     * Search order (earlier wins for deduplication):
     *  1. `context.filesDir`
     *  2. `context.getExternalFilesDir(null)`
     *  3. `context.cacheDir`
     *  4. `/data/local/tmp/` (debug sideload)
     *  5. Play AI Pack assets (extracted to filesDir on first access)
     *
     * @return models sorted by size descending (largest first).
     */
    fun discoverModels(
        context: Context,
        requireIntegrity: Boolean = !isDebuggable(context),
    ): List<ModelInfo> {
        val seen = mutableSetOf<String>() // filenames already collected
        val models = mutableListOf<ModelInfo>()
        val manifest = loadIntegrityManifest(context)

        fun scanDir(dir: File?) {
            if (dir == null || !dir.isDirectory) return
            val dirCanonical = try { dir.canonicalFile } catch (_: Exception) { dir.absoluteFile }
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(MODEL_EXTENSION) }
                ?.forEach { file ->
                    // Reject symlinks — prevents attacker-controlled mmap targets via linked paths
                    if (Files.isSymbolicLink(file.toPath())) {
                        MindlayerLog.w(TAG, "Skipping symlink in model dir: ${file.name}")
                        return@forEach
                    }
                    // Defense-in-depth: canonical path must still be a direct child of dir
                    val canonical = try { file.canonicalFile } catch (_: Exception) { null }
                    if (canonical == null || canonical.parentFile?.canonicalPath != dirCanonical.canonicalPath) {
                        MindlayerLog.w(TAG, "Skipping model outside scan dir (path traversal?): ${file.name}")
                        return@forEach
                    }
                    if (file.name !in seen) {
                        val verification = verifyModelFile(file, manifest[file.name], requireIntegrity)
                        if (verification.accepted) {
                            seen += file.name
                            models += buildModelInfo(file, verification.sha256)
                        } else {
                            Log.w(TAG, "Skipping model without valid integrity metadata: ${file.name}")
                        }
                    }
                }
        }

        // 1–3: Standard app storage
        scanDir(context.filesDir)
        scanDir(context.getExternalFilesDir(null))
        scanDir(context.cacheDir)

        // 4: Debug sideload directory
        if (isDebuggable(context)) {
            try {
                scanDir(File("/data/local/tmp"))
            } catch (_: SecurityException) {
                // Not accessible on non-debug builds — ignore
            }
        }

        // 5: Play AI Pack assets — extract any .litertlm assets not yet on disk
        try {
            val assetNames = context.assets.list("") ?: emptyArray()
            for (name in assetNames) {
                if (!name.endsWith(MODEL_EXTENSION)) continue
                if (name in seen) continue
                val expected = manifest[name]
                if (requireIntegrity && expected == null) {
                    Log.w(TAG, "Skipping AI pack model without integrity metadata: $name")
                    continue
                }

                val extracted = File(context.filesDir, name)
                if (!extracted.exists()) {
                    Log.i(TAG, "Extracting model from AI pack: $name")
                    context.assets.open(name).use { input ->
                        extracted.outputStream().use { output ->
                            input.copyTo(output, bufferSize = 8 * 1024 * 1024)
                        }
                    }
                    Log.i(TAG, "Extracted: ${extracted.length() / 1_048_576}MB")
                }
                val verification = verifyModelFile(extracted, expected, requireIntegrity)
                if (verification.accepted) {
                    seen += name
                    models += buildModelInfo(extracted, verification.sha256)
                } else {
                    extracted.delete()
                    Log.w(TAG, "Skipping AI pack model with invalid integrity metadata: $name")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI pack asset scan failed: ${e.message}")
        }

        Log.i(TAG, "Discovered ${models.size} model(s)")
        return models.sortedByDescending { it.sizeBytes }
    }

    /**
     * Pick the single model Mindlayer exposes publicly from [models].
     *
     * - Single model → that's the default.
     * - Multiple → prefer the largest (most capable).
     *
     * Returns a copy with [ModelInfo.isDefault] = true, or `null` if the list
     * is empty.
     */
    fun getDefaultModel(models: List<ModelInfo>): ModelInfo? {
        if (models.isEmpty()) return null
        // List is already sorted by size descending, so first is largest
        val best = models.first()
        return best.copy(isDefault = true)
    }

    /** Find a model by its [id] (filename stem). */
    fun findModelById(models: List<ModelInfo>, id: String): ModelInfo? =
        models.find { it.id == id }

    /**
     * Convert a `.litertlm` filename into a human-readable display name.
     *
     * Rules:
     *  - Strip the `.litertlm` extension
     *  - Replace `-` with space
     *  - Capitalise each word
     *  - Expand abbreviation: `it` → `Instruct`
     */
    fun deriveDisplayName(filename: String): String {
        val stem = filename.removeSuffix(MODEL_EXTENSION)
        return stem.split("-").joinToString(" ") { token ->
            when (token.lowercase(Locale.ROOT)) {
                "it" -> "Instruct"
                else -> token.replaceFirstChar { ch ->
                    if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
                }
            }
        }
    }

    // -- Internal helpers -----------------------------------------------------

    private fun buildModelInfo(file: File, sha256: String?) : ModelInfo {
        val id = file.nameWithoutExtension
        return ModelInfo(
            id = id,
            displayName = deriveDisplayName(file.name),
            path = file.absolutePath,
            sizeBytes = file.length(),
            sha256 = sha256,
        )
    }

    private fun verifyModelFile(
        file: File,
        manifestEntry: IntegrityMetadata?,
        requireIntegrity: Boolean,
    ): VerificationResult {
        val expected = manifestEntry ?: readSidecarIntegrity(file)
        if (expected == null) {
            return VerificationResult(accepted = !requireIntegrity, sha256 = null)
        }
        if (expected.sizeBytes != null && expected.sizeBytes != file.length()) {
            return VerificationResult(accepted = false, sha256 = null)
        }
        val actualSha256 = sha256(file)
        return VerificationResult(
            accepted = actualSha256.equals(expected.sha256, ignoreCase = true),
            sha256 = actualSha256,
        )
    }

    private fun loadIntegrityManifest(context: Context): Map<String, IntegrityMetadata> {
        val raw = try {
            context.assets.open(INTEGRITY_MANIFEST).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return emptyMap()
        }
        return try {
            val root = JSONObject(raw)
            val models = root.optJSONArray("models") ?: return emptyMap()
            buildMap {
                for (i in 0 until models.length()) {
                    val model = models.getJSONObject(i)
                    val filename = model.optString("filename").takeIf { it.endsWith(MODEL_EXTENSION) }
                        ?: continue
                    val sha256 = model.optString("sha256").lowercase(Locale.ROOT)
                    if (!SHA256_REGEX.matches(sha256)) continue
                    val size = model.optLong("sizeBytes", -1L).takeIf { it > 0L }
                    put(filename, IntegrityMetadata(sha256, size))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Model integrity manifest is invalid: ${e.message}")
            emptyMap()
        }
    }

    private fun readSidecarIntegrity(file: File): IntegrityMetadata? {
        val candidates = listOf(
            File(file.parentFile, "${file.name}.sha256"),
            File(file.parentFile, "${file.nameWithoutExtension}.sha256"),
        )
        val sidecar = candidates.firstOrNull { it.isFile } ?: return null
        val sha256 = sidecar.readText()
            .lineSequence()
            .map { it.trim().substringBefore(' ').lowercase(Locale.ROOT) }
            .firstOrNull { SHA256_REGEX.matches(it) }
            ?: return null
        return IntegrityMetadata(sha256, sizeBytes = null)
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        FileInputStream(file).use { input ->
            while (true) {
                val read = input.read(buffer)
                if (read == -1) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    private data class IntegrityMetadata(
        val sha256: String,
        val sizeBytes: Long?,
    )

    private data class VerificationResult(
        val accepted: Boolean,
        val sha256: String?,
    )
}
