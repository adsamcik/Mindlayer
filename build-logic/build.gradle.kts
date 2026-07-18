plugins {
    `kotlin-dsl`
}

// Convention plugins apply AGP and the Kotlin Gradle plugin by id, so both must
// be on this build's compile classpath (the AGP jar also provides the
// com.android.asset-pack plugin used by mindlayer.assetpack). Versions come from
// the shared version catalog imported in settings.gradle.kts.
dependencies {
    implementation(libs.android.gradlePlugin)
    implementation(libs.kotlin.gradlePlugin)
}
