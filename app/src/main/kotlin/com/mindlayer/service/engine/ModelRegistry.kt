package com.mindlayer.service.engine

import android.content.Context
import android.util.Log
import java.io.File
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
    fun discoverModels(context: Context): List<ModelInfo> {
        val seen = mutableSetOf<String>() // filenames already collected
        val models = mutableListOf<ModelInfo>()

        fun scanDir(dir: File?) {
            if (dir == null || !dir.isDirectory) return
            dir.listFiles()?.filter { it.isFile && it.name.endsWith(MODEL_EXTENSION) }
                ?.forEach { file ->
                    if (file.name !in seen) {
                        seen += file.name
                        models += buildModelInfo(file)
                    }
                }
        }

        // 1–3: Standard app storage
        scanDir(context.filesDir)
        scanDir(context.getExternalFilesDir(null))
        scanDir(context.cacheDir)

        // 4: Debug sideload directory
        try {
            scanDir(File("/data/local/tmp"))
        } catch (_: SecurityException) {
            // Not accessible on non-debug builds — ignore
        }

        // 5: Play AI Pack assets — extract any .litertlm assets not yet on disk
        try {
            val assetNames = context.assets.list("") ?: emptyArray()
            for (name in assetNames) {
                if (!name.endsWith(MODEL_EXTENSION)) continue
                if (name in seen) continue

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
                seen += name
                models += buildModelInfo(extracted)
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

    private fun buildModelInfo(file: File): ModelInfo {
        val id = file.nameWithoutExtension
        return ModelInfo(
            id = id,
            displayName = deriveDisplayName(file.name),
            path = file.absolutePath,
            sizeBytes = file.length(),
        )
    }
}
