import java.util.Properties
import java.util.Base64
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    id("org.jetbrains.kotlin.plugin.compose") version libs.versions.kotlin.get()
}

// ── Release signing (local-only) ──────────────────────────────────────────────
// If keystore.properties is present at the repo root, wire a release signing
// config that reads from it. If absent, release builds are produced unsigned —
// useful for CI (`:app:bundleRelease` artifact) and for developers who haven't
// set up a key yet. See RELEASE.md for the full signing flow.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties().apply {
    if (keystorePropertiesFile.exists()) {
        keystorePropertiesFile.inputStream().use { load(it) }
    }
}
val localReleaseKeystore = keystoreProperties.getProperty("storeFile")?.let(rootProject::file)
val hasLocalReleaseKeystore = localReleaseKeystore?.exists() == true
val ciKeystoreBase64 = providers.environmentVariable("ANDROID_KEYSTORE_BASE64").orNull?.takeIf { it.isNotBlank() }
val ciKeystorePassword = providers.environmentVariable("ANDROID_KEYSTORE_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val ciKeyAlias = providers.environmentVariable("ANDROID_KEY_ALIAS").orNull?.takeIf { it.isNotBlank() }
val ciKeyPassword = providers.environmentVariable("ANDROID_KEY_PASSWORD").orNull?.takeIf { it.isNotBlank() }
val ciReleaseKeystore = ciKeystoreBase64?.let { encoded ->
    layout.buildDirectory.file("generated/signing/ci-release.jks").get().asFile.also { file ->
        file.parentFile.mkdirs()
        file.writeBytes(Base64.getDecoder().decode(encoded))
    }
}
val hasCiReleaseKeystore =
    ciReleaseKeystore != null && ciKeystorePassword != null && ciKeyAlias != null && ciKeyPassword != null
val hasReleaseKeystore = hasLocalReleaseKeystore || hasCiReleaseKeystore

val modelSha256 = project.findProperty("modelSha256")?.toString()?.trim().orEmpty()
val modelSha256Pattern = Regex("^[0-9a-f]{64}$")

val paddleOcrDetSha256 = project.findProperty("paddleOcrDetSha256")?.toString()?.trim().orEmpty()
val paddleOcrRecSha256 = project.findProperty("paddleOcrRecSha256")?.toString()?.trim().orEmpty()
val paddleOcrClsSha256 = project.findProperty("paddleOcrClsSha256")?.toString()?.trim().orEmpty()
val paddleOcrDictSha256 = project.findProperty("paddleOcrDictSha256")?.toString()?.trim().orEmpty()

val embeddingModelSha256 = project.findProperty("embeddingModelSha256")?.toString()?.trim().orEmpty()
val embeddingTokenizerSha256 = project.findProperty("embeddingTokenizerSha256")?.toString()?.trim().orEmpty()

val validateReleaseModelSha256 by tasks.registering {
    dependsOn(":gemma_model:generateModelIntegrityManifest")
    group = "verification"
    description = "Fails release builds unless -PmodelSha256 is a lowercase SHA-256 digest."
    doLast {
        if (!modelSha256Pattern.matches(modelSha256)) {
            throw GradleException(
                "Release builds require -PmodelSha256=<64 lowercase hex SHA-256 of the bundled .litertlm model>.",
            )
        }
        val manifest = rootProject.file("gemma_model/src/main/assets/model_integrity.json")
        if (!manifest.isFile) {
            throw GradleException("Release builds require gemma_model/src/main/assets/model_integrity.json.")
        }
        val manifestSha = Regex(""""sha256"\s*:\s*"([0-9a-f]{64})"""")
            .find(manifest.readText())
            ?.groupValues
            ?.get(1)
            ?: throw GradleException("model_integrity.json must contain a 64-character lowercase sha256 value.")
        if (manifestSha != modelSha256) {
            throw GradleException(
                "BuildConfig.MODEL_SHA256 does not match model_integrity.json sha256.",
            )
        }
    }
}

// v0.8 OCR (PR B): mirror the Gemma release SHA guard for the four
// PaddleOCR PP-OCRv5 mobile artifacts. The :paddleocr_model module's own
// generatePaddleOcrModelIntegrityManifest task already fails release
// builds if any -PpaddleOcr*Sha256 is missing/malformed; this validator
// adds the same defense-in-depth cross-check on the :app side, ensuring
// the manifest file on disk matches the four properties passed in.
val validateReleasePaddleOcrSha256 by tasks.registering {
    dependsOn(":paddleocr_model:generatePaddleOcrModelIntegrityManifest")
    group = "verification"
    description = "Fails release builds unless all four -PpaddleOcr*Sha256 properties are lowercase SHA-256 digests matching the manifest."
    doLast {
        val missing = buildList {
            if (!modelSha256Pattern.matches(paddleOcrDetSha256)) add("paddleOcrDetSha256")
            if (!modelSha256Pattern.matches(paddleOcrRecSha256)) add("paddleOcrRecSha256")
            if (!modelSha256Pattern.matches(paddleOcrClsSha256)) add("paddleOcrClsSha256")
            if (!modelSha256Pattern.matches(paddleOcrDictSha256)) add("paddleOcrDictSha256")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release builds require -P${missing.joinToString("=<64 hex> -P")}=<64 hex>",
            )
        }
        val manifest = rootProject.file("paddleocr_model/src/main/assets/paddleocr_model_integrity.json")
        if (!manifest.isFile) {
            throw GradleException(
                "Release builds require paddleocr_model/src/main/assets/paddleocr_model_integrity.json.",
            )
        }
        val text = manifest.readText()
        val byRole = Regex(
            """"filename"\s*:\s*"([^"]+)"\s*,\s*"sha256"\s*:\s*"([0-9a-f]{64})"\s*,\s*"role"\s*:\s*"([^"]+)"""",
        )
            .findAll(text)
            .associate { match -> match.groupValues[3] to match.groupValues[2] }

        fun assertRole(role: String, expected: String) {
            val actual = byRole[role]
                ?: throw GradleException(
                    "paddleocr_model_integrity.json missing entry for role '$role'.",
                )
            if (actual != expected.lowercase()) {
                throw GradleException(
                    "paddleocr_model_integrity.json sha256 for '$role' does not match -PpaddleOcr*Sha256.",
                )
            }
        }
        assertRole("detection", paddleOcrDetSha256)
        assertRole("recognition", paddleOcrRecSha256)
        assertRole("orientation", paddleOcrClsSha256)
        assertRole("dictionary", paddleOcrDictSha256)
    }
}

// Phase D #1 (release-validation): mirror the Gemma + PaddleOCR release
// SHA guards for the two EmbeddingGemma AI-pack artifacts. The
// :embeddinggemma_model module's own generateEmbeddingModelIntegrityManifest
// task already fails release builds if either -PembeddingModelSha256 or
// -PembeddingTokenizerSha256 is missing/malformed; this validator adds
// the same defense-in-depth cross-check on the :app side, ensuring the
// manifest file on disk matches the properties passed in. Without this
// cross-check, a buggy CI script could silently regenerate the manifest
// with zero hashes while still passing the per-module guard.
val validateReleaseEmbeddingSha256 by tasks.registering {
    dependsOn(":embeddinggemma_model:generateEmbeddingModelIntegrityManifest")
    group = "verification"
    description = "Fails release builds unless -PembeddingModelSha256 and -PembeddingTokenizerSha256 are 64-hex SHA-256 digests matching the embedding_model_integrity.json manifest."
    doLast {
        val missing = buildList {
            if (!modelSha256Pattern.matches(embeddingModelSha256)) add("embeddingModelSha256")
            if (!modelSha256Pattern.matches(embeddingTokenizerSha256)) add("embeddingTokenizerSha256")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release builds require -P${missing.joinToString("=<64 hex> -P")}=<64 hex>",
            )
        }
        val manifest = rootProject.file("embeddinggemma_model/src/main/assets/embedding_model_integrity.json")
        if (!manifest.isFile) {
            throw GradleException(
                "Release builds require embeddinggemma_model/src/main/assets/embedding_model_integrity.json.",
            )
        }
        val text = manifest.readText()
        val byRole = Regex(
            """"filename"\s*:\s*"([^"]+)"\s*,\s*"sha256"\s*:\s*"([0-9a-f]{64})"\s*,\s*"role"\s*:\s*"([^"]+)"""",
        )
            .findAll(text)
            .associate { match -> match.groupValues[3] to match.groupValues[2] }

        fun assertRole(role: String, expected: String) {
            val actual = byRole[role]
                ?: throw GradleException(
                    "embedding_model_integrity.json missing entry for role '$role'.",
                )
            if (actual != expected.lowercase()) {
                throw GradleException(
                    "embedding_model_integrity.json sha256 for '$role' does not match -PembeddingModelSha256 / -PembeddingTokenizerSha256.",
                )
            }
        }
        assertRole("weights", embeddingModelSha256)
        assertRole("tokenizer", embeddingTokenizerSha256)
    }
}

