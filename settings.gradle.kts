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
        // alpha.7 has a managed-identity quota bug on process restart. This
        // source-documented repair stays pinned until an upstream release fixes it.
        exclusiveContent {
            forRepository {
                maven {
                    name = "TraceboxManagedRestartRepair"
                    url = uri("third_party/tracebox/repository")
                    metadataSources { mavenPom(); artifact() }
                }
            }
            filter { includeVersion("io.github.tracebox", "tracebox", "0.1.0-alpha.7-mindlayer.1") }
        }
        // Tracebox is published by this project owner. An explicit repository override
        // supports local validation; CI consumes the pinned GitHub Packages release.
        providers.gradleProperty("traceboxLocalRepository").orNull?.let { repositoryPath ->
            check(!providers.environmentVariable("CI").isPresent) {
                "traceboxLocalRepository is only available for local validation"
            }
            maven {
                name = "TraceboxLocalCandidate"
                url = uri(repositoryPath)
                content { includeGroup("io.github.tracebox") }
            }
        }
        maven {
            name = "TraceboxGitHubPackages"
            url = uri("https://maven.pkg.github.com/adsamcik/tracebox")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("TRACEBOX_PACKAGES_READ_TOKEN"))
                    .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
            content { includeGroup("io.github.tracebox") }
        }
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
