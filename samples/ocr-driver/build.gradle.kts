plugins {
    id("mindlayer.android.application")
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.adsamcik.mindlayer.ocrdriver"

    defaultConfig {
        applicationId = "com.adsamcik.mindlayer.ocrdriver"
        versionCode = 1
        versionName = "0.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Sign with a stable, project-checked-in keystore so the signing-cert
        // SHA-256 the user approves once (Mindlayer pins it per package) stays
        // identical across dev machines. With the consent model any signing key
        // works to bind — there is no longer a signature|knownSigner gate — but
        // a per-machine ~/.android/debug.keystore would change the pinned cert
        // and force re-consent on every machine.
        create("knownCertsOwner") {
            storeFile = rootProject.file("app/keystores/knowncerts-owner.jks")
            storePassword = "knowncertstest"
            keyAlias = "knowncerts-owner"
            keyPassword = "knowncertstest"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("knownCertsOwner")
        }
        debug {
            signingConfig = signingConfigs.getByName("knownCertsOwner")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(project(":sdk"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.viewmodel.compose)
    implementation(libs.lifecycle.runtime.compose)
    debugImplementation(libs.compose.ui.tooling)
}