// ── F-079: LiteRT-LM AAR ABI inspection ──────────────────────────────────────
// Replaces the audit's "32-bit ABI silent failure" speculation with a hard
// build-time check: unpack the resolved LiteRT-LM AAR, enumerate the native
// libraries it ships under jni/<abi>/, and fail the build if any required
// ABI is missing.
//
// Update EXPECTED_LIBRARY_ABIS / ALLOWED_MISSING_LIBRARY_ABIS intentionally
// whenever the LiteRT-LM coordinate in libs.versions.toml is bumped — cross-
// reference the upstream release notes for the new ABI matrix:
//   https://github.com/google-ai-edge/LiteRT-LM/releases
//
// As of 0.10.0 the AAR ships arm64-v8a + x86_64 only; armeabi-v7a (32-bit
// ARM) has been dropped upstream. The Gemma 4 E2B weights cannot fit on a
// 32-bit address space anyway, so the gap is acceptable, but allow-listing
// it here means a *future* regression that drops arm64-v8a or x86_64 would
// be loud, not silent.

val EXPECTED_LIBRARY_ABIS: Set<String> = setOf(
    "arm64-v8a",     // mandatory — shipping ABI for all real devices
    "armeabi-v7a",   // historic 32-bit ARM (currently allow-listed missing)
    "x86_64",        // mandatory — CI emulator runs on x86_64
)

