plugins {
    id("mindlayer.android.library.published")
    alias(libs.plugins.kotlin.compose)
    id("kotlin-parcelize")
}

android {
    namespace = "com.adsamcik.mindlayer.sdk.camera.launcher"

    buildFeatures {
        compose = true
    }
}

mindlayerPublish {
    pomName.set("Mindlayer SDK camera launcher")
    pomDescription.set(
        "Turn-key Activity-result-based camera capture flow that " +
            "calls Mindlayer.ocrRealtime() or Mindlayer.ocrAsync() under " +
            "the hood. Consumers register a single ActivityResultContract " +
            "and never touch CameraX or runtime permissions directly.",
    )
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
