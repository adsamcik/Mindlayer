package com.adsamcik.mindlayer.lint

import com.android.tools.lint.checks.infrastructure.ProjectDescription
import com.android.tools.lint.checks.infrastructure.TestFiles.gradle
import com.android.tools.lint.checks.infrastructure.TestFiles.manifest
import com.android.tools.lint.checks.infrastructure.TestFiles.xml
import com.android.tools.lint.checks.infrastructure.TestLintTask.lint
import org.junit.Test

class NoNetworkTelemetryDetectorTest {
    @Test
    fun `flags blocked implementation and api dependencies in app and sdk build files`() {
        lint()
            .projects(
                ProjectDescription().name("app").files(
                    gradle(
                        "build.gradle.kts",
                        """
                            plugins { id("com.android.application") }

                            dependencies {
                                implementation("io.sentry:sentry-android:8.0.0")
                                api("com.google.mlkit:barcode-scanning:17.3.0")
                                implementation(
                                    "com.amazonaws:aws-android-sdk-s3:2.75.0"
                                )
                            }
                        """.trimIndent(),
                    ),
                ),
            )
            .issues(NoNetworkTelemetryDetector.ISSUE)
            .run()
            .expectContains("MindlayerNoNetworkTelemetry")
            .expectContains("io.sentry:")
            .expectContains("com.google.mlkit:")
            .expectContains("com.amazonaws:")
    }

    @Test
    fun `flags internet and network state permissions in app and sdk manifests`() {
        lint()
            .projects(
                ProjectDescription().name("sdk").files(
                    manifest(
                        """
                            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                                <uses-permission android:name="android.permission.INTERNET" />
                                <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
                            </manifest>
                        """.trimIndent(),
                    ),
                ),
            )
            .issues(NoNetworkTelemetryDetector.ISSUE)
            .run()
            .expectContains("MindlayerNoNetworkTelemetry")
            .expectContains("android.permission.INTERNET")
            .expectContains("android.permission.ACCESS_NETWORK_STATE")
    }

    @Test
    fun `allows only removal of transitive PAD network-state permission`() {
        lint()
            .projects(
                ProjectDescription().name("app").files(
                    manifest(
                        """
                            <manifest
                                xmlns:android="http://schemas.android.com/apk/res/android"
                                xmlns:tools="http://schemas.android.com/tools">
                                <uses-permission
                                    android:name="android.permission.ACCESS_NETWORK_STATE"
                                    tools:node="remove" />
                            </manifest>
                        """.trimIndent(),
                    ),
                ),
            )
            .issues(NoNetworkTelemetryDetector.ISSUE)
            .run()
            .expectClean()
    }

    @Test
    fun `rejects selector-scoped network-state permission removal`() {
        lint()
            .projects(
                ProjectDescription().name("app").files(
                    manifest(
                        """
                            <manifest
                                xmlns:android="http://schemas.android.com/apk/res/android"
                                xmlns:tools="http://schemas.android.com/tools">
                                <uses-permission
                                    android:name="android.permission.ACCESS_NETWORK_STATE"
                                    tools:node="remove"
                                    tools:selector="com.example.library" />
                            </manifest>
                        """.trimIndent(),
                    ),
                ),
            )
            .issues(NoNetworkTelemetryDetector.ISSUE)
            .run()
            .expectContains("MindlayerNoNetworkTelemetry")
            .expectContains("android.permission.ACCESS_NETWORK_STATE")
    }

    @Test
    fun `allows current offline dependencies and signature permission`() {
        lint()
            .projects(
                ProjectDescription().name("app").files(
                    gradle(
                        "build.gradle.kts",
                        """
                            dependencies {
                                implementation(project(":shared"))
                                implementation(libs.litertlm.android)
                                implementation(libs.androidx.core.ktx)
                                testImplementation("io.sentry:sentry-android:8.0.0")
                            }
                        """.trimIndent(),
                    ),
                    xml(
                        "src/main/AndroidManifest.xml",
                        """
                            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                                <uses-permission android:name="com.adsamcik.mindlayer.permission.BIND_ML_SERVICE" />
                            </manifest>
                        """.trimIndent(),
                    ),
                ),
            )
            .issues(NoNetworkTelemetryDetector.ISSUE)
            .run()
            .expectClean()
    }
}