val ALLOWED_MISSING_LIBRARY_ABIS: Set<String> = setOf(
    "armeabi-v7a",   // intentionally absent in LiteRT-LM 0.10.0 — see comment above
)

val litertlmAarInspection: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    description = "Resolves the raw LiteRT-LM AAR for build-time ABI inspection (F-079)."
}

run {
    val dep = libs.litertlm.android.get()
    val coords = "${dep.module.group}:${dep.module.name}:${dep.versionConstraint.requiredVersion}@aar"
    dependencies.add(litertlmAarInspection.name, coords)
}

val validateLitertlmAbis by tasks.registering {
    group = "verification"
    description = "Fails the build if the LiteRT-LM AAR no longer ships a required native ABI (F-079)."

    val aarFiles: FileCollection = litertlmAarInspection
    inputs.files(aarFiles)
        .withPropertyName("litertlmAar")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)

    val expectedAbis = EXPECTED_LIBRARY_ABIS
    val allowedMissingAbis = ALLOWED_MISSING_LIBRARY_ABIS
    val markerFile = layout.buildDirectory.file("litertlm-abi-check/abis.txt")
    outputs.file(markerFile)

    doLast {
        val resolved = aarFiles.files
        val aar = resolved.singleOrNull {
            it.name.startsWith("litertlm-android") && it.name.endsWith(".aar")
        } ?: throw GradleException(
            "validateLitertlmAbis: expected exactly one LiteRT-LM AAR on the inspection " +
                "configuration; got ${resolved.map { it.name }}",
        )

        val abiEntryRegex = Regex("^jni/([^/]+)/lib[^/]+\\.so$")
        val present = sortedSetOf<String>()
        val nativeLibsByAbi = sortedMapOf<String, MutableList<String>>()
        ZipFile(aar).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val match = abiEntryRegex.matchEntire(entry.name) ?: return@forEach
                val abi = match.groupValues[1]
                present.add(abi)
                nativeLibsByAbi.getOrPut(abi) { mutableListOf() }.add(
                    entry.name.substringAfterLast('/'),
                )
            }
        }

        val missing = (expectedAbis - present).toSortedSet()
        val missingAndAllowed = (missing intersect allowedMissingAbis).toSortedSet()
        val missingAndUnallowed = (missing - allowedMissingAbis).toSortedSet()
        val unexpectedExtras = (present - expectedAbis).toSortedSet()

        logger.lifecycle("validateLitertlmAbis: ${aar.name}")
        logger.lifecycle("  present ABIs       : $present")
        nativeLibsByAbi.forEach { (abi, libs) ->
            logger.info("    $abi -> ${libs.sorted()}")
        }
        logger.lifecycle("  expected ABIs      : ${expectedAbis.toSortedSet()}")
        if (missingAndAllowed.isNotEmpty()) {
            logger.lifecycle("  allow-listed missing: $missingAndAllowed")
        }
        if (unexpectedExtras.isNotEmpty()) {
            logger.lifecycle(
                "  extra ABIs (consider adding to EXPECTED_LIBRARY_ABIS): $unexpectedExtras",
            )
        }

        if (missingAndUnallowed.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine(
                        "LiteRT-LM AAR is missing required ABIs: $missingAndUnallowed",
                    )
                    appendLine("  AAR file        : ${aar.absolutePath}")
                    appendLine("  Present ABIs    : $present")
                    appendLine("  Expected ABIs   : ${expectedAbis.toSortedSet()}")
                    appendLine("  Allowed missing : ${allowedMissingAbis.toSortedSet()}")
                    appendLine()
                    appendLine(
                        "Either upgrade LiteRT-LM, drop the ABI from EXPECTED_LIBRARY_ABIS,",
                    )
                    append(
                        "or add it to ALLOWED_MISSING_LIBRARY_ABIS with a documented rationale.",
                    )
                },
            )
        }

        markerFile.get().asFile.apply { parentFile.mkdirs() }.writeText(
            buildString {
                appendLine("aar=${aar.name}")
                appendLine("present=${present.joinToString(",")}")
                appendLine("expected=${expectedAbis.toSortedSet().joinToString(",")}")
                appendLine("allowedMissing=${allowedMissingAbis.toSortedSet().joinToString(",")}")
            },
        )
    }
}

