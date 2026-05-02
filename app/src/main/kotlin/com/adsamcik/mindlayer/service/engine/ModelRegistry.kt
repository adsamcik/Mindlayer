package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.util.Log
import com.adsamcik.mindlayer.service.BuildConfig
import java.io.File
import java.util.Locale

/**
 * Discovers installed LLM model files on the device.
 *
 * Mindlayer may detect multiple candidates internally, but runtime behavior is
 * single-model only: the best available model is selected once and exposed to
 * clients as the device's single installed model.
 *
 * **Trust ranking (F-003)**: each discovered candidate is annotated with an
 * [origin] field. When choosing a default we prefer the most-trusted origin
 * (Play AI Pack > internal storage > cache > sideload), then by largest size
 * within that tier. The previous "largest wins regardless of origin" policy
 * let an attacker with `/data/local/tmp` write access shadow the legitimate
 * AI-Pack-extracted model with a backdoored larger file.
 *
 * **Sideload gating**: `/data/local/tmp/` is only scanned when
 * [BuildConfig.DEBUG] is true. The previous code's `try { … } catch
 * (SecurityException)` did not actually fail in release — `listFiles` on
 * `/data/local/tmp` returns the entries readable by the app UID. The
 * `BuildConfig.DEBUG` gate is the proper guard.
 */
object ModelRegistry {

    private const val TAG = "ModelRegistry"
    private const val MODEL_EXTENSION = ".litertlm"
    private val SAFE_NAME_PATTERN = Regex("^[A-Za-z0-9_.-]+\\.litertlm$")

    /** Strict ordering of trust tiers. Lower ordinal = more trusted. */
    private enum class Origin { AI_PACK, FILES_DIR, EXTERNAL_FILES, CACHE_DIR, SIDELOAD }

    /**
     * Scan device directories for `.litertlm` model files.
     *
     * Search order (later tiers add candidates only if not already found
     * from an earlier, more-trusted tier):
     *  1. Play AI Pack assets (extracted to filesDir on first access)
     *  2. `context.filesDir`
     *  3. `context.getExternalFilesDir(null)`
     *  4. `context.cacheDir`
     *  5. `/data/local/tmp/` — debug builds only
     */
    fun discoverModels(context: Context): List<ModelInfo> {
        val seen = mutableSetOf<String>()
        val models = mutableListOf<Pair<Origin, ModelInfo>>()

        fun scanDir(origin: Origin, dir: File?) {
            if (dir == null || !dir.isDirectory) return
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(MODEL_EXTENSION) }
                ?.forEach { file ->
                    if (file.name in seen) return@forEach
                    if (!SAFE_NAME_PATTERN.matches(file.name)) {
                        Log.w(TAG, "Skipping model with unexpected name: ${file.name}")
                        return@forEach
                    }
                    seen += file.name
                    models += origin to buildModelInfo(file)
                }
        }

        // 1: Play AI Pack assets — extract any .litertlm assets not yet on
        //    disk. We do this BEFORE scanning filesDir so the extracted
        //    artifact dominates over a sideload of the same name (see the
        //    `seen` dedup which we therefore prime with AI-Pack names).
        try {
            val assetNames = context.assets.list("") ?: emptyArray()
            for (name in assetNames) {
                if (!name.endsWith(MODEL_EXTENSION)) continue
                if (!SAFE_NAME_PATTERN.matches(name)) {
                    Log.w(TAG, "Skipping AI-Pack asset with unexpected name: $name")
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
                seen += name
                models += Origin.AI_PACK to buildModelInfo(extracted)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AI pack asset scan failed: ${e.message}")
        }

        // 2-4: Standard app storage
        scanDir(Origin.FILES_DIR, context.filesDir)
        scanDir(Origin.EXTERNAL_FILES, context.getExternalFilesDir(null))
        scanDir(Origin.CACHE_DIR, context.cacheDir)

        // 5: Sideload — debug builds only.
        if (BuildConfig.DEBUG) {
            try {
                scanDir(Origin.SIDELOAD, File("/data/local/tmp"))
            } catch (_: SecurityException) {
                // Not accessible — ignore
            }
        }

        Log.i(TAG, "Discovered ${models.size} model(s)")
        // Most-trusted origin first, then largest within tier.
        return models
            .sortedWith(
                compareBy<Pair<Origin, ModelInfo>> { it.first.ordinal }
                    .thenByDescending { it.second.sizeBytes },
            )
            .map { it.second }
    }

    /**
     * Pick the single model Mindlayer exposes publicly from [models].
     *
     * - Single model → that's the default.
     * - Multiple → first in the trust-ordered list returned by
     *   [discoverModels].
     *
     * Returns a copy with [ModelInfo.isDefault] = true, or `null` if the list
     * is empty.
     */
    fun getDefaultModel(models: List<ModelInfo>): ModelInfo? {
        if (models.isEmpty()) return null
        // List is already sorted by origin then size, so first is best.
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
