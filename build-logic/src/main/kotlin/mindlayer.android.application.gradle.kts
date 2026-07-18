import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Common configuration for Mindlayer Android application modules: SDK levels,
 * Java 17 / JVM 17 targets, and packaging excludes. Modules still declare their
 * own `namespace`, `applicationId`, versioning, signing, `buildTypes`, and
 * `buildFeatures`.
 */
plugins {
    id("com.android.application")
}

android {
    compileSdk = Mindlayer.COMPILE_SDK

    defaultConfig {
        minSdk = Mindlayer.MIN_SDK
        targetSdk = Mindlayer.TARGET_SDK
    }

    compileOptions {
        sourceCompatibility = Mindlayer.JAVA_VERSION
        targetCompatibility = Mindlayer.JAVA_VERSION
    }

    packaging {
        resources {
            excludes += Mindlayer.RESOURCE_EXCLUDES
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
