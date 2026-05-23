plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    compileOnly(libs.android.tools.lint.api)
    compileOnly(libs.android.tools.lint.checks)

    testImplementation(libs.junit)
    testImplementation(libs.android.tools.lint.api)
    testImplementation(libs.android.tools.lint.tests)
}
