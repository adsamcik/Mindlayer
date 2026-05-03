import java.util.Properties
import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
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
val hasReleaseKeystore = keystoreProperties.getProperty("storeFile")?.let {
    rootProject.file(it).exists()
} ?: false
val modelSha256 = project.findProperty("modelSha256")?.toString()?.trim().orEmpty()
val modelSha256Pattern = Regex("^[0-9a-f]{64}$")

val validateReleaseModelSha256 by tasks.registering {
    group = "verification"
    description = "Fails release builds unless -PmodelSha256 is a lowercase SHA-256 digest."
    doLast {
        if (!modelSha256Pattern.matches(modelSha256)) {
            throw GradleException(
                "Release builds require -PmodelSha256=<64 lowercase hex SHA-256 of the bundled .litertlm model>.",
            )
        }
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

android {
    namespace = "com.adsamcik.mindlayer.service"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.adsamcik.mindlayer.service"
        minSdk = 26
        targetSdk = 36
        versionCode = 3
        versionName = "0.3.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Only bundle resources for the locales we actually ship.
        // Expand this list when translations are added.
        resourceConfigurations += listOf("en")

        // F-002: hex-encoded SHA-256 of the bundled model file. When
        // non-empty, the engine refuses to load any .litertlm whose hash
        // does not match. Populate from CI (or `-PmodelSha256=…`) once the
        // model becomes part of the artifact pipeline; an empty string
        // keeps verification advisory (logged warning only) so debug
        // builds work without the manifest.
        buildConfigField("String", "MODEL_SHA256", "\"${modelSha256.lowercase()}\"")
    }

    signingConfigs {
        if (hasReleaseKeystore) {
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
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

    assetPacks += listOf(":gemma_model")

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

tasks.configureEach {
    val isReleasePackageTask = name.contains("Release") &&
        (name.startsWith("assemble") || name.startsWith("bundle") || name.startsWith("package"))
    if (isReleasePackageTask) {
        dependsOn(validateReleaseModelSha256)
    }
    // F-079: every public APK/AAB packaging task must see the ABI validator.
    // assembleDebug + assembleRelease + bundleRelease are the lifecycle tasks
    // CI invokes; the validator's <200 ms warm cost makes blanket wiring safe.
    if (name == "assembleDebug" || name == "assembleRelease" || name == "bundleRelease") {
        dependsOn(validateLitertlmAbis)
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
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
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.litertlm.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.navigation.compose)
    debugImplementation(libs.compose.ui.tooling)

    // F-029: BiometricPrompt for sensitive Approve / Revoke actions; FragmentActivity host.
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.fragment.ktx)

    // Room (for logging database)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    implementation(libs.sqlcipher.android)
    ksp(libs.room.compiler)

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
}
