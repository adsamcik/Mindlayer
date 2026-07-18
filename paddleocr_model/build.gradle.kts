plugins {
    id("com.android.asset-pack")
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
// (on-demand pack) or sideloaded via `adb push` for development.
//
// The release-build SHA-256 guard in :app:validateReleaseModelSha256 is
// extended in this PR with parallel `-PpaddleOcrDetSha256`,
// `-PpaddleOcrRecSha256`, `-PpaddleOcrClsSha256`, and
// `-PpaddleOcrDictSha256` properties.

private val detFileName = "paddleocr-ppocrv5-mobile-det.tflite"
private val recFileName = "paddleocr-ppocrv5-mobile-rec.tflite"
private val clsFileName = "paddleocr-ppocrv5-mobile-cls.tflite"
private val dictFileName = "paddleocr-ppocrv5-mobile-dict.txt"

// SHA precedence: explicit -P props (CI / override) → local-cache-derived digest
// computed once in the root build → "" (debug advisory). See the "Release model
// provisioning" block in the root build.gradle.kts.
@Suppress("UNCHECKED_CAST")
val releaseModelShas: Map<String, String> =
    (rootProject.extra["mindlayerModelShas"] as? Map<String, String>).orEmpty()
val modelCachePath: String = (rootProject.extra["mindlayerModelCachePath"] as? String).orEmpty()

val paddleOcrDetSha256 = project.findProperty("paddleOcrDetSha256")?.toString()?.trim()?.lowercase()
    ?.takeIf { it.isNotEmpty() } ?: releaseModelShas[detFileName].orEmpty()
val paddleOcrRecSha256 = project.findProperty("paddleOcrRecSha256")?.toString()?.trim()?.lowercase()
    ?.takeIf { it.isNotEmpty() } ?: releaseModelShas[recFileName].orEmpty()
val paddleOcrClsSha256 = project.findProperty("paddleOcrClsSha256")?.toString()?.trim()?.lowercase()
    ?.takeIf { it.isNotEmpty() } ?: releaseModelShas[clsFileName].orEmpty()
val paddleOcrDictSha256 = project.findProperty("paddleOcrDictSha256")?.toString()?.trim()?.lowercase()
    ?.takeIf { it.isNotEmpty() } ?: releaseModelShas[dictFileName].orEmpty()

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

val generatePaddleOcrModelIntegrityManifest = tasks.register("generatePaddleOcrModelIntegrityManifest") {
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
                    "Release builds need PaddleOCR SHA-256 digests for: ${missing.joinToString(", ")}. " +
                        "Set MINDLAYER_MODEL_CACHE (or -Pmindlayer.modelCache=<dir>) holding the PP-OCRv5 " +
                        "files, or pass the matching -P props. See docs/project/RELEASE.md.",
                )
            }
        }

        val existingManifest = outputs.files.singleFile.takeIf { it.exists() }
            ?.runCatching { readText() }?.getOrNull()
            .orEmpty()
        fun existingShaForRole(role: String): String? {
            val perRole = Regex(
                """\{[^}]*"role"\s*:\s*"$role"[^}]*\}""",
                RegexOption.DOT_MATCHES_ALL,
            ).find(existingManifest)?.value ?: return null
            return Regex("\"sha256\"\\s*:\\s*\"([0-9a-fA-F]{64})\"")
                .find(perRole)?.groupValues?.get(1)?.lowercase()
                ?.takeIf { it != "0".repeat(64) }
        }
        val detSha = detSha256.takeIf { sha256Re.matches(it) }
            ?: existingShaForRole("detection")
            ?: "0".repeat(64)
        val recSha = recSha256.takeIf { sha256Re.matches(it) }
            ?: existingShaForRole("recognition")
            ?: "0".repeat(64)
        val clsSha = clsSha256.takeIf { sha256Re.matches(it) }
            ?: existingShaForRole("orientation")
            ?: "0".repeat(64)
        val dictSha = dictSha256.takeIf { sha256Re.matches(it) }
            ?: existingShaForRole("dictionary")
            ?: "0".repeat(64)

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

// Copies the four PaddleOCR artifacts from the local cache into src/main/assets
// so the on-demand asset pack can bundle them for a release, keeping the
// binaries out of git. No-op on debug. Fail-fast on release when a required file
// is absent from both the asset dir and the cache.
val provisionReleaseModelAssets = tasks.register("provisionReleaseModelAssets") {
    group = "build"
    description = "Copies the PaddleOCR PP-OCRv5 mobile artifacts from the local model cache into src/main/assets for release packaging."
    val assetsDir = layout.projectDirectory.dir("src/main/assets").asFile
    val cachePath = modelCachePath
    val releaseReq = releaseTaskRequested
    val requiredFiles = listOf(detFileName, recFileName, clsFileName, dictFileName)
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
        dependsOn(generatePaddleOcrModelIntegrityManifest, provisionReleaseModelAssets)
    }
}

assetPack {
    packName = "paddleocr_model"
    dynamicDelivery {
        deliveryType = "on-demand"
    }
}

tasks.register("assembleDebug") {
    group = "build"
    description = "Alias for assemble so CI gates can target debug-like asset-pack builds."
    dependsOn("assemble")
}
