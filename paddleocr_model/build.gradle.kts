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

// Hoist execution-time values to configuration time so the configuration
// cache can capture them as plain captured locals rather than live
// `project`/`gradle` references (Gradle 9 forbids Task.project at
// execution time when the configuration cache is enabled).
val moduleVersion = project.version.toString().takeUnless { it == "unspecified" } ?: "0.0.0"
val releaseTaskRequested = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = false) && !it.contains("UnitTest", ignoreCase = false)
}

val generatePaddleOcrModelIntegrityManifest by tasks.registering {
    group = "verification"
    description = "Writes paddleocr_model_integrity.json from the four -PpaddleOcr*Sha256 properties."
    // All values that the doLast action needs are declared here as task inputs.
    // This ensures Gradle's UP-TO-DATE checks work correctly, AND it allows the
    // doLast lambda to read every value via `inputs.properties[key]` rather than
    // capturing script-scope variables — which would be "Gradle script object
    // references" that the configuration cache cannot serialize (Gradle 9+).
    inputs.property("paddleOcrDetSha256", paddleOcrDetSha256)
    inputs.property("paddleOcrRecSha256", paddleOcrRecSha256)
    inputs.property("paddleOcrClsSha256", paddleOcrClsSha256)
    inputs.property("paddleOcrDictSha256", paddleOcrDictSha256)
    inputs.property("releaseTaskRequested", releaseTaskRequested)
    inputs.property("moduleVersion", moduleVersion)
    inputs.property("detFileName", detFileName)
    inputs.property("recFileName", recFileName)
    inputs.property("clsFileName", clsFileName)
    inputs.property("dictFileName", dictFileName)
    outputs.file(manifestFile)
    doLast {
        // Read all values via inputs.properties so this lambda captures nothing
        // from the build-script scope — satisfying the Gradle 9 config-cache
        // requirement that execution-time actions must not hold script references.
        val props = inputs.properties
        val sha256Re = Regex("^[0-9a-f]{64}$")
        val detSha256 = props["paddleOcrDetSha256"] as String
        val recSha256 = props["paddleOcrRecSha256"] as String
        val clsSha256 = props["paddleOcrClsSha256"] as String
        val dictSha256 = props["paddleOcrDictSha256"] as String
        val releaseReq = props["releaseTaskRequested"] as Boolean
        val version = props["moduleVersion"] as String
        val detFn = props["detFileName"] as String
        val recFn = props["recFileName"] as String
        val clsFn = props["clsFileName"] as String
        val dictFn = props["dictFileName"] as String

        if (releaseReq) {
            val missing = buildList {
                if (!sha256Re.matches(detSha256)) add("paddleOcrDetSha256")
                if (!sha256Re.matches(recSha256)) add("paddleOcrRecSha256")
                if (!sha256Re.matches(clsSha256)) add("paddleOcrClsSha256")
                if (!sha256Re.matches(dictSha256)) add("paddleOcrDictSha256")
            }
            if (missing.isNotEmpty()) {
                throw GradleException(
                    "Release paddleocr_model builds require -P${missing.joinToString("=<64 hex> -P")}=<64 hex>",
                )
            }
        }

        val detSha = detSha256.takeIf { sha256Re.matches(it) } ?: "0".repeat(64)
        val recSha = recSha256.takeIf { sha256Re.matches(it) } ?: "0".repeat(64)
        val clsSha = clsSha256.takeIf { sha256Re.matches(it) } ?: "0".repeat(64)
        val dictSha = dictSha256.takeIf { sha256Re.matches(it) } ?: "0".repeat(64)

        val file = outputs.files.singleFile
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
                  "filename": "$detFn",
                  "sha256": "$detSha",
                  "role": "detection"
                },
                {
                  "filename": "$recFn",
                  "sha256": "$recSha",
                  "role": "recognition"
                },
                {
                  "filename": "$clsFn",
                  "sha256": "$clsSha",
                  "role": "orientation"
                },
                {
                  "filename": "$dictFn",
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
