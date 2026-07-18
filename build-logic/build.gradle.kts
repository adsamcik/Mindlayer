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

// ── Security: pin patched build-only transitives on the build-logic classpath ──
// This is a separate (included) build, so the root project's `allprojects`
// resolution forces do NOT reach it. AGP's signing tooling (apksig) drags in the
// vulnerable BouncyCastle 1.79 family (critical GHSA-574f-3g2m-x479, fixed in
// 1.81.1) and AGP's Unified Test Platform pulls older netty — both then appear in
// the submitted Gradle dependency graph even though neither ships in any
// artifact. Mirror the root build's pins here so build-logic stays clean too.
// Keep this list in sync with `mindlayerSecurityDependencyForces` in the root
// build.gradle.kts.
configurations.configureEach {
    resolutionStrategy.force(
        "org.bouncycastle:bcprov-jdk18on:1.85",
        "org.bouncycastle:bcpkix-jdk18on:1.85",
        "org.bouncycastle:bcutil-jdk18on:1.85",
        "io.netty:netty-buffer:4.2.16.Final",
        "io.netty:netty-codec:4.2.16.Final",
        "io.netty:netty-codec-http:4.2.16.Final",
        "io.netty:netty-codec-http2:4.2.16.Final",
        "io.netty:netty-codec-socks:4.2.16.Final",
        "io.netty:netty-common:4.2.16.Final",
        "io.netty:netty-handler:4.2.16.Final",
        "io.netty:netty-handler-proxy:4.2.16.Final",
        "io.netty:netty-resolver:4.2.16.Final",
        "io.netty:netty-transport:4.2.16.Final",
        "io.netty:netty-transport-native-unix-common:4.2.16.Final",
    )
}
