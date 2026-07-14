plugins {
    id("com.android.ai-pack")
}

val modelFileName = "gemma-4-E2B-it.litertlm"

// SHA precedence: explicit -PmodelSha256 (CI / override) → local-cache-derived
// digest computed once in the root build → "" (debug advisory). See the
// "Release model provisioning" block in the root build.gradle.kts.
@Suppress("UNCHECKED_CAST")
val releaseModelShas: Map<String, String> =
    (rootProject.extra["mindlayerModelShas"] as? Map<String, String>).orEmpty()
val modelCachePath: String = (rootProject.extra["mindlayerModelCachePath"] as? String).orEmpty()
val modelSha256 = project.findProperty("modelSha256")?.toString()?.trim()?.lowercase()
    ?.takeIf { it.isNotEmpty() }
    ?: releaseModelShas[modelFileName].orEmpty()
val modelSha256Pattern = Regex("^[0-9a-f]{64}$")
val zeroSha = "0".repeat(64)
val manifestFile = layout.projectDirectory.file("src/main/assets/model_integrity.json")

// Hoist execution-time values to configuration time so the configuration
// cache can capture them as plain captured locals rather than live
// `project`/`gradle` references (Gradle 9 forbids Task.project at
// execution time when the configuration cache is enabled).
val moduleVersion = project.version.toString().takeUnless { it == "unspecified" } ?: "0.0.0"
val releaseTaskRequested = gradle.startParameter.taskNames.any {
    it.contains("Release", ignoreCase = false) && !it.contains("UnitTest", ignoreCase = false)
}

val generateModelIntegrityManifest by tasks.registering {
    group = "verification"
    description = "Writes the model_integrity.json asset from -PmodelSha256."
    // Declare SHA/release inputs so Gradle invalidates a cached manifest
    // when the release hash changes, matching the PaddleOCR and
    // EmbeddingGemma asset-pack tasks.
    inputs.property("modelSha256", modelSha256)
    inputs.property("releaseTaskRequested", releaseTaskRequested)
    inputs.property("moduleVersion", moduleVersion)
    inputs.property("modelFileName", modelFileName)
    outputs.file(manifestFile)
    doLast {
        val props = inputs.properties
        val sha256Re = Regex("^[0-9a-f]{64}$")
        val sha = props["modelSha256"] as String
        val releaseReq = props["releaseTaskRequested"] as Boolean
        val version = props["moduleVersion"] as String
        val modelFn = props["modelFileName"] as String
        val zero = "0".repeat(64)

        if (releaseReq && !sha256Re.matches(sha)) {
            throw GradleException(
                "Release builds need the Gemma model SHA-256. Set MINDLAYER_MODEL_CACHE (or " +
                    "-Pmindlayer.modelCache=<dir>) to a directory holding gemma-4-E2B-it.litertlm, " +
                    "or pass -PmodelSha256=<64 lowercase hex>. See docs/project/RELEASE.md.",
            )
        }

        // SHA selection precedence (highest first):
        //   1. -PmodelSha256 — explicit per-build pin (CI, release, dev override)
        //   2. The currently-committed manifest's SHA, IF it's already a real
        //      hex digest (not the all-zeros placeholder). This preserves the
        //      canonical SHA pinned in source control (see docs/models/MODEL_SHAS.md)
        //      so debug builds reproduce production integrity behaviour and
        //      casual `./gradlew assembleDebug` runs don't churn the manifest
        //      back to placeholders on every invocation.
        //   3. All-zeros placeholder fallback for first-build / clean state.
        val explicit = sha.takeIf { sha256Re.matches(it) }
        val existing = outputs.files.singleFile.takeIf { it.exists() }
            ?.runCatching { readText() }?.getOrNull()
            ?.let { Regex("\"sha256\"\\s*:\\s*\"([0-9a-fA-F]{64})\"").find(it)?.groupValues?.get(1)?.lowercase() }
            ?.takeIf { it != zero }
        val shaForManifest = explicit ?: existing ?: zero

        val file = outputs.files.singleFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            {
              "modelFile": "$modelFn",
              "sha256": "$shaForManifest",
              "version": "$version",
              "schema": 1
            }
            """.trimIndent() + "\n",
        )
    }
}

// Copies the Gemma chat model from the local cache into src/main/assets so the
// install-time AI pack can bundle it for a release, keeping the multi-GB binary
// out of git. No-op on debug (sideload path). Fail-fast on release when the file
// is absent from both the asset dir and the cache.
val provisionReleaseModelAssets by tasks.registering {
    group = "build"
    description = "Copies the Gemma chat model from the local model cache into src/main/assets for release packaging."
    val assetsDir = layout.projectDirectory.dir("src/main/assets").asFile
    val cachePath = modelCachePath
    val releaseReq = releaseTaskRequested
    val requiredFiles = listOf(modelFileName)
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
    if ((name == "preBuild" || name.contains("Release")) && name != "provisionReleaseModelAssets") {
        dependsOn(generateModelIntegrityManifest, provisionReleaseModelAssets)
    }
}

aiPack {
    packName = "gemma_model"
    dynamicDelivery {
        deliveryType = "install-time"
    }
}
