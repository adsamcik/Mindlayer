plugins {
    id("mindlayer.assetpack")
}

// ── EmbeddingGemma-300M asset pack ──────────────────────────────────────
//
// The asset pack carries two artifacts that the service-side embedding
// engine (EmbeddingCoordinator / LiteRtEmbeddingBackend) needs:
//   - embedding-gemma-300m-v1.tflite      — Mixed Precision quantized weights
//   - embedding-gemma-300m-v1.spm.model   — SentencePiece tokenizer
//
// Both come from `huggingface.co/litert-community/embeddinggemma-300m`
// (Mixed Precision, seq2048, generic / non-SoC-specific variant). The
// model is Gemma-licensed; on-device use is permitted under the same
// Gemma terms as the existing `gemma_model` asset pack.
//
// Live artifact bytes are deliberately not committed (see .gitignore
// `*.tflite` + `*.spm.model`); they are delivered to devices via Play
// Asset Delivery (on-demand pack) or sideloaded via `adb push` for
// development.
//
// The release-build SHA-256 guard in :app:validateReleaseEmbeddingSha256
// mirrors :validateReleaseModelSha256 / :validateReleasePaddleOcrSha256;
// release tasks require -PembeddingModelSha256 + -PembeddingTokenizerSha256.

// On-demand asset pack carries two artifacts: the model and the tokenizer. Both must
// pass integrity verification before EmbeddingEngine accepts them.
private val modelFileName = "embedding-gemma-300m-v1.tflite"
private val tokenizerFileName = "embedding-gemma-300m-v1.spm.model"

// SHA precedence: explicit -P props (CI / override) → local-cache-derived digest
// computed once in the root build → "" (debug advisory). See the "Release model
// provisioning" block in the root build.gradle.kts.
@Suppress("UNCHECKED_CAST")
val releaseModelShas: Map<String, String> =
    (rootProject.extra["mindlayerModelShas"] as? Map<String, String>).orEmpty()
val modelCachePath: String = (rootProject.extra["mindlayerModelCachePath"] as? String).orEmpty()

val embeddingModelSha256 = project.findProperty("embeddingModelSha256")?.toString()?.trim()?.lowercase()
    ?.takeIf { it.isNotEmpty() }
    ?: releaseModelShas[modelFileName].orEmpty()
val embeddingTokenizerSha256 = project.findProperty("embeddingTokenizerSha256")?.toString()?.trim()?.lowercase()
    ?.takeIf { it.isNotEmpty() }
    ?: releaseModelShas[tokenizerFileName].orEmpty()
val sha256Pattern = Regex("^[0-9a-f]{64}$")
val manifestFile = layout.projectDirectory.file("src/main/assets/embedding_model_integrity.json")

// Hoist execution-time values to configuration time so the configuration
// cache can capture them as plain captured locals rather than live
// `project`/`gradle` references (Gradle 9 forbids Task.project at
// execution time when the configuration cache is enabled).
val moduleVersion = project.version.toString().takeUnless { it == "unspecified" } ?: "0.0.0"
val releaseTaskRequested = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = false) && !it.contains("UnitTest", ignoreCase = false)
}

