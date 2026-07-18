plugins {
    id("mindlayer.jvm.library")
}

dependencies {
    compileOnly(libs.android.tools.lint.api)
    compileOnly(libs.android.tools.lint.checks)

    testImplementation(libs.junit)
    testImplementation(libs.android.tools.lint.api)
    testImplementation(libs.android.tools.lint.tests)
}
