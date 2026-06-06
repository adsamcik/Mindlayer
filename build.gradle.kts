plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Publishing configuration for GitHub Packages
// Version: override with -PpublishVersion=X.Y.Z or via tag (CI extracts from v-tag)
val publishVersion = findProperty("publishVersion")?.toString() ?: "1.0.0-alpha01"
val githubOwner = findProperty("GITHUB_OWNER")?.toString() ?: System.getenv("GITHUB_OWNER") ?: "OWNER"
val githubRepo = findProperty("GITHUB_REPO")?.toString() ?: System.getenv("GITHUB_REPO") ?: "Mindlayer"
val githubToken = findProperty("GITHUB_TOKEN")?.toString() ?: System.getenv("GITHUB_TOKEN") ?: ""

extra["publishVersion"] = publishVersion
extra["githubOwner"] = githubOwner
extra["githubRepo"] = githubRepo
extra["githubToken"] = githubToken

// ── Release model provisioning from a local cache (never committed) ───────────
// Release AABs/APKs bundle the on-device AI models (Gemma chat, EmbeddingGemma,
// PaddleOCR) as install-time AI Asset Packs. The multi-GB model bytes are NOT in
// git (.gitignore). For a LOCAL release we source them from a flat cache dir —
// the same one tools/dev-models/push-models.* uses — resolved from
// `-Pmindlayer.modelCache=<path>` or the `MINDLAYER_MODEL_CACHE` env var.
//
// Gated on a release task + a resolved cache, we hash each canonical file ONCE
// here and expose the digests via `extra`. Downstream:
//   * each AI-pack module writes the digest into its *_integrity.json manifest
//     and copies the bytes into src/main/assets/ (provisionReleaseModelAssets);
//   * :app feeds BuildConfig.MODEL_SHA256 and the release SHA validators.
// The cache is the trusted source of truth, so a local release needs no
// hand-typed -P*Sha256 flags. CI keeps passing its existing -P*Sha256 values,
// which still take precedence in every consumer.
//
// Config-cache note: the digest is captured at configuration time (like the
// pre-existing -PmodelSha256 value it replaces). If you swap model bytes WITHOUT
// changing the filename, run a release with `--no-configuration-cache` so the
// new digest is recomputed.
val mindlayerReleaseTaskRequested: Boolean = gradle.startParameter.taskNames.any {
    it.contains("Release") && !it.contains("UnitTest")
}

val mindlayerModelCachePath: String = (
    providers.gradleProperty("mindlayer.modelCache").orNull
        ?: providers.environmentVariable("MINDLAYER_MODEL_CACHE").orNull
).orEmpty().trim()

fun mindlayerSha256Hex(target: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    target.inputStream().buffered().use { input ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// Canonical filenames the three AI-pack modules expect (flat cache layout,
// matching docs/DEV_MODELS.md). Kept in sync with each module + push-models.*.
val mindlayerCanonicalModelFiles = listOf(
    "gemma-4-E2B-it.litertlm",
    "embedding-gemma-300m-v1.tflite",
    "embedding-gemma-300m-v1.spm.model",
    "paddleocr-ppocrv5-mobile-det.tflite",
    "paddleocr-ppocrv5-mobile-rec.tflite",
    "paddleocr-ppocrv5-mobile-cls.tflite",
    "paddleocr-ppocrv5-mobile-dict.txt",
)

val mindlayerModelShas: Map<String, String> =
    if (mindlayerReleaseTaskRequested && mindlayerModelCachePath.isNotEmpty()) {
        val cacheDir = file(mindlayerModelCachePath)
        mindlayerCanonicalModelFiles.mapNotNull { name ->
            val candidate = cacheDir.resolve(name)
            if (candidate.isFile && candidate.length() > 1L) {
                name to mindlayerSha256Hex(candidate)
            } else {
                null
            }
        }.toMap()
    } else {
        emptyMap()
    }

extra["mindlayerReleaseTaskRequested"] = mindlayerReleaseTaskRequested
extra["mindlayerModelCachePath"] = mindlayerModelCachePath
extra["mindlayerModelShas"] = mindlayerModelShas
