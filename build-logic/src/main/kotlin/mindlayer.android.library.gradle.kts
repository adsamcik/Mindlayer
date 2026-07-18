import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Common configuration for every Mindlayer Android library module: SDK levels,
 * Java 17 / JVM 17 targets, the non-minified release variant, consumer ProGuard
 * rules, packaging excludes, and a publishable `release` variant with sources.
 *
 * Modules still declare their own `namespace` and any module-specific
 * `buildFeatures` (aidl, compose) and dependencies.
 */
plugins {
    id("com.android.library")
}

android {
    compileSdk = Mindlayer.COMPILE_SDK

    defaultConfig {
        minSdk = Mindlayer.MIN_SDK
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
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

    // Every Mindlayer library publishes its release variant with a sources jar.
    // Declaring it here (vs the publish convention) keeps the software component
    // available even for library-only consumers.
    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
