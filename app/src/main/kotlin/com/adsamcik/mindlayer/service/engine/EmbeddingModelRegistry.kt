package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.adsamcik.mindlayer.service.BuildConfig
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryFileLock
import com.adsamcik.mindlayer.service.modeldelivery.ModelFamily
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers installed EmbeddingGemma LiteRT model pairs.
 *
 * Phase A intentionally ships no bundled embedding model. In normal installs
 * discovery therefore returns an empty list until Phase D adds the
 * `gemma_embed_model` Asset Pack containing `embedding-*.tflite`, a
 * `sentencepiece.model` tokenizer, and integrity metadata.
 *
 * Trust ranking mirrors [ModelRegistry]: verified on-demand delivery, `filesDir`,
 * external files, cache, then debug-only `/data/local/tmp` sideloads. A
 * candidate is accepted only when the `.tflite` model and sidecar tokenizer
 * are both present in the same directory.
 */
object EmbeddingModelRegistry {

    private const val TAG = "EmbeddingModelRegistry"
    private const val MODEL_EXTENSION = ".tflite"
    private const val TOKENIZER_EXTENSION = ".spm.model"
    private const val DEFAULT_TOKENIZER_NAME = "sentencepiece.model"
    private const val INTEGRITY_MANIFEST = "embedding_model_integrity.json"
    private const val LEGACY_INTEGRITY_MANIFEST = "model_integrity.json"
    private val SAFE_MODEL_NAME_PATTERN = Regex("^embedding-[A-Za-z0-9_.-]+\\.tflite$")
    private val SHA256_REGEX = Regex("(?i)^[0-9a-f]{64}$")

    private enum class Origin { MODEL_DELIVERY, FILES_DIR, EXTERNAL_FILES, CACHE_DIR, SIDELOAD }

    fun discoverModels(
        context: Context,
        requireIntegrity: Boolean = !isDebuggable(context),
        deliveryLockHeld: Boolean = false,
    ): List<EmbeddingModelInfo> {
        if (!deliveryLockHeld) {
            return ModelDeliveryFileLock.withLock(context.filesDir, ModelFamily.EMBEDDINGS) {
                discoverModels(context, requireIntegrity, deliveryLockHeld = true)
            }
        }
        if (
            ModelDeliveryFileLock.isRemovalAuthoritative(
                context.filesDir,
                ModelFamily.EMBEDDINGS,
                lockHeld = true,
            )
        ) {
            return emptyList()
        }
        val seen = mutableSetOf<String>()
        val models = mutableListOf<Pair<Origin, EmbeddingModelInfo>>()
        val manifest = loadIntegrityManifest(context)

        fun scanDir(origin: Origin, dir: File?) {
            if (dir == null || !dir.isDirectory) return
            val dirCanonical = try { dir.canonicalFile } catch (_: Exception) { dir.absoluteFile }
            dir.listFiles()
                ?.filter { it.isFile && it.name.endsWith(MODEL_EXTENSION) }
                ?.forEach { file ->
                    val info = candidateFromFile(
                        file = file,
                        dirCanonical = dirCanonical,
                        manifest = manifest,
                        requireIntegrity = requireIntegrity,
                        isDeliveredArtifact = origin == Origin.MODEL_DELIVERY,
                        isReleaseBuild = !isDebuggable(context),
                    )
                        ?: return@forEach
                    if (file.name in seen) return@forEach
                    seen += file.name
                    models += origin to info
                }
        }

        val scanDelivered = {
            scanDir(
                Origin.MODEL_DELIVERY,
                ModelDeliveryFileLock.familyDir(context.filesDir, ModelFamily.EMBEDDINGS),
            )
        }
        runCatching {
            if (deliveryLockHeld) {
                scanDelivered()
            } else {
                ModelDeliveryFileLock.withLock(context.filesDir, ModelFamily.EMBEDDINGS) {
                    scanDelivered()
                }
            }
        }.onFailure { error ->
            MindlayerLog.w(
                TAG,
                "On-demand embedding model scan deferred: ${error.safeLabel()}",
                throwable = null,
            )
        }
        scanDir(Origin.FILES_DIR, context.filesDir)
        scanDir(Origin.EXTERNAL_FILES, context.getExternalFilesDir(null))
        scanDir(Origin.CACHE_DIR, context.cacheDir)

        if (BuildConfig.DEBUG) {
            try {
                val tmp = File("/data/local/tmp")
                warnIfSideloadInaccessible(context, tmp)
                scanDir(Origin.SIDELOAD, tmp)
            } catch (_: SecurityException) {
                // Not accessible — ignore.
            }
        }

        MindlayerLog.i(TAG, "Discovered ${models.size} embedding model(s)")
        return models
            .sortedWith(
                compareBy<Pair<Origin, EmbeddingModelInfo>> { it.first.ordinal }
                    .thenByDescending { it.second.sizeBytes },
            )
            .map { it.second }
    }

