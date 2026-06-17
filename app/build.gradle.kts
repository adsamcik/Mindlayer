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

// SHA precedence for every model digest the release guards + BuildConfig need:
//   1. explicit -P<name>Sha256 (CI / power-user override), else
//   2. the digest computed once from the local cache in the root build, else
//   3. "" (debug advisory; release guards below fail closed).
// See the "Release model provisioning" block in the root build.gradle.kts.
@Suppress("UNCHECKED_CAST")
val releaseModelShas: Map<String, String> =
    (rootProject.extra["mindlayerModelShas"] as? Map<String, String>).orEmpty()

fun resolveModelSha(propName: String, cacheFileName: String): String =
    project.findProperty(propName)?.toString()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
        ?: releaseModelShas[cacheFileName].orEmpty()

val modelSha256 = resolveModelSha("modelSha256", "gemma-4-E2B-it.litertlm")

val paddleOcrDetSha256 = resolveModelSha("paddleOcrDetSha256", "paddleocr-ppocrv5-mobile-det.tflite")
val paddleOcrRecSha256 = resolveModelSha("paddleOcrRecSha256", "paddleocr-ppocrv5-mobile-rec.tflite")
val paddleOcrClsSha256 = resolveModelSha("paddleOcrClsSha256", "paddleocr-ppocrv5-mobile-cls.tflite")
val paddleOcrDictSha256 = resolveModelSha("paddleOcrDictSha256", "paddleocr-ppocrv5-mobile-dict.txt")

val embeddingModelSha256 = resolveModelSha("embeddingModelSha256", "embedding-gemma-300m-v1.tflite")
val embeddingTokenizerSha256 = resolveModelSha("embeddingTokenizerSha256", "embedding-gemma-300m-v1.spm.model")

// Resolve the AI-pack integrity-manifest paths at configuration time so the
// validator doLast actions capture plain Strings (config-cache safe) instead of
// referencing rootProject at execution time.
val gemmaIntegrityManifestPath = rootProject.layout.projectDirectory
    .file("gemma_model/src/main/assets/model_integrity.json").asFile.absolutePath
val paddleIntegrityManifestPath = rootProject.layout.projectDirectory
    .file("paddleocr_model/src/main/assets/paddleocr_model_integrity.json").asFile.absolutePath
val embeddingIntegrityManifestPath = rootProject.layout.projectDirectory
    .file("gemma_embed_model/src/main/assets/embedding_model_integrity.json").asFile.absolutePath

