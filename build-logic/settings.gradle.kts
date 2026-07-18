// Composite build hosting Mindlayer's Gradle convention plugins. Kept as an
// included build (not buildSrc) so editing a convention plugin doesn't
// invalidate the whole main-build classpath cache on every change.
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    // Reuse the single source of truth for versions so the convention plugins
    // and the modules they configure never drift.
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