val litertAarInspection: Configuration by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
    description = "Resolves the raw base LiteRT AAR for build-time ABI inspection."
}

run {
    val dep = libs.litert.get()
    val coords = "${dep.module.group}:${dep.module.name}:${dep.versionConstraint.requiredVersion}@aar"
    dependencies.add(litertAarInspection.name, coords)
}

val validateLitertAbis by tasks.registering {
    group = "verification"
    description = "Fails the build if the base LiteRT AAR no longer ships a required native ABI."

    val aarFiles: FileCollection = litertAarInspection
    inputs.files(aarFiles)
        .withPropertyName("litertAar")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)

    val expectedAbis = EXPECTED_LIBRARY_ABIS
    val allowedMissingAbis = ALLOWED_MISSING_LIBRARY_ABIS
    val markerFile = layout.buildDirectory.file("litert-abi-check/abis.txt")
    outputs.file(markerFile)

    doLast {
        val resolved = aarFiles.files
        val aar = resolved.singleOrNull {
            it.name.startsWith("litert-") && it.name.endsWith(".aar")
        } ?: throw GradleException(
            "validateLitertAbis: expected exactly one base LiteRT AAR on the inspection " +
                "configuration; got ${resolved.map { it.name }}",
        )

        val abiEntryRegex = Regex("^jni/([^/]+)/lib[^/]+\\.so$")
        val present = sortedSetOf<String>()
        val nativeLibsByAbi = sortedMapOf<String, MutableList<String>>()
        ZipFile(aar).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val match = abiEntryRegex.matchEntire(entry.name) ?: return@forEach
                val abi = match.groupValues[1]
                present.add(abi)
                nativeLibsByAbi.getOrPut(abi) { mutableListOf() }.add(
                    entry.name.substringAfterLast('/'),
                )
            }
        }

        if (present.isEmpty()) {
            logger.lifecycle("validateLitertAbis: ${aar.name} has no native libraries")
            markerFile.get().asFile.apply { parentFile.mkdirs() }.writeText("aar=${aar.name}\npresent=\n")
            return@doLast
        }

        val missing = (expectedAbis - present).toSortedSet()
        val missingAndAllowed = (missing intersect allowedMissingAbis).toSortedSet()
        val missingAndUnallowed = (missing - allowedMissingAbis).toSortedSet()
        val unexpectedExtras = (present - expectedAbis).toSortedSet()

        logger.lifecycle("validateLitertAbis: ${aar.name}")
        logger.lifecycle("  present ABIs       : $present")
        nativeLibsByAbi.forEach { (abi, libs) ->
            logger.info("    $abi -> ${libs.sorted()}")
        }
        logger.lifecycle("  expected ABIs      : ${expectedAbis.toSortedSet()}")
        if (missingAndAllowed.isNotEmpty()) {
            logger.lifecycle("  allow-listed missing: $missingAndAllowed")
        }
        if (unexpectedExtras.isNotEmpty()) {
            logger.lifecycle(
                "  extra ABIs (consider adding to EXPECTED_LIBRARY_ABIS): $unexpectedExtras",
            )
        }

        if (missingAndUnallowed.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Base LiteRT AAR is missing required ABIs: $missingAndUnallowed")
                    appendLine("  AAR file        : ${aar.absolutePath}")
                    appendLine("  Present ABIs    : $present")
                    appendLine("  Expected ABIs   : ${expectedAbis.toSortedSet()}")
                    append("  Allowed missing : ${allowedMissingAbis.toSortedSet()}")
                },
            )
        }

        markerFile.get().asFile.apply { parentFile.mkdirs() }.writeText(
            buildString {
                appendLine("aar=${aar.name}")
                appendLine("present=${present.joinToString(",")}")
                appendLine("expected=${expectedAbis.toSortedSet().joinToString(",")}")
                appendLine("allowedMissing=${allowedMissingAbis.toSortedSet().joinToString(",")}")
            },
        )
    }
}

