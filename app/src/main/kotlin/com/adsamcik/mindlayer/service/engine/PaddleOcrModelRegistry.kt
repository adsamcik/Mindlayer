package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File
import java.io.FileInputStream
import java.nio.file.Files
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Discovers installed PaddleOCR PP-OCRv5 mobile model bundles.
 *
 * Mirrors [EmbeddingModelRegistry] in shape but loads a **bundle** of 4
 * artifacts: detection + recognition + (optional) orientation classifier
 * ``.tflite`` files plus a character dictionary ``.txt``.
 *
 * Trust ranking follows [ModelRegistry] / [EmbeddingModelRegistry]:
 *   1. Play AI Pack assets (in ``:paddleocr_model``'s asset directory)
 *   2. ``filesDir`` (extracted Play AI Pack or migration target)
 *   3. ``externalFilesDir`` (developer sideload)
 *   4. ``cacheDir`` (transient)
 *   5. ``/data/local/tmp`` (debuggable builds only)
 *
 * A bundle is accepted only when:
 *   - detection + recognition ``.tflite`` files are both present
 *   - dictionary ``.txt`` is present
 *   - all filenames match the safe pattern (no path traversal)
 *   - none of the paths are symlinks (no chroot escape)
 *   - **all** present artifacts' SHA-256 values match the integrity
 *     manifest when ``requireIntegrity = true`` (production builds)
 *
 * The orientation classifier is optional — when missing from the bundle
 * the registry still accepts the pair and surfaces
 * [PaddleOcrModelInfo.classifierPath] as null. The engine's pipeline
 * (PR C2) then skips the rotation step.
 */
object PaddleOcrModelRegistry {

    internal const val TAG = "PaddleOcrModelRegistry"
    internal const val MODEL_EXTENSION = ".tflite"
    internal const val DICT_EXTENSION = ".txt"
    internal const val INTEGRITY_MANIFEST = "paddleocr_model_integrity.json"

    // Filename patterns: paddleocr-ppocrv5-mobile-{det,rec,cls}.tflite +
    // paddleocr-ppocrv5-mobile-dict.txt. The PaddleOcrAssetPackTest in
    // PR B pins these names, so we can hardcode them and reject anything
    // else as a defense against substitution attacks via filesDir.
    internal val SAFE_DET_NAME = Regex("^paddleocr-ppocrv5-mobile-det\\.tflite$")
    internal val SAFE_REC_NAME = Regex("^paddleocr-ppocrv5-mobile-rec\\.tflite$")
    internal val SAFE_CLS_NAME = Regex("^paddleocr-ppocrv5-mobile-cls\\.tflite$")
    internal val SAFE_DICT_NAME = Regex("^paddleocr-ppocrv5-mobile-dict\\.txt$")

    internal val SHA256_REGEX = Regex("(?i)^[0-9a-f]{64}$")

    internal enum class Origin { AI_PACK, FILES_DIR, EXTERNAL_FILES, CACHE_DIR, SIDELOAD }

    /**
     * Discover all installed PaddleOCR bundles, ranked by trust.
     *
     * @param requireIntegrity when true (release builds), rejects any
     *   bundle whose artifacts are not in the integrity manifest or
     *   whose SHA-256 does not match. Debuggable builds default this
     *   to false so developers can drop unverified ``.tflite`` files
     *   into ``filesDir``/``/data/local/tmp`` while iterating.
     */
    fun discoverBundles(
        context: Context,
        requireIntegrity: Boolean = !isDebuggable(context),
    ): List<PaddleOcrModelInfo> {
        val manifest = loadIntegrityManifest(context)
        val seenIds = mutableSetOf<String>()
        val bundles = mutableListOf<Pair<Origin, PaddleOcrModelInfo>>()

        fun consider(origin: Origin, bundle: PaddleOcrModelInfo?) {
            if (bundle == null) return
            if (bundle.id in seenIds) return
            seenIds += bundle.id
            bundles += origin to bundle
        }

        // 1. AI-Pack assets — extract on first run to filesDir so the engine
        //    has stable file paths (LiteRT cannot read straight from AAB
        //    assets without an mmap-friendly file).
        consider(Origin.AI_PACK, bundleFromAssetPack(context, manifest, requireIntegrity))

        // 2. filesDir / external / cache scans
        consider(Origin.FILES_DIR, bundleFromDir(context.filesDir, manifest, requireIntegrity))
        consider(Origin.EXTERNAL_FILES, bundleFromDir(context.getExternalFilesDir(null), manifest, requireIntegrity))
        consider(Origin.CACHE_DIR, bundleFromDir(context.cacheDir, manifest, requireIntegrity))

        // 3. Sideload — debuggable builds only.
        if (isDebuggable(context)) {
            try {
                val tmp = File("/data/local/tmp")
                warnIfSideloadInaccessible(context, tmp)
                consider(Origin.SIDELOAD, bundleFromDir(tmp, manifest, requireIntegrity))
            } catch (_: SecurityException) {
                // /data/local/tmp not accessible — fine, ignore.
            }
        }

        MindlayerLog.i(TAG, "Discovered ${bundles.size} PaddleOCR bundle(s)")
        return bundles
            .sortedBy { it.first.ordinal }
            .map { it.second }
    }

    /** Return the default bundle (highest-trust origin). */
    fun getDefaultBundle(bundles: List<PaddleOcrModelInfo>): PaddleOcrModelInfo? =
        bundles.firstOrNull()

    /** Find a bundle by its [PaddleOcrModelInfo.id]. */
    fun findBundleById(bundles: List<PaddleOcrModelInfo>, id: String): PaddleOcrModelInfo? =
        bundles.find { it.id == id }

    // ── Asset-Pack extraction ────────────────────────────────────────────

    private fun bundleFromAssetPack(
        context: Context,
        manifest: Map<String, IntegrityMetadata>,
        requireIntegrity: Boolean,
    ): PaddleOcrModelInfo? {
        val assetNames = try {
            context.assets.list("")?.toSet().orEmpty()
        } catch (e: Exception) {
            MindlayerLog.w(TAG, "Asset-pack listing failed: ${e.safeLabel()}")
            return null
        }

        val detAsset = assetNames.firstOrNull { SAFE_DET_NAME.matches(it) } ?: return null
        val recAsset = assetNames.firstOrNull { SAFE_REC_NAME.matches(it) } ?: return null
        val dictAsset = assetNames.firstOrNull { SAFE_DICT_NAME.matches(it) } ?: return null
        val clsAsset = assetNames.firstOrNull { SAFE_CLS_NAME.matches(it) }

        if (requireIntegrity) {
            val required = listOfNotNull(detAsset, recAsset, dictAsset, clsAsset)
            if (required.any { it !in manifest }) {
                MindlayerLog.w(TAG, "Asset-pack bundle missing integrity entry; skipping.")
                return null
            }
        }

        val extracted = listOfNotNull(detAsset, recAsset, dictAsset, clsAsset)
            .map { name -> name to extractAsset(context, name) }
        if (extracted.any { (_, file) -> file == null }) {
            // One or more extractions failed — clean up partial files.
            extracted.forEach { (_, file) -> file?.delete() }
            return null
        }

        // Verify each extracted file matches the manifest.
        for ((name, file) in extracted) {
            val expected = manifest[name]
            val verification = verifyFile(file!!, expected, requireIntegrity)
            if (!verification.accepted) {
                MindlayerLog.w(TAG, "Asset-pack file failed integrity check: $name")
                extracted.forEach { (_, f) -> f?.delete() }
                return null
            }
        }

        val byName = extracted.associate { (n, f) -> n to f!! }
        return buildBundle(
            detFile = byName.getValue(detAsset),
            recFile = byName.getValue(recAsset),
            clsFile = clsAsset?.let { byName[it] },
            dictFile = byName.getValue(dictAsset),
            manifest = manifest,
        )
    }

    private fun extractAsset(context: Context, name: String): File? {
        val target = File(context.filesDir, name)
        if (target.exists()) return target
        return try {
            context.assets.open(name).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target
        } catch (e: Exception) {
            MindlayerLog.w(TAG, "Failed to extract asset $name: ${e.safeLabel()}")
            target.delete()
            null
        }
    }

    // ── Directory scan ───────────────────────────────────────────────────

    private fun bundleFromDir(
        dir: File?,
        manifest: Map<String, IntegrityMetadata>,
        requireIntegrity: Boolean,
    ): PaddleOcrModelInfo? {
        if (dir == null || !dir.isDirectory) return null
        val dirCanonical = try { dir.canonicalFile } catch (_: Exception) { return null }

        val files = dir.listFiles()?.filter { it.isFile } ?: return null
        val det = files.firstOrNull { SAFE_DET_NAME.matches(it.name) }
        val rec = files.firstOrNull { SAFE_REC_NAME.matches(it.name) }
        val dict = files.firstOrNull { SAFE_DICT_NAME.matches(it.name) }
        val cls = files.firstOrNull { SAFE_CLS_NAME.matches(it.name) }

        if (det == null || rec == null || dict == null) return null

        // Reject symlinks + path traversal at every step.
        for (f in listOfNotNull(det, rec, dict, cls)) {
            if (Files.isSymbolicLink(f.toPath())) {
                MindlayerLog.w(TAG, "Skipping symlink in PaddleOCR scan dir: ${f.name}")
                return null
            }
            val canonical = try { f.canonicalFile } catch (_: Exception) { return null }
            if (canonical.parentFile?.canonicalPath != dirCanonical.canonicalPath) {
                MindlayerLog.w(TAG, "Skipping file outside scan dir (path traversal?): ${f.name}")
                return null
            }
        }

        // Integrity verification.
        for (f in listOfNotNull(det, rec, dict, cls)) {
            val verification = verifyFile(f, manifest[f.name], requireIntegrity)
            if (!verification.accepted) {
                MindlayerLog.w(TAG, "PaddleOCR file failed integrity: ${f.name}")
                return null
            }
        }

        return buildBundle(det, rec, cls, dict, manifest)
    }

    // ── Bundle construction ──────────────────────────────────────────────

    private fun buildBundle(
        detFile: File,
        recFile: File,
        clsFile: File?,
        dictFile: File,
        manifest: Map<String, IntegrityMetadata>,
    ): PaddleOcrModelInfo {
        val totalBytes = detFile.length() + recFile.length() + dictFile.length() +
            (clsFile?.length() ?: 0L)
        return PaddleOcrModelInfo(
            id = "paddleocr-ppocrv5-mobile",
            displayName = "PaddleOCR PP-OCRv5 mobile",
            detectionPath = detFile.absolutePath,
            recognitionPath = recFile.absolutePath,
            classifierPath = clsFile?.absolutePath,
            dictionaryPath = dictFile.absolutePath,
            totalSizeBytes = totalBytes,
            detSha256 = manifest[detFile.name]?.sha256,
            recSha256 = manifest[recFile.name]?.sha256,
            clsSha256 = clsFile?.let { manifest[it.name]?.sha256 },
            dictSha256 = manifest[dictFile.name]?.sha256,
        )
    }

    // ── Integrity manifest parsing ───────────────────────────────────────

    private fun loadIntegrityManifest(context: Context): Map<String, IntegrityMetadata> {
        val raw = try {
            context.assets.open(INTEGRITY_MANIFEST).bufferedReader().use { it.readText() }
        } catch (_: Exception) {
            return emptyMap()
        }
        return try {
            val root = Json.parseToJsonElement(raw) as? JsonObject ?: return emptyMap()
            val models = root["models"] as? JsonArray ?: return emptyMap()
            buildMap {
                for (element in models) {
                    val model = element as? JsonObject ?: continue
                    val filename = model.stringOrNull("filename")?.takeIf { name ->
                        SAFE_DET_NAME.matches(name) || SAFE_REC_NAME.matches(name) ||
                            SAFE_CLS_NAME.matches(name) || SAFE_DICT_NAME.matches(name)
                    } ?: continue
                    val sha256 = model.stringOrNull("sha256")?.lowercase(Locale.ROOT) ?: continue
                    if (!SHA256_REGEX.matches(sha256)) continue
                    // Reject the all-zero placeholder (committed default) at
                    // load time when integrity is required. This prevents a
                    // production service from silently accepting a forgotten-
                    // to-replace placeholder manifest.
                    if (sha256 == "0".repeat(64)) continue
                    put(filename, IntegrityMetadata(sha256))
                }
            }
        } catch (e: Exception) {
            MindlayerLog.w(TAG, "PaddleOCR integrity manifest invalid: ${e.safeLabel()}")
            emptyMap()
        }
    }

    private fun verifyFile(
        file: File,
        expected: IntegrityMetadata?,
        requireIntegrity: Boolean,
    ): VerificationResult {
        if (expected == null) {
            return VerificationResult(accepted = !requireIntegrity, sha256 = null)
        }
        val actual = sha256(file)
        return VerificationResult(
            accepted = actual.equals(expected.sha256, ignoreCase = true),
            sha256 = actual,
        )
    }

    internal fun sha256(file: File): String {
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

    internal fun isDebuggable(context: Context): Boolean =
        (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0

    /**
     * Emit a one-time warning when the legacy `/data/local/tmp` sideload
     * scan is silently skipped on API 31+. As of Android 12, apps cannot
     * list `/data/local/tmp` even when individual files inside are
     * world-readable — `dir.listFiles()` returns `null`, so
     * [bundleFromDir] returns `null` with no obvious explanation. The
     * existing `getExternalFilesDir(null)` scan IS accessible to the
     * app's UID; the dev tooling now targets that. See docs/DEV_MODELS.md.
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
                "registry already scans that path. See docs/DEV_MODELS.md.",
        )
    }

    private fun JsonObject.stringOrNull(name: String): String? =
        (this[name] as? JsonPrimitive)?.contentOrNull

    internal data class IntegrityMetadata(val sha256: String)
    internal data class VerificationResult(val accepted: Boolean, val sha256: String?)
}
