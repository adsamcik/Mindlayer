import java.util.Properties
import java.util.Base64

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
    }

    signingConfigs {
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
