plugins {
    alias(libs.plugins.android.library)
    id("org.jetbrains.kotlin.plugin.compose") version libs.versions.kotlin.get()
    id("kotlin-parcelize")
    id("maven-publish")
}

android {
    namespace = "com.adsamcik.mindlayer.sdk.camera.launcher"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
        consumerProguardFiles("consumer-rules.pro")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

afterEvaluate {
    publishing {
        publications {
            create<MavenPublication>("release") {
                from(components["release"])
                groupId = "com.adsamcik.mindlayer"
                artifactId = "sdk-camera-launcher"
                version = rootProject.extra.get("publishVersion") as String

                pom {
                    name.set("Mindlayer SDK camera launcher")
                    description.set(
                        "Turn-key Activity-result-based camera capture flow that " +
                            "calls Mindlayer.ocrRealtime() or Mindlayer.ocrAsync() under " +
                            "the hood. Consumers register a single ActivityResultContract " +
                            "and never touch CameraX or runtime permissions directly.",
                    )
                }
            }
        }

        repositories {
            maven {
                name = "GitHubPackages"
                url = uri("https://maven.pkg.github.com/${rootProject.extra.get("githubOwner")}/${rootProject.extra.get("githubRepo")}")
                credentials {
                    username = rootProject.extra.get("githubOwner") as String
                    password = rootProject.extra.get("githubToken") as String
                }
            }
        }
    }
}

dependencies {
    // The launcher's public types reference :sdk (Mindlayer, OcrProfile, OcrImageResult) and
    // :shared (OcrSessionConfig, OcrFrameMeta) — expose them transitively.
    api(project(":sdk"))
    api(project(":shared"))

    // Internally drives a CameraX-based session; reuses OcrImageAnalyzer
    // and OcrFrame so the realtime path is identical to bring-your-own-
    // CameraX integrations.
    implementation(project(":sdk-camerax"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.guava)

    // CameraX surface for the capture activity. All four artifacts are
    // bundled because consumers don't ship their own camera UI when
    // they use this module — we own the surface end-to-end.
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)
    implementation(libs.camerax.view)

    // Compose UI for the in-activity preview + capture controls. Bundled
    // because the activity ships its own UI, not the consumer's.
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.core)
    implementation(libs.activity.compose)
    implementation(libs.lifecycle.runtime.compose)
    implementation(libs.lifecycle.viewmodel.compose)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
}
