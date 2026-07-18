import org.jetbrains.kotlin.gradle.dsl.JvmTarget

/**
 * Plain Kotlin/JVM library (no Android) on Java 17 / JVM 17. Used by
 * :lint-checks.
 */
plugins {
    id("java-library")
    id("org.jetbrains.kotlin.jvm")
}

java {
    sourceCompatibility = Mindlayer.JAVA_VERSION
    targetCompatibility = Mindlayer.JAVA_VERSION
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}
