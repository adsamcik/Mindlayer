plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Publishing configuration for GitHub Packages
// Version: override with -PpublishVersion=X.Y.Z or via tag (CI extracts from v-tag)
val publishVersion = findProperty("publishVersion")?.toString() ?: "0.1.0"
val githubOwner = findProperty("GITHUB_OWNER")?.toString() ?: System.getenv("GITHUB_OWNER") ?: "OWNER"
val githubRepo = findProperty("GITHUB_REPO")?.toString() ?: System.getenv("GITHUB_REPO") ?: "Mindlayer"
val githubToken = findProperty("GITHUB_TOKEN")?.toString() ?: System.getenv("GITHUB_TOKEN") ?: ""

extra["publishVersion"] = publishVersion
extra["githubOwner"] = githubOwner
extra["githubRepo"] = githubRepo
extra["githubToken"] = githubToken
