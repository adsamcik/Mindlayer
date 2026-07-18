plugins {
    id("mindlayer.android.library.published")
    alias(libs.plugins.kotlin.serialization)
    id("kotlin-parcelize")
}

android {
    namespace = "com.adsamcik.mindlayer.shared"
}

mindlayerPublish {
    pomName.set("Mindlayer Shared Types")
    pomDescription.set("Shared Parcelable types and streaming protocol for Mindlayer SDK")
}

dependencies {
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.junit)
}
