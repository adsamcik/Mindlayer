plugins {
    id("com.android.ai-pack")
}

val modelFileName = "gemma-4-E2B-it.litertlm"
val modelSha256 = project.findProperty("modelSha256")?.toString()?.trim()?.lowercase().orEmpty()
val modelSha256Pattern = Regex("^[0-9a-f]{64}$")
val manifestFile = layout.projectDirectory.file("src/main/assets/model_integrity.json")

val generateModelIntegrityManifest by tasks.registering {
    group = "verification"
    description = "Writes the model_integrity.json asset from -PmodelSha256."
    // Declare SHA/release inputs so Gradle invalidates a cached manifest
    // when the release hash changes, matching the PaddleOCR and
    // EmbeddingGemma asset-pack tasks.
    inputs.property("modelSha256", modelSha256)
    inputs.property("releaseTaskRequested", gradle.startParameter.taskNames.any {
        it.contains("Release", ignoreCase = false) && !it.contains("UnitTest", ignoreCase = false)
    })
    outputs.file(manifestFile)
    doLast {
        val releaseRequested = gradle.startParameter.taskNames.any {
            it.contains("Release", ignoreCase = false) && !it.contains("UnitTest", ignoreCase = false)
        }
        if (releaseRequested && !modelSha256Pattern.matches(modelSha256)) {
            throw GradleException("Release AI-pack builds require -PmodelSha256=<64 lowercase hex SHA-256>.")
        }
        val shaForManifest = modelSha256.takeIf { modelSha256Pattern.matches(it) }
            ?: "0000000000000000000000000000000000000000000000000000000000000000"
        val version = project.version.toString().takeUnless { it == "unspecified" } ?: "0.0.0"
        val file = manifestFile.asFile
        file.parentFile.mkdirs()
        file.writeText(
            """
            {
              "modelFile": "$modelFileName",
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