    fun getDefaultModel(models: List<EmbeddingModelInfo>): EmbeddingModelInfo? = models.firstOrNull()

    fun findModelById(models: List<EmbeddingModelInfo>, id: String): EmbeddingModelInfo? =
        models.find { it.id == id }

    fun deriveDisplayName(filename: String): String {
        val stem = filename.removeSuffix(MODEL_EXTENSION)
        return stem.split("-").joinToString(" ") { token ->
            token.replaceFirstChar { ch ->
                if (ch.isLowerCase()) ch.titlecase(Locale.ROOT) else ch.toString()
            }
        }
    }

    private fun candidateFromFile(
        file: File,
        dirCanonical: File,
        manifest: Map<String, IntegrityMetadata>,
        requireIntegrity: Boolean,
        isDeliveredArtifact: Boolean,
        isReleaseBuild: Boolean,
    ): EmbeddingModelInfo? {
        if (!SAFE_MODEL_NAME_PATTERN.matches(file.name)) {
            MindlayerLog.w(TAG, "Skipping embedding model with unexpected name: ${file.name}")
            return null
        }
        if (Files.isSymbolicLink(file.toPath())) {
            MindlayerLog.w(TAG, "Skipping symlink in embedding model dir: ${file.name}")
            return null
        }
        val canonical = try { file.canonicalFile } catch (_: Exception) { null }
        if (canonical == null || canonical.parentFile?.canonicalPath != dirCanonical.canonicalPath) {
            MindlayerLog.w(TAG, "Skipping embedding model outside scan dir (path traversal?): ${file.name}")
            return null
        }
        val tokenizer = findTokenizerFor(file) ?: run {
            MindlayerLog.w(TAG, "Skipping embedding model without tokenizer: ${file.name}")
            return null
        }
        if (Files.isSymbolicLink(tokenizer.toPath())) {
            MindlayerLog.w(TAG, "Skipping tokenizer symlink for embedding model: ${file.name}")
            return null
        }
        val tokenizerCanonical = try { tokenizer.canonicalFile } catch (_: Exception) { null }
        if (tokenizerCanonical == null || tokenizerCanonical.parentFile?.canonicalPath != dirCanonical.canonicalPath) {
            MindlayerLog.w(TAG, "Skipping tokenizer outside scan dir for embedding model: ${file.name}")
            return null
        }
        val verification = verifyModelFile(
            file,
            manifest[file.name],
            requireIntegrity,
            isDeliveredArtifact,
            isReleaseBuild,
        )
        val tokenizerVerification = verifyModelFile(
            tokenizer,
            manifest[tokenizer.name],
            requireIntegrity,
            isDeliveredArtifact,
            isReleaseBuild,
        )
        if (!verification.accepted || !tokenizerVerification.accepted) {
            MindlayerLog.w(TAG, "Skipping embedding pair without valid integrity metadata: ${file.name}")
            return null
        }
        return buildModelInfo(file, tokenizer, verification.sha256)
    }

    private fun findTokenizerFor(model: File): File? {
        val specific = File(model.parentFile, "${model.nameWithoutExtension}$TOKENIZER_EXTENSION")
        if (specific.isFile) return specific
        val shared = File(model.parentFile, DEFAULT_TOKENIZER_NAME)
        return shared.takeIf { it.isFile }
    }

    private fun tokenizerAssetName(modelId: String, assetNames: Set<String>): String? {
        val specific = "$modelId$TOKENIZER_EXTENSION"
        return when {
            specific in assetNames -> specific
            DEFAULT_TOKENIZER_NAME in assetNames -> DEFAULT_TOKENIZER_NAME
            else -> null
        }
    }