val generateEmbeddingModelIntegrityManifest = tasks.register("generateEmbeddingModelIntegrityManifest") {
    group = "verification"
    description = "Writes the embedding_model_integrity.json asset from -PembeddingModelSha256 / -PembeddingTokenizerSha256."
    // All values that the doLast action needs are declared here as task inputs.
    // This ensures Gradle's UP-TO-DATE checks work correctly, AND it allows the
    // doLast lambda to read every value via `inputs.properties[key]` rather than
    // capturing script-scope variables — which would be "Gradle script object
    // references" that the configuration cache cannot serialize (Gradle 9+).
    // Mirrors paddleocr_model/build.gradle.kts.
    inputs.property("embeddingModelSha256", embeddingModelSha256)
    inputs.property("embeddingTokenizerSha256", embeddingTokenizerSha256)
    inputs.property("releaseTaskRequested", releaseTaskRequested)
    inputs.property("moduleVersion", moduleVersion)
    inputs.property("modelFileName", modelFileName)
    inputs.property("tokenizerFileName", tokenizerFileName)
    outputs.file(manifestFile)
    doLast {
        // Read all values via inputs.properties so this lambda captures nothing
        // from the build-script scope — satisfying the Gradle 9 config-cache
        // requirement that execution-time actions must not hold script references.
        val props = inputs.properties
        val sha256Re = Regex("^[0-9a-f]{64}$")
        val modelSha256 = props["embeddingModelSha256"] as String
        val tokenizerSha256 = props["embeddingTokenizerSha256"] as String
        val releaseReq = props["releaseTaskRequested"] as Boolean
        val version = props["moduleVersion"] as String
        val modelFn = props["modelFileName"] as String
        val tokenizerFn = props["tokenizerFileName"] as String

        if (releaseReq) {
            if (!sha256Re.matches(modelSha256)) {
                throw GradleException(
                    "Release builds need the EmbeddingGemma weights SHA-256. Set MINDLAYER_MODEL_CACHE " +
                        "(or -Pmindlayer.modelCache=<dir>) holding $modelFn, or pass " +
                        "-PembeddingModelSha256=<64 hex>. See docs/project/RELEASE.md.",
                )
            }
            if (!sha256Re.matches(tokenizerSha256)) {
                throw GradleException(
                    "Release builds need the EmbeddingGemma tokenizer SHA-256. Set MINDLAYER_MODEL_CACHE " +
                        "(or -Pmindlayer.modelCache=<dir>) holding $tokenizerFn, or pass " +
                        "-PembeddingTokenizerSha256=<64 hex>. See docs/project/RELEASE.md.",
                )
            }
        }
        val zero = "0".repeat(64)
        // SHA selection precedence (highest first):
        //   1. -PembeddingModelSha256 / -PembeddingTokenizerSha256 — explicit
        //      per-build pin (CI, release, dev override)
        //   2. The currently-committed manifest's SHA per role, IF it's a real
        //      hex digest (not the all-zeros placeholder). Preserves the
        //      canonical SHA pinned in source control (see docs/models/MODEL_SHAS.md)
        //      so debug builds reproduce production integrity behaviour and
        //      `./gradlew assembleDebug` doesn't churn the manifest back to
        //      placeholders on every invocation.
        //   3. All-zeros placeholder fallback for first-build / clean state.
        val existingManifest = outputs.files.singleFile.takeIf { it.exists() }
            ?.runCatching { readText() }?.getOrNull()
            .orEmpty()
        fun existingShaForRole(role: String): String? {
            // Match `…"filename":"…", "sha256":"<hex>", …, "role":"<role>"`
            // tolerantly across whitespace + key ordering.
            val perRole = Regex(
                """\{[^}]*"role"\s*:\s*"$role"[^}]*\}""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(existingManifest)?.value ?: return null
            return Regex("\"sha256\"\\s*:\\s*\"([0-9a-fA-F]{64})\"")
                .find(perRole)?.groupValues?.get(1)?.lowercase()
                ?.takeIf { it != zero }
        }
        val modelSha = modelSha256.takeIf { sha256Re.matches(it) }
            ?: existingShaForRole("weights")
            ?: zero
        val tokenizerSha = tokenizerSha256.takeIf { sha256Re.matches(it) }
            ?: existingShaForRole("tokenizer")
            ?: zero
        val file = outputs.files.singleFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            {
              "schema": 1,
              "version": "$version",
              "models": [
                {
                  "filename": "$modelFn",
                  "sha256": "$modelSha",
                  "role": "weights"
                },
                {
                  "filename": "$tokenizerFn",
                  "sha256": "$tokenizerSha",
                  "role": "tokenizer"
                }
              ]
            }
            """.trimIndent() + "\n",
        )
    }
}

// Copies the EmbeddingGemma weights + tokenizer from the local cache into
// src/main/assets so the on-demand asset pack can bundle them for a release,
// keeping the binaries out of git. No-op on debug. Fail-fast on release when a
// required file is absent from both the asset dir and the cache.
val provisionReleaseModelAssets = tasks.register("provisionReleaseModelAssets") {
    group = "build"
    description = "Copies the EmbeddingGemma model + tokenizer from the local model cache into src/main/assets for release packaging."
    val assetsDir = layout.projectDirectory.dir("src/main/assets").asFile
    val cachePath = modelCachePath
    val releaseReq = releaseTaskRequested
    val requiredFiles = listOf(modelFileName, tokenizerFileName)
    inputs.property("cachePath", cachePath)
    inputs.property("releaseReq", releaseReq)
    inputs.property("requiredFiles", requiredFiles)
    doLast {
        if (!releaseReq) return@doLast
        val cacheDir = cachePath.takeIf { it.isNotEmpty() }?.let { File(it) }
        requiredFiles.forEach { fileName ->
            val target = File(assetsDir, fileName)
            val targetReady = target.isFile && target.length() > 1L
            val source = cacheDir?.resolve(fileName)?.takeIf { it.isFile && it.length() > 1L }
            when {
                // Equal byte lengths do not establish content integrity; the selected
                // cache must always win so the asset matches its generated digest.
                source != null -> {
                    target.parentFile.mkdirs()
                    source.copyTo(target, overwrite = true)
                }
                targetReady -> Unit
                else -> throw GradleException(
                    "Release build needs '$fileName'. Set MINDLAYER_MODEL_CACHE (or " +
                        "-Pmindlayer.modelCache=<dir>) to a directory containing it, or place the " +
                        "file directly in $assetsDir. See docs/project/RELEASE.md.",
                )
            }
        }
    }
}

tasks.configureEach {
    if ((name == "preBuild" || name == "assemble" || name == "assembleDebug" || name.contains("Release")) &&
        name != "provisionReleaseModelAssets"
    ) {
        dependsOn(generateEmbeddingModelIntegrityManifest, provisionReleaseModelAssets)
    }
}

tasks.register("assembleDebug") {
    group = "build"
    description = "Alias for assemble so CI gates can target debug-like asset-pack builds."
    dependsOn("assemble")
}
