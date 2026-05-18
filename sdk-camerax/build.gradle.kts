plugins {
    alias(libs.plugins.android.library)
    id("maven-publish")
}

android {
    namespace = "com.adsamcik.mindlayer.sdk.camerax"
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
                artifactId = "sdk-camerax"
                version = rootProject.extra.get("publishVersion") as String

                pom {
                    name.set("Mindlayer SDK CameraX integration")
                    description.set(
                        "Optional CameraX ImageAnalysis.Analyzer integration for the " +
                            "Mindlayer multi-frame OCR API. Brings your own CameraX " +
                            "version (compileOnly).",
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
    // Re-expose Mindlayer SDK types so consumers can call ocrSession()
    // directly. `api` because OcrFrameMeta + OcrSession appear in this
    // module's public signatures.
    api(project(":sdk"))
    api(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)

    // CameraX is compileOnly so consumers pick their own version. The
    // analyser interfaces and ImageProxy / ImageInfo APIs have been
    // stable across CameraX 1.3+; consumers on older versions can use
    // the OcrFrame.fromYPlane(...) helper directly without ever
    // touching the CameraX types this module references.
    compileOnly("androidx.camera:camera-core:1.4.0")

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation("androidx.camera:camera-core:1.4.0")
}