    private fun buildModelInfo(file: File, tokenizer: File, sha256: String?): EmbeddingModelInfo =
        EmbeddingModelInfo(
            id = file.nameWithoutExtension,
            displayName = deriveDisplayName(file.name),
            modelPath = file.absolutePath,
            tokenizerPath = tokenizer.absolutePath,
            sizeBytes = file.length(),
            nativeDim = 768,
            supportedDims = listOf(768, 512, 256, 128),
            maxContextTokens = 2048,
            sha256 = sha256,
        )

    private fun verifyModelFile(
        file: File,
        manifestEntry: IntegrityMetadata?,
        requireIntegrity: Boolean,
        isDeliveredArtifact: Boolean,
        isReleaseBuild: Boolean,
    ): VerificationResult {
        // S-5: on integrity-enforcing (release) builds, the expected digest
        // must come ONLY from the packaged, build-pinned manifest. A
        // sidecar integrity file sitting next to the model is
        // attacker-controllable (a writable model dir / post-discovery
        // swap) and must never be trusted as the source of truth. Debug
        // builds keep the sidecar fallback for local model iteration.
        val expected = if (requireIntegrity && isDeliveredArtifact && isReleaseBuild) {
            pinnedIntegrity(file.name)
        } else if (requireIntegrity) {
            pinnedIntegrity(file.name) ?: manifestEntry
        } else {
            pinnedIntegrity(file.name) ?: manifestEntry ?: readSidecarIntegrity(file)
        }
        if (expected == null) return VerificationResult(accepted = !requireIntegrity, sha256 = null)
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
        val raw = listOf(INTEGRITY_MANIFEST, LEGACY_INTEGRITY_MANIFEST)
            .firstNotNullOfOrNull { manifestName ->
                try {
                    context.assets.open(manifestName).bufferedReader().use { it.readText() }
                } catch (_: Exception) {
                    null
                }
            }
            ?: return emptyMap()
        return try {
            val root = Json.parseToJsonElement(raw).jsonObject
            val singleFile = root.stringOrNull("modelFile")?.takeIf { it.endsWith(MODEL_EXTENSION) }
            val singleSha = root.stringOrNull("sha256")?.lowercase(Locale.ROOT)
            if (singleFile != null && singleSha != null && SHA256_REGEX.matches(singleSha)) {
                return mapOf(singleFile to IntegrityMetadata(singleSha, sizeBytes = null))
            }
            val models = root["models"] as? JsonArray ?: return emptyMap()
            buildMap {
                for (element in models) {
                    val model = element as? JsonObject ?: continue
                    val filename = model.stringOrNull("filename")?.takeIf { filename ->
                        filename.endsWith(MODEL_EXTENSION) ||
                            filename.endsWith(TOKENIZER_EXTENSION) ||
                            filename == DEFAULT_TOKENIZER_NAME
                    } ?: continue
                    val sha256 = model.stringOrNull("sha256")?.lowercase(Locale.ROOT) ?: continue
                    if (!SHA256_REGEX.matches(sha256)) continue
                    val size = model.longOrNull("sizeBytes")?.takeIf { it > 0L }
                    put(filename, IntegrityMetadata(sha256, size))
                }
            }
        } catch (e: Exception) {
            MindlayerLog.w(TAG, "Embedding model integrity manifest is invalid: ${e.safeLabel()}")
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

    private fun pinnedIntegrity(filename: String): IntegrityMetadata? {
        val sha = when (filename) {
            "embedding-gemma-300m-v1.tflite" -> BuildConfig.EMBEDDING_MODEL_SHA256
            "embedding-gemma-300m-v1.spm.model" -> BuildConfig.EMBEDDING_TOKENIZER_SHA256
            else -> ""
        }
        return sha.takeIf { SHA256_REGEX.matches(it) && it != "0".repeat(64) }
            ?.let { IntegrityMetadata(it, sizeBytes = null) }
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
     * the full rationale; this is the mirror for the embedding registry.
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

    private fun JsonObject.stringOrNull(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    private fun JsonObject.longOrNull(name: String): Long? =
        (this[name] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()

    private data class IntegrityMetadata(
        val sha256: String,
        val sizeBytes: Long?,
    )

    private data class VerificationResult(
        val accepted: Boolean,
        val sha256: String?,
    )
}
