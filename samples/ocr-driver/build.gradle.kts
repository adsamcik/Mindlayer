import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    id("org.jetbrains.kotlin.plugin.compose") version libs.versions.kotlin.get()
}

android {
    namespace = "com.adsamcik.mindlayer.ocrdriver"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.adsamcik.mindlayer.ocrdriver"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        // Sign with the same project-checked-in dev keystore the :app debug
        // variant uses. The `BIND_ML_SERVICE` permission on :app is gated
        // by signature|knownSigner with this cert's SHA-256 listed in
        // app/src/main/res/values/arrays.xml (mindlayer_trusted_client_certs).
        // Without this signingConfig the sample APK is signed by the
        // per-machine default ~/.android/debug.keystore and the service
        // refuses to bind.
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

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
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
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
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