val aidlContractDriftCheck by tasks.registering {
    group = "verification"
    description = "Fails if :app and :sdk AIDL contracts differ byte-for-byte."

    val appAidlDir = rootProject.layout.projectDirectory.dir("app/src/main/aidl/com/adsamcik/mindlayer")
    val sdkAidlDir = rootProject.layout.projectDirectory.dir("sdk/src/main/aidl/com/adsamcik/mindlayer")

    inputs.dir(appAidlDir)
        .withPropertyName("appAidlContracts")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    inputs.dir(sdkAidlDir)
        .withPropertyName("sdkAidlContracts")
        .withPathSensitivity(org.gradle.api.tasks.PathSensitivity.RELATIVE)
    val markerFile = layout.buildDirectory.file("aidl-contract-drift-check/success.txt")
    outputs.file(markerFile)

    doLast {
        val appDir = appAidlDir.asFile
        val sdkDir = sdkAidlDir.asFile

        fun aidlFiles(dir: File): List<String> = dir.listFiles { file ->
            file.isFile && file.extension == "aidl"
        }?.map { it.name }?.sorted().orEmpty()

        val appFiles = aidlFiles(appDir)
        val sdkFiles = aidlFiles(sdkDir)
        if (appFiles != sdkFiles) {
            val onlyInApp = (appFiles - sdkFiles).sorted()
            val onlyInSdk = (sdkFiles - appFiles).sorted()
            throw GradleException(
                buildString {
                    appendLine(":app and :sdk AIDL file sets differ.")
                    appendLine("  Only in app: ${onlyInApp.ifEmpty { listOf("<none>") }}")
                    append("  Only in sdk: ${onlyInSdk.ifEmpty { listOf("<none>") }}")
                },
            )
        }

        appFiles.forEach { fileName ->
            val appFile = appDir.resolve(fileName)
            val sdkFile = sdkDir.resolve(fileName)
            val appBytes = appFile.readBytes()
            val sdkBytes = sdkFile.readBytes()
            if (!appBytes.contentEquals(sdkBytes)) {
                throw GradleException(
                    "AIDL contract drift in $fileName: app/src/main/aidl and sdk/src/main/aidl copies must be byte-identical.",
                )
            }
        }

        markerFile.get().asFile.apply { parentFile.mkdirs() }.writeText(
            "checked=${appFiles.joinToString(",")}\n",
        )
    }
}