val validateReleaseModelSha256 by tasks.registering {
    dependsOn(":gemma_model:generateModelIntegrityManifest")
    group = "verification"
    description = "Fails release builds unless the Gemma model SHA-256 (from -PmodelSha256 or the local cache) matches model_integrity.json."
    inputs.property("modelSha256", modelSha256)
    inputs.property("manifestPath", gemmaIntegrityManifestPath)
    doLast {
        val props = inputs.properties
        val sha = props["modelSha256"] as String
        val sha256Re = Regex("^[0-9a-f]{64}$")
        if (!sha256Re.matches(sha)) {
            throw GradleException(
                "Release builds need a Gemma model SHA-256. Set MINDLAYER_MODEL_CACHE (or " +
                    "-Pmindlayer.modelCache=<dir>) so it can be derived from the cached " +
                    "gemma-4-E2B-it.litertlm, or pass -PmodelSha256=<64 lowercase hex>.",
            )
        }
        val manifest = File(props["manifestPath"] as String)
        if (!manifest.isFile) {
            throw GradleException("Release builds require ${manifest.path}.")
        }
        val manifestSha = Regex(""""sha256"\s*:\s*"([0-9a-f]{64})"""")
            .find(manifest.readText())
            ?.groupValues
            ?.get(1)
            ?: throw GradleException("model_integrity.json must contain a 64-character lowercase sha256 value.")
        if (manifestSha != sha) {
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
    description = "Fails release builds unless the four PaddleOCR SHA-256 digests (from -PpaddleOcr*Sha256 or the local cache) match the manifest."
    inputs.property("detSha256", paddleOcrDetSha256)
    inputs.property("recSha256", paddleOcrRecSha256)
    inputs.property("clsSha256", paddleOcrClsSha256)
    inputs.property("dictSha256", paddleOcrDictSha256)
    inputs.property("manifestPath", paddleIntegrityManifestPath)
    doLast {
        val props = inputs.properties
        val sha256Re = Regex("^[0-9a-f]{64}$")
        val detSha = props["detSha256"] as String
        val recSha = props["recSha256"] as String
        val clsSha = props["clsSha256"] as String
        val dictSha = props["dictSha256"] as String
        val missing = buildList {
            if (!sha256Re.matches(detSha)) add("detection")
            if (!sha256Re.matches(recSha)) add("recognition")
            if (!sha256Re.matches(clsSha)) add("orientation")
            if (!sha256Re.matches(dictSha)) add("dictionary")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release builds need PaddleOCR SHA-256 digests for: ${missing.joinToString(", ")}. " +
                    "Set MINDLAYER_MODEL_CACHE (or -Pmindlayer.modelCache=<dir>) so they can be " +
                    "derived from the cached PP-OCRv5 files, or pass the matching -PpaddleOcr*Sha256.",
            )
        }
        val manifest = File(props["manifestPath"] as String)
        if (!manifest.isFile) {
            throw GradleException(
                "Release builds require paddleocr_model/src/main/assets/paddleocr_model_integrity.json.",
            )
        }
        val byRole = Regex(
            """"filename"\s*:\s*"([^"]+)"\s*,\s*"sha256"\s*:\s*"([0-9a-f]{64})"\s*,\s*"role"\s*:\s*"([^"]+)"""",
        )
            .findAll(manifest.readText())
            .associate { match -> match.groupValues[3] to match.groupValues[2] }

        fun assertRole(role: String, expected: String) {
            val actual = byRole[role]
                ?: throw GradleException(
                    "paddleocr_model_integrity.json missing entry for role '$role'.",
                )
            if (actual != expected.lowercase()) {
                throw GradleException(
                    "paddleocr_model_integrity.json sha256 for '$role' does not match the expected digest.",
                )
            }
        }
        assertRole("detection", detSha)
        assertRole("recognition", recSha)
        assertRole("orientation", clsSha)
        assertRole("dictionary", dictSha)
    }
}

// Phase D #1 (release-validation): mirror the Gemma + PaddleOCR release
// SHA guards for the two EmbeddingGemma AI-pack artifacts. The
// :gemma_embed_model module's own generateEmbeddingModelIntegrityManifest
// task already fails release builds if either -PembeddingModelSha256 or
// -PembeddingTokenizerSha256 is missing/malformed; this validator adds
// the same defense-in-depth cross-check on the :app side, ensuring the
// manifest file on disk matches the properties passed in. Without this
// cross-check, a buggy CI script could silently regenerate the manifest
// with zero hashes while still passing the per-module guard.
val validateReleaseEmbeddingSha256 by tasks.registering {
    dependsOn(":gemma_embed_model:generateEmbeddingModelIntegrityManifest")
    group = "verification"
    description = "Fails release builds unless the EmbeddingGemma weights + tokenizer SHA-256 (from -Pembedding*Sha256 or the local cache) match embedding_model_integrity.json."
    inputs.property("modelSha256", embeddingModelSha256)
    inputs.property("tokenizerSha256", embeddingTokenizerSha256)
    inputs.property("manifestPath", embeddingIntegrityManifestPath)
    doLast {
        val props = inputs.properties
        val sha256Re = Regex("^[0-9a-f]{64}$")
        val modelSha = props["modelSha256"] as String
        val tokenizerSha = props["tokenizerSha256"] as String
        val missing = buildList {
            if (!sha256Re.matches(modelSha)) add("weights")
            if (!sha256Re.matches(tokenizerSha)) add("tokenizer")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "Release builds need EmbeddingGemma SHA-256 digests for: ${missing.joinToString(", ")}. " +
                    "Set MINDLAYER_MODEL_CACHE (or -Pmindlayer.modelCache=<dir>) so they can be " +
                    "derived from the cached files, or pass -PembeddingModelSha256 / -PembeddingTokenizerSha256.",
            )
        }
        val manifest = File(props["manifestPath"] as String)
        if (!manifest.isFile) {
            throw GradleException(
                "Release builds require gemma_embed_model/src/main/assets/embedding_model_integrity.json.",
            )
        }
        val byRole = Regex(
            """"filename"\s*:\s*"([^"]+)"\s*,\s*"sha256"\s*:\s*"([0-9a-f]{64})"\s*,\s*"role"\s*:\s*"([^"]+)"""",
        )
            .findAll(manifest.readText())
            .associate { match -> match.groupValues[3] to match.groupValues[2] }

        fun assertRole(role: String, expected: String) {
            val actual = byRole[role]
                ?: throw GradleException(
                    "embedding_model_integrity.json missing entry for role '$role'.",
                )
            if (actual != expected.lowercase()) {
                throw GradleException(
                    "embedding_model_integrity.json sha256 for '$role' does not match the expected digest.",
                )
            }
        }
        assertRole("weights", modelSha)
        assertRole("tokenizer", tokenizerSha)
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

android {
    namespace = "com.adsamcik.mindlayer.service"
    // Compose BOM 2026.05.01 pulls androidx.compose.* 1.12.0-alpha03 and
    // material3 1.5.0-alpha20, which require compileSdk 37 via AAR
    // metadata. minSdk is intentionally unchanged.
    compileSdk = 37

    defaultConfig {
        applicationId = "com.adsamcik.mindlayer.service"
        minSdk = 26
        // targetSdk 37 (Android 17 / API 37). Audited against the API-37
        // behaviour changes: no native System.load() DCL (only loadLibrary
        // of bundled libs), no MessageQueue reflection, no orientation lock,
        // no INTERNET/local-network/SMS/contacts/WebView/background-audio
        // surfaces. The dashboard activities are fully resizable, so the
        // large-screen orientation/resizability restrictions are no-ops here.
        targetSdk = 37
        versionCode = 5
        versionName = "1.0.0-alpha.2"
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
        // AIDL interfaces + parcelables are defined and compiled ONLY in :sdk;
        // :app consumes the generated Binder classes (IMindlayerService.Stub,
        // IClientCallback, parcelables) via implementation(project(":sdk")). The
        // app must NOT compile its own copy, or R8 fails the release build with
        // "Type ... is defined multiple times" (the same class would be present
        // in both the app's javac output and the :sdk runtime jar).
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
        bundle("mindlayer.bundleEmbeddings", ":gemma_embed_model")
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

    // The release AI Asset Pack staging tasks read each pack module's
    // src/main/assets directly. By AGP's default ordering the per-module model
    // provisioning + integrity-manifest tasks would otherwise run AFTER staging,
    // so the cache-derived model bytes and freshly-pinned SHAs would not make it
    // into THIS build's AAB/APK. Force both to complete before staging.
    val stagesReleaseAssetPacks =
        name == "assetPackReleasePreBundleTask" ||
        name == "processReleaseAssetPackManifests" ||
        name == "packageReleaseAssetPackManifest" ||
        name == "assetPackReleaseAssemble"
    if (stagesReleaseAssetPacks) {
        dependsOn(
            ":gemma_model:provisionReleaseModelAssets",
            ":gemma_model:generateModelIntegrityManifest",
            ":gemma_embed_model:provisionReleaseModelAssets",
            ":gemma_embed_model:generateEmbeddingModelIntegrityManifest",
            ":paddleocr_model:provisionReleaseModelAssets",
            ":paddleocr_model:generatePaddleOcrModelIntegrityManifest",
        )
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
        // The release-variant CI step exists to catch release-only source-set
        // compile errors (it implicitly runs :app:compileRelease*Kotlin). The
        // rest of the :app unit suite is Robolectric-heavy and not release-
        // clean, so we scope the release run to one fast, pure-JVM smoke test.
        // Replaces DebugAllowlistSeederReleaseAbsenceTest (subject deleted in
        // the v0.10 consent migration). See ConsentReleaseSmokeTest.
        filter.includeTestsMatching("com.adsamcik.mindlayer.service.security.ConsentReleaseSmokeTest")
        filter.includeTestsMatching("com.adsamcik.mindlayer.service.security.DebugAutoAcceptReleaseAbsenceTest")
        filter.includeTestsMatching("com.adsamcik.mindlayer.service.engine.mock.MockEnginesReleaseAbsenceTest")
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
    implementation(project(":sdk"))

    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.mockk.android)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(project(":sdk"))

    // OCR engine benchmark — Tesseract4Android. ANDROIDTEST ONLY. Never
    // shipped in :app or :sdk. The benchmark is `OcrEngineBenchmarkInstrumentedTest`
    // and exists to compare PaddleOCR vs Tesseract on a fixture image set.
    // Pulled from JitPack via a content-scoped repository declaration in
    // `settings.gradle.kts` (Apache 2.0).
    androidTestImplementation(libs.tesseract4android)
}

