plugins {
    id("com.android.ai-pack")
}

// ── PaddleOCR PP-OCRv5 mobile asset pack ────────────────────────────────
//
// The asset pack carries four artifacts that the service-side OCR engine
// (PR C) needs to instantiate the PP-OCRv5 mobile pipeline against the
// existing LiteRT runtime:
//   - <det>.tflite — text-line detection
//   - <rec>.tflite — text recognition
//   - <cls>.tflite — text-orientation classifier
//   - <dict>.txt  — character dictionary for the rec head
//
// All four come from PaddleOCR v3.5.0 mobile, converted at build time
// via paddle2onnx v2.1.0 → onnx2tf v2.4.0 (pipeline in
// .github/workflows/build-paddleocr-models.yml). Each release pin must
// satisfy the 7-day soak rule in .github/instructions/privacy-offline
// .instructions.md — community projects only; Google + JetBrains
// libraries are exempt.
//
// Live artifact bytes are deliberately not committed (see .gitignore
// `*.tflite`); they are delivered to devices via Play Asset Delivery
// (install-time pack) or sideloaded via `adb push` for development.
//
// The release-build SHA-256 guard in :app:validateReleaseModelSha256 is
// extended in this PR with parallel `-PpaddleOcrDetSha256`,
// `-PpaddleOcrRecSha256`, `-PpaddleOcrClsSha256`, and
// `-PpaddleOcrDictSha256` properties.

private val detFileName = "paddleocr-ppocrv5-mobile-det.tflite"
private val recFileName = "paddleocr-ppocrv5-mobile-rec.tflite"
private val clsFileName = "paddleocr-ppocrv5-mobile-cls.tflite"
private val dictFileName = "paddleocr-ppocrv5-mobile-dict.txt"

val paddleOcrDetSha256 = project.findProperty("paddleOcrDetSha256")?.toString()?.trim()?.lowercase().orEmpty()
val paddleOcrRecSha256 = project.findProperty("paddleOcrRecSha256")?.toString()?.trim()?.lowercase().orEmpty()
val paddleOcrClsSha256 = project.findProperty("paddleOcrClsSha256")?.toString()?.trim()?.lowercase().orEmpty()
val paddleOcrDictSha256 = project.findProperty("paddleOcrDictSha256")?.toString()?.trim()?.lowercase().orEmpty()

val sha256Pattern = Regex("^[0-9a-f]{64}$")
val manifestFile = layout.projectDirectory.file("src/main/assets/paddleocr_model_integrity.json")

val generatePaddleOcrModelIntegrityManifest by tasks.registering {
    group = "verification"
    description = "Writes paddleocr_model_integrity.json from the four -PpaddleOcr*Sha256 properties."
    // Declare the four SHA properties as task inputs so Gradle invalidates
    // the cached manifest whenever any of them change. Without this the
    // task's outputs.file(manifestFile) snapshot would keep a stale
    // zero-hashes manifest even after `-PpaddleOcr*Sha256=...` is passed.
    inputs.property("paddleOcrDetSha256", paddleOcrDetSha256)
    inputs.property("paddleOcrRecSha256", paddleOcrRecSha256)
    inputs.property("paddleOcrClsSha256", paddleOcrClsSha256)
    inputs.property("paddleOcrDictSha256", paddleOcrDictSha256)
    inputs.property("releaseTaskRequested", gradle.startParameter.taskNames.any {
        it.contains("Release", ignoreCase = false) && !it.contains("UnitTest", ignoreCase = false)
    })
    outputs.file(manifestFile)
    doLast {
        val releaseRequested = gradle.startParameter.taskNames.any {
            it.contains("Release", ignoreCase = false) && !it.contains("UnitTest", ignoreCase = false)
        }
        if (releaseRequested) {
            val missing = buildList {
                if (!sha256Pattern.matches(paddleOcrDetSha256)) add("paddleOcrDetSha256")
                if (!sha256Pattern.matches(paddleOcrRecSha256)) add("paddleOcrRecSha256")
                if (!sha256Pattern.matches(paddleOcrClsSha256)) add("paddleOcrClsSha256")
                if (!sha256Pattern.matches(paddleOcrDictSha256)) add("paddleOcrDictSha256")
            }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Release paddleocr_model builds require -P${missing.joinToString("=<64 hex> -P")}=<64 hex>",
                )
            }
        }

        val detSha = paddleOcrDetSha256.takeIf { sha256Pattern.matches(it) } ?: "0".repeat(64)
        val recSha = paddleOcrRecSha256.takeIf { sha256Pattern.matches(it) } ?: "0".repeat(64)
        val clsSha = paddleOcrClsSha256.takeIf { sha256Pattern.matches(it) } ?: "0".repeat(64)
        val dictSha = paddleOcrDictSha256.takeIf { sha256Pattern.matches(it) } ?: "0".repeat(64)
        val version = project.version.toString().takeUnless { it == "unspecified" } ?: "0.0.0"

        val file = manifestFile.asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            {
              "schema": 1,
              "version": "$version",
              "engine": "paddleocr-ppocrv5-mobile",
              "converted_by": {
                "paddle2onnx": "2.1.0",
                "onnx2tf": "2.4.0"
              },
              "models": [
                {
                  "filename": "$detFileName",
                  "sha256": "$detSha",
                  "role": "detection"
                },
                {
                  "filename": "$recFileName",
                  "sha256": "$recSha",
                  "role": "recognition"
                },
                {
                  "filename": "$clsFileName",
                  "sha256": "$clsSha",
                  "role": "orientation"
                },
                {
                  "filename": "$dictFileName",
                  "sha256": "$dictSha",
                  "role": "dictionary"
                }
              ]
            }
            """.trimIndent() + "\n",
        )
    }
}

tasks.configureEach {
    if (name == "preBuild" || name == "assemble" || name == "assembleDebug" || name.contains("Release")) {
        dependsOn(generatePaddleOcrModelIntegrityManifest)
    }
}

aiPack {
    packName = "paddleocr_model"
    dynamicDelivery {
        deliveryType = "install-time"
    }
}

tasks.register("assembleDebug") {
    group = "build"
    description = "Alias for assemble so CI gates can target debug-like AI-pack builds."
    dependsOn("assemble")
}
