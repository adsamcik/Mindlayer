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
// resolution forces do NOT reach it. AGP signing, emulator control and other
// build tools bring these libraries onto the plugin classpath. Mirror the root
// build's patched versions so the actual loaded classes and submitted dependency
// graph agree.
// Keep this list in sync with `mindlayerSecurityDependencyForces` in the root
// build.gradle.kts.
configurations.configureEach {
    resolutionStrategy.force(
        "org.bouncycastle:bcprov-jdk18on:1.85",
        "org.bouncycastle:bcpkix-jdk18on:1.85",
        "org.bouncycastle:bcutil-jdk18on:1.85",
        // GHSA-2363-cqg2-863c, GHSA-3677-xxcr-wjqv, GHSA-j288-q9x7-2f5v.
        "org.jdom:jdom2:2.0.6.1",
        "org.bitbucket.b_c:jose4j:0.9.6",
        "org.apache.commons:commons-lang3:3.18.0",
        "io.netty:netty-buffer:4.2.17.Final",
        "io.netty:netty-codec:4.2.17.Final",
        "io.netty:netty-codec-http:4.2.17.Final",
        "io.netty:netty-codec-http2:4.2.17.Final",
        "io.netty:netty-codec-socks:4.2.17.Final",
        "io.netty:netty-common:4.2.17.Final",
        "io.netty:netty-handler:4.2.17.Final",
        "io.netty:netty-handler-proxy:4.2.17.Final",
        "io.netty:netty-resolver:4.2.17.Final",
        "io.netty:netty-transport:4.2.17.Final",
        "io.netty:netty-transport-native-unix-common:4.2.17.Final",
    )
}
