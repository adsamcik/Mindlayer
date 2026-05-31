plugins {
    id("com.android.ai-pack")
}

val modelFileName = "gemma-4-E2B-it.litertlm"
val modelSha256 = project.findProperty("modelSha256")?.toString()?.trim()?.lowercase().orEmpty()
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
            throw GradleException("Release AI-pack builds require -PmodelSha256=<64 lowercase hex SHA-256>.")
        }

        // SHA selection precedence (highest first):
        //   1. -PmodelSha256 — explicit per-build pin (CI, release, dev override)
        //   2. The currently-committed manifest's SHA, IF it's already a real
        //      hex digest (not the all-zeros placeholder). This preserves the
        //      canonical SHA pinned in source control (see docs/MODEL_SHAS.md)
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

tasks.configureEach {
    if (name == "preBuild" || name.contains("Release")) {
        dependsOn(generateModelIntegrityManifest)
    }
}

aiPack {
    packName = "gemma_model"
    dynamicDelivery {
        deliveryType = "install-time"
    }
}
