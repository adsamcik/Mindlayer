import java.util.Properties

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