android {
    namespace = "com.adsamcik.mindlayer.service"
    compileSdk = 36
    compileSdkMinor = 1

    defaultConfig {
        applicationId = "com.adsamcik.mindlayer.service"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only bundle resources for the locales we actually ship.
        // Expand this list when translations are added.
        androidResources {
            localeFilters += listOf("en")
        }

        // F-002: hex-encoded SHA-256 of the bundled model file. When
        // non-empty, the engine refuses to load any .litertlm whose hash
        // does not match. Populate from CI (or `-PmodelSha256=…`) once the
        // model becomes part of the artifact pipeline; an empty string
        // keeps verification advisory (logged warning only) so debug
        // builds work without the manifest.
        buildConfigField("String", "MODEL_SHA256", "\"${modelSha256.lowercase()}\"")
    }

    signingConfigs {
        create("knownCertsOwner") {
            storeFile = rootProject.file("app/keystores/knowncerts-owner.jks")
            storePassword = "knowncertstest"
            keyAlias = "knowncerts-owner"
            keyPassword = "knowncertstest"
        }
        if (hasReleaseKeystore) {
            create("release") {
                if (hasLocalReleaseKeystore) {
                    storeFile = localReleaseKeystore
                    storePassword = keystoreProperties.getProperty("storePassword")
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                } else {
                    storeFile = checkNotNull(ciReleaseKeystore)
                    storePassword = checkNotNull(ciKeystorePassword)
                    keyAlias = checkNotNull(ciKeyAlias)
                    keyPassword = checkNotNull(ciKeyPassword)
                }
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isMinifyEnabled = false
            // F-071 escape hatch: developer override that re-enables the
            // pre-flight memory warn-and-proceed in debug builds when the
            // workstation itself is under memory pressure during local
            // testing. Pass `-Pmindlayer.allowLowMem=true` to any Gradle
            // invocation to flip it on. Release builds always hard-fail
            // (see the release block below) — this is *only* a developer
            // affordance.
            val allowLowMem = (project.findProperty("mindlayer.allowLowMem")?.toString() ?: "false")
                .equals("true", ignoreCase = true)
            buildConfigField("boolean", "ALLOW_LOW_MEM", allowLowMem.toString())
            signingConfig = signingConfigs.getByName("knownCertsOwner")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            if (hasReleaseKeystore) {
                signingConfig = signingConfigs.getByName("release")
            }
            // F-071: release builds NEVER tolerate low-memory engine init.
            // SIGABRT during model load is worse than a typed error, and
            // production users are not in a position to "free some RAM and
            // try again" without diagnostic surface.
            buildConfigField("boolean", "ALLOW_LOW_MEM", "false")
        }
    }

    buildFeatures {
        aidl = true
        compose = true
        buildConfig = true
    }

    // Selective AI Asset Pack bundling for dev iteration.
    // Defaults preserve current release/CI behavior (all packs bundled).
    // Override per-pack to build a small code-only APK and sideload models via
    // `tools/dev-models/push-models.ps1` (see `docs/DEV_MODELS.md`). Sideloaded
    // files are read from `/data/local/tmp/` by the runtime registries on
    // debuggable builds only.
    val bundledAssetPacks = buildList {
        fun bundle(prop: String, pack: String) {
            val enabled = providers.gradleProperty(prop)
                .map { it.toBoolean() }
                .getOrElse(true)
            if (enabled) add(pack)
        }
        bundle("mindlayer.bundleGemma", ":gemma_model")
        bundle("mindlayer.bundleEmbeddings", ":embeddinggemma_model")
        bundle("mindlayer.bundlePaddleocr", ":paddleocr_model")
    }
    assetPacks += bundledAssetPacks

    sourceSets {
        getByName("androidTest") {
            val paddleOcrAssetsDir = rootProject.file("paddleocr_model/src/main/assets")
            val requiredPaddleOcrAssets = listOf(
                "paddleocr_model_integrity.json",
                "paddleocr-ppocrv5-mobile-det.tflite",
                "paddleocr-ppocrv5-mobile-rec.tflite",
                "paddleocr-ppocrv5-mobile-dict.txt",
            )
            val hasRequiredPaddleOcrAssets = requiredPaddleOcrAssets.all { name ->
                paddleOcrAssetsDir.resolve(name).let { it.isFile && it.length() > 0L }
            }
            if (hasRequiredPaddleOcrAssets) {
                // Mirror the install-time AI Pack into the test APK so the
                // production coexistence smoke can exercise real assets on CI
                // when the SHA-gated bundle is provisioned. The orientation
                // classifier is optional; detection, recognition, dictionary,
                // and the manifest are required before we append this source.
                assets.directories.add(paddleOcrAssetsDir.absolutePath)
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/LICENSE.md",
                "META-INF/LICENSE-notice.md",
                "META-INF/AL2.0",
                "META-INF/LGPL2.1",
            )
        }
        jniLibs {
            // Base LiteRT and LiteRT-LM both ship the core runtime SONAME.
            // Keep the existing LiteRT-LM packaged copy until Phase A's
            // coexistence validation determines whether versions can diverge.
            pickFirsts += setOf("lib/*/libLiteRt*.so")
        }
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        // Treat these as build-breaking on release.
        warningsAsErrors = false
        abortOnError = true
        checkReleaseBuilds = true
        // Play Store-critical checks that must not regress.
        // NOTE: InlinedApi is intentionally NOT fatal — inlined integer
        // constants (e.g. PowerManager.THERMAL_STATUS_NONE) are always safe
        // when used as a fallback behind a proper SDK_INT check.
        fatal += setOf("NewApi", "MissingTranslation")
    }
}

tasks.named("preBuild") {
    dependsOn(aidlContractDriftCheck)
}

androidComponents {
    beforeVariants(selector().withBuildType("release")) { variantBuilder ->
        (variantBuilder as com.android.build.api.variant.HasUnitTestBuilder).enableUnitTest = true
    }
}

tasks.configureEach {
    val isReleasePackageTask = !name.contains("UnitTest") &&
        (name == "assembleRelease" || name == "bundleRelease" || name == "packageRelease")
    if (isReleasePackageTask) {
        dependsOn(validateReleaseModelSha256, validateReleasePaddleOcrSha256, validateReleaseEmbeddingSha256)
    }
    // F-079: every public APK/AAB packaging task must see the ABI validator.
    // assembleDebug + assembleRelease + bundleRelease are the lifecycle tasks
    // CI invokes; the validator's <200 ms warm cost makes blanket wiring safe.
    if (name == "assembleDebug" || name == "assembleRelease" || name == "bundleRelease") {
        dependsOn(validateLitertlmAbis, validateLitertAbis)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        freeCompilerArgs.addAll(
            "-opt-in=androidx.compose.material3.ExperimentalMaterial3ExpressiveApi",
            "-opt-in=androidx.compose.material3.Material3ExpressiveApi",
        )
    }
}

tasks.withType<Test> {
    // Tests inherit the Gradle JVM (Java 21 from the CI setup-java step) to
    // avoid Gradle's toolchain resolver auto-provisioning Temurin 21.0.10,
    // which has a deterministic SIGSEGV in G1SATBMarkQueueSet::filter under
    // Robolectric's classloading pattern.
    //
    // The same SIGSEGV reproduces on JBR-21.0.9 with the default -Xmx512m
    // gradle worker heap; switching to ParallelGC and bumping the heap
    // sidesteps the G1 marking bug. Both flags are safe across Temurin /
    // JBR / Microsoft / Azul.
    maxHeapSize = "2g"
    jvmArgs(
        "-XX:+UseParallelGC",
        "-XX:-UseG1GC",
    )
    if (name == "testReleaseUnitTest") {
        filter.includeTestsMatching("com.adsamcik.mindlayer.service.security.DebugAllowlistSeederReleaseAbsenceTest")
    }
}

dependencies {
    lintChecks(project(":lint-checks"))

    implementation(project(":shared"))
    implementation(libs.litertlm.android)
    implementation(libs.litert)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    // F-029: biometric gate for sensitive Approve / Revoke actions.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.biometric.compose)

    // Room (for logging database)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.sqlcipher.android)
    ksp(libs.room.compiler)

    // ZXing core (pure-JVM barcode decoder) — used by
    // BarcodeAnchorDetector to inject GTIN / QR / Code-128 anchors
    // into the OCR evidence package. Pure JVM => unit-testable.
    implementation(libs.zxing.core)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.turbine)
    testImplementation(libs.room.testing)
    testImplementation(project(":sdk"))

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(project(":sdk"))
}
