// ── Security: pin patched BouncyCastle on the plugin/buildscript classpath ──────
// AGP's APK-signing tooling (bundletool → apksig) drags in
// org.bouncycastle:*-jdk18on 1.79 onto the buildscript classpath, which trips the
// critical GHSA-574f-3g2m-x479 advisory (vulnerable <= 1.80.1). It never ships in
// any artifact, but it appears in the submitted Gradle dependency graph, so we
// force the patched 1.81.1 here. The `allprojects` block at the bottom of this
// file pins the project-configuration (Robolectric / Unified Test Platform)
// counterparts.
buildscript {
    configurations.classpath {
        resolutionStrategy.force(
            "org.bouncycastle:bcprov-jdk18on:1.81.1",
            "org.bouncycastle:bcpkix-jdk18on:1.81.1",
            "org.bouncycastle:bcutil-jdk18on:1.81.1",
        )
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
}

// Publishing configuration for GitHub Packages
// Version: override with -PpublishVersion=X.Y.Z or via tag (CI extracts from v-tag)
val publishVersion = findProperty("publishVersion")?.toString() ?: "1.0.0-alpha.4"
val githubOwner = findProperty("GITHUB_OWNER")?.toString() ?: System.getenv("GITHUB_OWNER") ?: "OWNER"
val githubRepo = findProperty("GITHUB_REPO")?.toString() ?: System.getenv("GITHUB_REPO") ?: "Mindlayer"
val githubToken = findProperty("GITHUB_TOKEN")?.toString() ?: System.getenv("GITHUB_TOKEN") ?: ""

extra["publishVersion"] = publishVersion
extra["githubOwner"] = githubOwner
extra["githubRepo"] = githubRepo
extra["githubToken"] = githubToken

// ── Product/contract version synchronization ───────────────────────────────
// The product version (publishVersion, above) and the AIDL/wire contract
// version (com.adsamcik.mindlayer.shared.ContractVersion, in :shared) are
// two DELIBERATELY separate numbers — see docs/architecture/AIDL_STABILITY.md
// § "Contract version and compatibility policy" — but they MUST share the
// same MAJOR component. This is the single enforcement point: bump BOTH
// `contractMajorVersion` below AND `ContractVersion.MAJOR` in the same PR
// whenever either the product or the contract needs a major bump. Neither
// side derives the other automatically — that would let a contract-breaking
// change slip out under a same-major product patch release, or vice versa;
// a deliberate, symmetric edit in both places is the point.
val contractMajorVersion = 1
val productMajorVersion = publishVersion.substringBefore("-").substringBefore(".").toInt()
require(productMajorVersion == contractMajorVersion) {
    "publishVersion's major ($productMajorVersion, from '$publishVersion') must equal " +
        "contractMajorVersion ($contractMajorVersion) declared here. Bump " +
        "ContractVersion.MAJOR in " +
        "shared/src/main/kotlin/com/adsamcik/mindlayer/shared/ContractVersion.kt " +
        "and this constant together — see docs/architecture/AIDL_STABILITY.md."
}

/**
 * Deterministic Android `versionCode` derived from a semver-with-prerelease
 * string like `"1.0.0-alpha.4"` or `"1.2.3"`. Encodes
 * `MAJOR*1_000_000 + MINOR*10_000 + PATCH*100 + PRERELEASE_NUM`, where a
 * stable release (no prerelease suffix) sorts after any alpha/beta/rc of
 * the same MAJOR.MINOR.PATCH (`PRERELEASE_NUM = 99`).
 *
 * Introduced because `:app`'s own `versionCode`/`versionName` had drifted
 * from `publishVersion` for multiple releases (both were bumped by hand,
 * never in lockstep). Deriving `:app`'s Android version directly from
 * `publishVersion` (see `app/build.gradle.kts`) eliminates that class of
 * drift going forward.
 */
fun versionCodeFor(semverWithPrerelease: String): Int {
    val (core, prerelease) = semverWithPrerelease.split("-", limit = 2)
        .let { it[0] to it.getOrNull(1) }
    val (major, minor, patch) = core.split(".").map { it.trim().toInt() }
    val prereleaseNum = prerelease?.substringAfterLast(".")?.toIntOrNull() ?: 99
    return major * 1_000_000 + minor * 10_000 + patch * 100 + prereleaseNum
}

// versionCode 5 ("1.0.0-alpha.2") already shipped under the old manual
// scheme; every publishVersion from "1.0.0-alpha.3" onward already yields
// >= 1_000_003 under the new scheme, but this guards against a future
// rescoping of the function silently producing a lower/duplicate code.
extra["productVersionCode"] = versionCodeFor(publishVersion).also { code ->
    require(code >= 6) {
        "versionCode $code for '$publishVersion' must be >= 6 (versionCode 5 already shipped as 1.0.0-alpha.2)"
    }
}

// ── Release model provisioning from a local cache (never committed) ───────────
// Release AABs/APKs bundle the on-device AI models (Gemma chat, EmbeddingGemma,
// PaddleOCR) as install-time AI Asset Packs. The multi-GB model bytes are NOT in
// git (.gitignore). For a LOCAL release we source them from a flat cache dir —
// the same one tools/dev-models/push-models.* uses — resolved from
// `-Pmindlayer.modelCache=<path>` or the `MINDLAYER_MODEL_CACHE` env var.
//
// Gated on a release task + a resolved cache, we hash each canonical file ONCE
// here and expose the digests via `extra`. Downstream:
//   * each AI-pack module writes the digest into its *_integrity.json manifest
//     and copies the bytes into src/main/assets/ (provisionReleaseModelAssets);
//   * :app feeds BuildConfig.MODEL_SHA256 and the release SHA validators.
// The cache is the trusted source of truth, so a local release needs no
// hand-typed -P*Sha256 flags. CI keeps passing its existing -P*Sha256 values,
// which still take precedence in every consumer.
//
// Config-cache note: the digest is captured at configuration time (like the
// pre-existing -PmodelSha256 value it replaces). If you swap model bytes WITHOUT
// changing the filename, run a release with `--no-configuration-cache` so the
// new digest is recomputed.
val mindlayerReleaseTaskRequested: Boolean = gradle.startParameter.taskNames.any {
    it.contains("Release") && !it.contains("UnitTest")
}

val mindlayerModelCachePath: String = (
    providers.gradleProperty("mindlayer.modelCache").orNull
        ?: providers.environmentVariable("MINDLAYER_MODEL_CACHE").orNull
).orEmpty().trim()

fun mindlayerSha256Hex(target: File): String {
    val digest = java.security.MessageDigest.getInstance("SHA-256")
    target.inputStream().buffered().use { input ->
        val buffer = ByteArray(1 shl 20)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { "%02x".format(it) }
}

// Canonical filenames the three AI-pack modules expect (flat cache layout,
// matching docs/models/DEV_MODELS.md). Kept in sync with each module + push-models.*.
val mindlayerCanonicalModelFiles = listOf(
    "gemma-4-E2B-it.litertlm",
    "embedding-gemma-300m-v1.tflite",
    "embedding-gemma-300m-v1.spm.model",
    "paddleocr-ppocrv5-mobile-det.tflite",
    "paddleocr-ppocrv5-mobile-rec.tflite",
    "paddleocr-ppocrv5-mobile-cls.tflite",
    "paddleocr-ppocrv5-mobile-dict.txt",
)

val mindlayerModelShas: Map<String, String> =
    if (mindlayerReleaseTaskRequested && mindlayerModelCachePath.isNotEmpty()) {
        val cacheDir = file(mindlayerModelCachePath)
        mindlayerCanonicalModelFiles.mapNotNull { name ->
            val candidate = cacheDir.resolve(name)
            if (candidate.isFile && candidate.length() > 1L) {
                name to mindlayerSha256Hex(candidate)
            } else {
                null
            }
        }.toMap()
    } else {
        emptyMap()
    }

extra["mindlayerReleaseTaskRequested"] = mindlayerReleaseTaskRequested
extra["mindlayerModelCachePath"] = mindlayerModelCachePath
extra["mindlayerModelShas"] = mindlayerModelShas

// ── Security: force patched versions of vulnerable BUILD/TEST-only transitives ──
// None of these ship in the :app / :sdk APKs — they arrive purely through build
// and test tooling — but they trip Dependabot advisories in the submitted Gradle
// dependency graph, so we pin the patched versions across every project config:
//   • org.bouncycastle:*-jdk18on 1.79 / 1.81.0 — pulled by Robolectric (unit
//     tests) and AGP signing tooling. Critical GHSA-574f-3g2m-x479 is fixed in
//     1.81.1 (the whole -jdk18on family is released together and must match).
//   • io.netty:* 4.1.93 / 4.1.110.Final — pulled by AGP's Unified Test Platform
//     (grpc-netty emulator control). High GHSA-c653-97m9-rcg9, GHSA-x4gw-5cx5-pgmh,
//     GHSA-3qp7-7mw8-wx86 and moderate GHSA-hvcg-qmg6-jm4c, GHSA-563q-j3cm-6jxm,
//     GHSA-c2gf-v879-257j, GHSA-5x3r-wrvg-rp6q are fixed in 4.1.135.Final. The
//     entire netty family is pinned so all modules stay on one aligned version.
val mindlayerSecurityDependencyForces = listOf(
    "org.bouncycastle:bcprov-jdk18on:1.81.1",
    "org.bouncycastle:bcpkix-jdk18on:1.81.1",
    "org.bouncycastle:bcutil-jdk18on:1.81.1",
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

allprojects {
    configurations.configureEach {
        resolutionStrategy.force(*mindlayerSecurityDependencyForces.toTypedArray())
    }
}
