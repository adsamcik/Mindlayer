plugins {
    id("com.android.ai-pack")
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
// Asset Delivery (install-time pack) or sideloaded via `adb push` for
// development.
//
// The release-build SHA-256 guard in :app:validateReleaseEmbeddingSha256
// mirrors :validateReleaseModelSha256 / :validateReleasePaddleOcrSha256;
// release tasks require -PembeddingModelSha256 + -PembeddingTokenizerSha256.

// AI pack carries two artifacts: the model and the tokenizer. Both must
// pass integrity verification before EmbeddingEngine accepts them.
private val modelFileName = "embedding-gemma-300m-v1.tflite"
private val tokenizerFileName = "embedding-gemma-300m-v1.spm.model"

val embeddingModelSha256 = project.findProperty("embeddingModelSha256")?.toString()?.trim()?.lowercase().orEmpty()
val embeddingTokenizerSha256 = project.findProperty("embeddingTokenizerSha256")?.toString()?.trim()?.lowercase().orEmpty()
val sha256Pattern = Regex("^[0-9a-f]{64}$")
val manifestFile = layout.projectDirectory.file("src/main/assets/embedding_model_integrity.json")

val generateEmbeddingModelIntegrityManifest by tasks.registering {
    group = "verification"
    description = "Writes the embedding_model_integrity.json asset from -PembeddingModelSha256 / -PembeddingTokenizerSha256."
    // Declare the SHA properties as task inputs so Gradle invalidates the
    // cached manifest whenever any of them change. Without this the
    // task's outputs.file(manifestFile) snapshot would keep a stale
    // zero-hashes manifest even after `-PembeddingModelSha256=...` is
    // passed. Mirrors paddleocr_model/build.gradle.kts.
    inputs.property("embeddingModelSha256", embeddingModelSha256)
    inputs.property("embeddingTokenizerSha256", embeddingTokenizerSha256)
    inputs.property("releaseTaskRequested", gradle.startParameter.taskNames.any {
        it.contains("Release", ignoreCase = false) && !it.contains("UnitTest", ignoreCase = false)
    })
    outputs.file(manifestFile)
    doLast {
        val releaseRequested = gradle.startParameter.taskNames.any {
            it.contains("Release", ignoreCase = false) && !it.contains("UnitTest", ignoreCase = false)
        }
        if (releaseRequested) {
            if (!sha256Pattern.matches(embeddingModelSha256)) {
                throw GradleException("Release embedding AI-pack builds require -PembeddingModelSha256=<64 hex>")
            }
            if (!sha256Pattern.matches(embeddingTokenizerSha256)) {
                throw GradleException("Release embedding AI-pack builds require -PembeddingTokenizerSha256=<64 hex>")
            }
        }
        val modelSha = embeddingModelSha256.takeIf { sha256Pattern.matches(it) }
            ?: "0".repeat(64)
        val tokenizerSha = embeddingTokenizerSha256.takeIf { sha256Pattern.matches(it) }
            ?: "0".repeat(64)
        val version = project.version.toString().takeUnless { it == "unspecified" } ?: "0.0.0"
        val file = manifestFile.asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            {
              "schema": 1,
              "version": "$version",
              "models": [
                {
                  "filename": "$modelFileName",
                  "sha256": "$modelSha",
                  "role": "weights"
                },
                {
                  "filename": "$tokenizerFileName",
                  "sha256": "$tokenizerSha",
                  "role": "tokenizer"
                }
              ]
            }
            """.trimIndent() + "\n",
        )
    }
}

tasks.configureEach {
    if (name == "preBuild" || name == "assemble" || name == "assembleDebug" || name.contains("Release")) {
        dependsOn(generateEmbeddingModelIntegrityManifest)
    }
}

aiPack {
    packName = "embeddinggemma_model"
    dynamicDelivery {
        deliveryType = "install-time"
    }
}


tasks.register("assembleDebug") {
    group = "build"
    description = "Alias for assemble so CI gates can target debug-like AI-pack builds."
    dependsOn("assemble")
}

