plugins {
    id("mindlayer.android.library.published")
}

android {
    namespace = "com.adsamcik.mindlayer.sdk.camerax"
}

mindlayerPublish {
    pomName.set("Mindlayer SDK CameraX integration")
    pomDescription.set(
        "Optional CameraX ImageAnalysis.Analyzer integration for the " +
            "Mindlayer multi-frame OCR API. Brings your own CameraX " +
            "version (compileOnly).",
    )
}

dependencies {
    // Re-expose Mindlayer SDK types so consumers can call ocrSession()
    // directly. `api` because OcrFrameMeta + OcrSession appear in this
    // module's public signatures.
    api(project(":sdk"))
    api(project(":shared"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // CameraX is compileOnly so consumers pick their own version. The
    // analyser interfaces and ImageProxy / ImageInfo APIs have been
    // stable across CameraX 1.3+; consumers on older versions can use
    // the OcrFrame.fromYPlane(...) helper directly without ever
    // touching the CameraX types this module references.
    compileOnly(libs.camerax.core)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.camerax.core)
}
