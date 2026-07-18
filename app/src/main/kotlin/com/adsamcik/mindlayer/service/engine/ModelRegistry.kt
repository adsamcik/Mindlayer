package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.adsamcik.mindlayer.service.BuildConfig
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryFileLock
import com.adsamcik.mindlayer.service.modeldelivery.ModelFamily
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers installed LLM model files on the device.
 *
 * Mindlayer may detect multiple candidates internally, but runtime behavior is
 * single-model only: the best available model is selected once and exposed to
 * clients as the device's single installed model.
 *
 * **Trust ranking (F-003)**: each discovered candidate is annotated with an
 * [origin] field. When choosing a default we prefer the most-trusted origin
 * (verified on-demand delivery > internal storage > cache > sideload), then by largest size
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
    private const val INTEGRITY_MANIFEST = "model_integrity.json"
    private val SHA256_REGEX = Regex("(?i)^[0-9a-f]{64}$")

    /**
     * Default image budget for vision-capable models. Conservative: most
     * single-image chat callers (Starlit Coffee bag scan, OCR-driver
     * sample, etc.) push exactly one image per turn. Bump per-model in
     * [buildModelInfo] when a model actually advertises more.
     */
    private const val DEFAULT_MAX_IMAGES_PER_TURN: Int = 1

    /** Strict ordering of trust tiers. Lower ordinal = more trusted. */
    private enum class Origin { MODEL_DELIVERY, FILES_DIR, EXTERNAL_FILES, CACHE_DIR, SIDELOAD }

    /**
     * Scan device directories for `.litertlm` model files.
     *
     * Search order (later tiers add candidates only if not already found
     * from an earlier, more-trusted tier):
     *  1. verified on-demand materialization in `filesDir/model_delivery/chat`
     *  2. `context.filesDir`
     *  3. `context.getExternalFilesDir(null)`
     *  4. `context.cacheDir`
     *  5. `/data/local/tmp/` — debug builds only
     *
     * Integrity gating: when [requireIntegrity] is true, every candidate
     * must have a matching SHA-256 manifest entry (or sidecar `.sha256`)
     * or it is silently skipped. The default — `!isDebuggable(context)`
     * — derives from the APPLICATION's runtime debuggable flag (not
     * `BuildConfig.DEBUG`), so a release-flagged install fails-closed
     * even when the unit-test JVM is running a debug variant. This
     * complements `EngineManager.verifyModelIntegrity` (which checks
     * `BuildConfig.MODEL_SHA256` at load time) by adding a second
     * fail-closed layer at discovery time.
     */
    fun discoverModels(
        context: Context,
        requireIntegrity: Boolean = !isDebuggable(context),
        deliveryLockHeld: Boolean = false,
    ): List<ModelInfo> {
        if (!deliveryLockHeld) {
            return ModelDeliveryFileLock.withLock(context.filesDir, ModelFamily.CHAT) {
                discoverModels(context, requireIntegrity, deliveryLockHeld = true)
            }
        }
        if (
            ModelDeliveryFileLock.isRemovalAuthoritative(
                context.filesDir,
                ModelFamily.CHAT,
                lockHeld = true,
            )
        ) {
            return emptyList()
        }
        val seen = mutableSetOf<String>()
        val models = mutableListOf<Pair<Origin, ModelInfo>>()
        val manifest = loadIntegrityManifest(context)

        fun scanDir(origin: Origin, dir: File?) {
            if (dir == null || !dir.isDirectory) return
            val dirCanonical = try { dir.canonicalFile } catch (_: Exception) { dir.absoluteFile }
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(MODEL_EXTENSION) }
                ?.forEach { file ->
                    if (file.name in seen) return@forEach
                    if (!SAFE_NAME_PATTERN.matches(file.name)) {
                        MindlayerLog.w(TAG, "Skipping model with unexpected name: ${file.name}")
                        return@forEach
                    }
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
                    val verification = verifyModelFile(
                        file = file,
                        manifestEntry = manifest[file.name],
                        requireIntegrity = requireIntegrity,
                        isDeliveredArtifact = origin == Origin.MODEL_DELIVERY,
                        isReleaseBuild = !isDebuggable(context),
                    )
                    if (!verification.accepted) {
                        MindlayerLog.w(TAG, "Skipping model without valid integrity metadata: ${file.name}")
                        return@forEach
                    }
                    seen += file.name
                    models += origin to buildModelInfo(file, verification.sha256)
                }
        }

        // Standard PAD exposes pack assets by filesystem path. The delivery
        // manager verifies and atomically materializes them before discovery;
        // registries never extract mutable AssetManager bytes themselves.
        val scanDelivered = {
            scanDir(
                Origin.MODEL_DELIVERY,
                ModelDeliveryFileLock.familyDir(context.filesDir, ModelFamily.CHAT),
            )
        }
        runCatching {
            if (deliveryLockHeld) {
                scanDelivered()
            } else {
                ModelDeliveryFileLock.withLock(context.filesDir, ModelFamily.CHAT) {
                    scanDelivered()
                }
            }
        }.onFailure { error ->
            MindlayerLog.w(
                TAG,
                "On-demand chat model scan deferred: ${error.safeLabel()}",
                throwable = null,
            )
        }

        // Migration/dev fallbacks remain deliberately lower trust.
        scanDir(Origin.FILES_DIR, context.filesDir)
        scanDir(Origin.EXTERNAL_FILES, context.getExternalFilesDir(null))
        scanDir(Origin.CACHE_DIR, context.cacheDir)

        // 5: Sideload — debug builds only.
        if (BuildConfig.DEBUG) {
            try {
                val tmp = File("/data/local/tmp")
                warnIfSideloadInaccessible(context, tmp)
                scanDir(Origin.SIDELOAD, tmp)
            } catch (_: SecurityException) {
                // Not accessible — ignore
            }
        }

        MindlayerLog.i(TAG, "Discovered ${models.size} model(s)")
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

    private fun buildModelInfo(file: File, sha256: String?) : ModelInfo {
        val id = file.nameWithoutExtension
        return ModelInfo(
            id = id,
            displayName = deriveDisplayName(file.name),
            path = file.absolutePath,
            sizeBytes = file.length(),
            sha256 = sha256,
            supportsVision = deriveSupportsVision(id),
            maxImagesPerTurn = if (deriveSupportsVision(id)) DEFAULT_MAX_IMAGES_PER_TURN else 0,
        )
    }

    /**
     * Heuristic: every Gemma 4 family model has a vision encoder bundled in
     * the `.litertlm` package. The registry intentionally over-includes
     * here — declaring vision on a text-only model just allocates the
     * vision executor at init (harmless), while under-declaring on a
     * multimodal model SIGSEGVs on first image (LiteRT-LM #1874).
     *
     * If a future text-only Gemma 4 ship breaks this assumption, switch to
     * reading the litertlm metadata header instead of id-pattern matching.
     */
    private fun deriveSupportsVision(id: String): Boolean =
        id.startsWith("gemma-4-", ignoreCase = true)

    private fun verifyModelFile(
        file: File,
        manifestEntry: IntegrityMetadata?,
        requireIntegrity: Boolean,
        isDeliveredArtifact: Boolean,
        isReleaseBuild: Boolean,
    ): VerificationResult {
        val pinned = BuildConfig.MODEL_SHA256
            .takeIf { SHA256_REGEX.matches(it) && it != "0".repeat(64) }
            ?.let { IntegrityMetadata(it, sizeBytes = null) }
        val expected = if (requireIntegrity && isDeliveredArtifact && isReleaseBuild) {
            pinned
        } else if (requireIntegrity && isDeliveredArtifact) {
            pinned ?: manifestEntry
        } else {
            pinned ?: manifestEntry ?: readSidecarIntegrity(file)
        }
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
            val singleFile = root.optString("modelFile").takeIf { it.endsWith(MODEL_EXTENSION) }
            val singleSha = root.optString("sha256").lowercase(Locale.ROOT)
            if (singleFile != null && SHA256_REGEX.matches(singleSha)) {
                return mapOf(singleFile to IntegrityMetadata(singleSha, sizeBytes = null))
            }
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
            MindlayerLog.w(TAG, "Model integrity manifest is invalid: ${e.message}")
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

    /**
     * One-shot warning when `/data/local/tmp` listing is denied on API
     * 31+. See [PaddleOcrModelRegistry.warnIfSideloadInaccessible] for
     * the full rationale; this is the mirror for the chat-model registry.
     */
    private val sideloadInaccessibleWarned = AtomicBoolean(false)

    private fun warnIfSideloadInaccessible(context: Context, dir: File) {
        if (sideloadInaccessibleWarned.get()) return
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
        if (!isDebuggable(context)) return
        if (dir.listFiles() != null) return
        if (!sideloadInaccessibleWarned.compareAndSet(false, true)) return
        val ext = context.getExternalFilesDir(null)?.absolutePath
            ?: "/sdcard/Android/data/<package>/files"
        MindlayerLog.w(
            TAG,
            "Cannot list ${dir.absolutePath} on API ${Build.VERSION.SDK_INT} (apps lose " +
                "directory-listing permission from Android 12 onward, even when individual " +
                "files inside are world-readable). Push dev models to $ext instead — the " +
                "registry already scans that path. See docs/models/DEV_MODELS.md.",
        )
    }

    private data class IntegrityMetadata(
        val sha256: String,
        val sizeBytes: Long?,
    )

    private data class VerificationResult(
        val accepted: Boolean,
        val sha256: String?,
    )
}
