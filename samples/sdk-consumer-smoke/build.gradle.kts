plugins {
    id("mindlayer.android.library")
}

android {
    namespace = "com.adsamcik.mindlayer.sdk.consumercheck"
}

dependencies {
    // Intentionally no independent coroutine or serialization dependency:
    // the SDK must supply the types exposed by its public API.
    implementation(project(":sdk"))
}
