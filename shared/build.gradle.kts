plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
    id("maven-publish")
}

android {
    namespace = "com.mindlayer.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 26
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
                groupId = "com.mindlayer"
                artifactId = "shared"
                version = rootProject.extra.get("publishVersion") as String

                pom {
                    name.set("Mindlayer Shared Types")
                    description.set("Shared Parcelable types and streaming protocol for Mindlayer SDK")
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
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
