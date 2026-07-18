pluginManagement {
    includeBuild("build-logic")
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    @Suppress("UnstableApiUsage")
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // JitPack — scoped to Tesseract4Android only, for the on-device OCR
        // engine benchmark in `:app:androidTest`. Tesseract is NOT shipped in
        // any production artifact (only `androidTestImplementation`); the
        // benchmark exists to compare PaddleOCR vs Tesseract on a fixture
        // dataset and lives outside the main build graph.
        maven {
            url = uri("https://jitpack.io")
            content {
                includeGroup("cz.adaptech.tesseract4android")
            }
        }
    }
}

rootProject.name = "Mindlayer"

include(":app")
include(":sdk")
include(":sdk-camerax")
include(":sdk-camera-launcher")
include(":shared")
include(":lint-checks")
include(":gemma_model")
include(":gemma_model_part_2")
include(":gemma_embed_model")
include(":paddleocr_model")
include(":samples:ocr-driver")
