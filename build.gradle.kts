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
            "org.bouncycastle:bcprov-jdk18on:1.85",
            "org.bouncycastle:bcpkix-jdk18on:1.85",
            "org.bouncycastle:bcutil-jdk18on:1.85",
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
val publishVersion = findProperty("publishVersion")?.toString() ?: "1.0.0-alpha.5"
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
// PaddleOCR) as standard on-demand Play Asset Delivery packs. The multi-GB model bytes are NOT in
// git (.gitignore). For a LOCAL release we source them from a flat cache dir —
// the same one tools/dev-models/push-models.* uses — resolved from
// `-Pmindlayer.modelCache=<path>`, the `MINDLAYER_MODEL_CACHE` env var, or the
// standardized gitignored `<repo-root>/.models` directory when it exists.
//
// Gated on a release task + a resolved cache, we hash each canonical file ONCE
// here and expose the digests via `extra`. Downstream:
//   * each asset-pack module writes the digest into its *_integrity.json manifest
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
        ?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?: providers.environmentVariable("MINDLAYER_MODEL_CACHE").orNull
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
        ?: rootDir.resolve(".models")
            .takeIf { it.isDirectory }
            ?.absolutePath
).orEmpty()

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

// Canonical filenames the on-demand asset-pack modules expect (flat cache layout,
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

// ── Gemma on-demand fragment provisioning ───────────────────────────────────
// Standard Play Asset Delivery has a practical per-pack size ceiling below the
// Gemma 4 E2B binary. This application-level prototype splits the one
// `.litertlm` file into exactly two ordinary packs and reconstructs it locally;
// LiteRT-LM itself does not support sharded model loading.
val gemmaFragmentMaxBytes = 1_350_000_000L
val gemmaFullModelName = "gemma-4-E2B-it.litertlm"
val gemmaPartOneName = "$gemmaFullModelName.part1"
val gemmaPartTwoName = "$gemmaFullModelName.part2"
val gemmaPartOneMetadataName = "gemma_part_1_integrity.json"
val gemmaPartTwoMetadataName = "gemma_part_2_integrity.json"
val gemmaPartOneAssets = rootDir.resolve("gemma_model/src/main/assets")
val gemmaPartTwoAssets = rootDir.resolve("gemma_model_part_2/src/main/assets")
val legacyFullGemmaAsset = gemmaPartOneAssets.resolve(gemmaFullModelName)
val requestedGemmaSha = providers.gradleProperty("modelSha256").orNull
    ?.trim()
    ?.lowercase()
    ?.takeIf { it.isNotEmpty() }
    ?: mindlayerModelShas[gemmaFullModelName].orEmpty()
val gemmaFragmentVersion = publishVersion

val provisionGemmaFragments = tasks.register("provisionGemmaFragments") {
    group = "build"
    description = "Streams the cached Gemma model into two verified on-demand asset-pack fragments."
    notCompatibleWithConfigurationCache(
        "Streams a multi-gigabyte local model into generated asset-pack files; release builds disable the configuration cache.",
    )
    inputs.property("releaseRequested", mindlayerReleaseTaskRequested)
    inputs.property("modelCachePath", mindlayerModelCachePath)
    inputs.property("expectedFullSha256", requestedGemmaSha)
    inputs.property("maxPartBytes", gemmaFragmentMaxBytes)
    inputs.property("version", gemmaFragmentVersion)
    inputs.property("fullModelName", gemmaFullModelName)
    inputs.property("partOneName", gemmaPartOneName)
    inputs.property("partTwoName", gemmaPartTwoName)
    inputs.property("partOneMetadataName", gemmaPartOneMetadataName)
    inputs.property("partTwoMetadataName", gemmaPartTwoMetadataName)
    inputs.property("partOneAssetsPath", gemmaPartOneAssets.absolutePath)
    inputs.property("partTwoAssetsPath", gemmaPartTwoAssets.absolutePath)
    inputs.property("legacyFullModelPath", legacyFullGemmaAsset.absolutePath)
    outputs.files(
        gemmaPartOneAssets.resolve(gemmaPartOneName),
        gemmaPartOneAssets.resolve(gemmaPartOneMetadataName),
        gemmaPartOneAssets.resolve("model_integrity.json"),
        gemmaPartTwoAssets.resolve(gemmaPartTwoName),
        gemmaPartTwoAssets.resolve(gemmaPartTwoMetadataName),
    )
    doLast {
        val props = inputs.properties
        if (!(props["releaseRequested"] as Boolean)) return@doLast
        val cachePath = props["modelCachePath"] as String
        val expectedSha = props["expectedFullSha256"] as String
        val maxBytes = props["maxPartBytes"] as Long
        val version = props["version"] as String
        val fullModelName = props["fullModelName"] as String
        val partOneName = props["partOneName"] as String
        val partTwoName = props["partTwoName"] as String
        val partOneMetadataName = props["partOneMetadataName"] as String
        val partTwoMetadataName = props["partTwoMetadataName"] as String
        val partOneAssets = File(props["partOneAssetsPath"] as String)
        val partTwoAssets = File(props["partTwoAssetsPath"] as String)
        val legacySource = File(props["legacyFullModelPath"] as String)
        val source = cachePath.takeIf { it.isNotBlank() }
            ?.let(::File)
            ?.resolve(fullModelName)
            ?.takeIf { it.isFile && it.length() > maxBytes }
            ?: legacySource.takeIf { it.isFile && it.length() > maxBytes }
            ?: throw GradleException(
                "Release builds need $fullModelName in MINDLAYER_MODEL_CACHE (or " +
                    "-Pmindlayer.modelCache). The legacy pack-assets location is accepted only " +
                    "for migration and is deleted after splitting.",
            )
        val sha256 = Regex("^[0-9a-f]{64}$")
        if (!sha256.matches(expectedSha)) {
            throw GradleException(
                "Release builds need a valid Gemma SHA-256 from the model cache or -PmodelSha256.",
            )
        }
        partOneAssets.mkdirs()
        partTwoAssets.mkdirs()
        listOf(
            partOneAssets.resolve(partOneName),
            partTwoAssets.resolve(partTwoName),
            partOneAssets.resolve("gemma_fragment_integrity.json"),
            partTwoAssets.resolve("gemma_fragment_integrity.json"),
        ).forEach(File::delete)

        data class FragmentOutput(val file: File, val name: String, val size: Long, val sha256: String)
        val fullDigest = java.security.MessageDigest.getInstance("SHA-256")
        source.inputStream().buffered(1 shl 20).use { input ->
            fun writePart(target: File, name: String, limit: Long): FragmentOutput {
                val fragmentDigest = java.security.MessageDigest.getInstance("SHA-256")
                var written = 0L
                target.outputStream().buffered(1 shl 20).use { output ->
                    val buffer = ByteArray(1 shl 20)
                    while (written < limit) {
                        val read = input.read(buffer, 0, minOf(buffer.size.toLong(), limit - written).toInt())
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        fragmentDigest.update(buffer, 0, read)
                        fullDigest.update(buffer, 0, read)
                        written += read
                    }
                }
                return FragmentOutput(
                    file = target,
                    name = name,
                    size = written,
                    sha256 = fragmentDigest.digest().joinToString("") { "%02x".format(it) },
                )
            }

            val first = writePart(partOneAssets.resolve(partOneName), partOneName, maxBytes)
            require(first.size == maxBytes) { "Gemma part 1 unexpectedly ended before $maxBytes bytes." }
            val second = writePart(partTwoAssets.resolve(partTwoName), partTwoName, maxBytes)
            if (input.read() != -1) {
                first.file.delete()
                second.file.delete()
                throw GradleException(
                    "Gemma remainder exceeds $maxBytes bytes. This two-part prototype cannot package it safely.",
                )
            }
            require(second.size > 0L) { "Gemma part 2 is empty; two on-demand packs are required." }
            val actualFullSha = fullDigest.digest().joinToString("") { "%02x".format(it) }
            if (actualFullSha != expectedSha) {
                first.file.delete()
                second.file.delete()
                throw GradleException("Cached Gemma SHA-256 does not match -PmodelSha256 / derived release pin.")
            }

            fun writeFragmentManifest(
                directory: File,
                metadataName: String,
                fragment: FragmentOutput,
                index: Int,
            ) {
                directory.resolve(metadataName).writeText(
                    """
                    {
                      "schema": 1,
                      "index": $index,
                      "totalParts": 2,
                      "fragmentFile": "${fragment.name}",
                      "fragmentByteSize": ${fragment.size},
                      "fragmentSha256": "${fragment.sha256}",
                      "fullFile": "$fullModelName",
                      "fullSha256": "$actualFullSha"
                    }
                    """.trimIndent() + "\n",
                )
            }
            writeFragmentManifest(partOneAssets, partOneMetadataName, first, 1)
            writeFragmentManifest(partTwoAssets, partTwoMetadataName, second, 2)
            // Kept only as a compatibility integrity record for release
            // validation. It never exposes or packages the full binary.
            partOneAssets.resolve("model_integrity.json").writeText(
                """
                {
                  "modelFile": "$fullModelName",
                  "sha256": "$actualFullSha",
                  "version": "$version",
                  "schema": 1
                }
                """.trimIndent() + "\n",
            )
        }
        if (source.canonicalFile == legacySource.canonicalFile && !legacySource.delete()) {
            throw GradleException(
                "Gemma fragments were generated, but the legacy full model could not be removed " +
                    "from ${legacySource.path}; refusing to risk packaging it.",
            )
        }
        val forbiddenFullFiles = listOf(
            partOneAssets.resolve(fullModelName),
            partTwoAssets.resolve(fullModelName),
        ).filter(File::exists)
        if (forbiddenFullFiles.isNotEmpty()) {
            throw GradleException(
                "Full Gemma model must never be staged in an asset pack: " +
                    forbiddenFullFiles.joinToString { it.path },
            )
        }
    }
}

val validateNoFullGemmaInAssetPacks = tasks.register("validateNoFullGemmaInAssetPacks") {
    dependsOn(provisionGemmaFragments)
    group = "verification"
    description = "Fails if the complete Gemma container is present in either fragment pack."
    inputs.property("fullModelName", gemmaFullModelName)
    inputs.property("partOneAssetsPath", gemmaPartOneAssets.absolutePath)
    inputs.property("partTwoAssetsPath", gemmaPartTwoAssets.absolutePath)
    doLast {
        val props = inputs.properties
        val fullModelName = props["fullModelName"] as String
        val forbidden = listOf(
            File(props["partOneAssetsPath"] as String, fullModelName),
            File(props["partTwoAssetsPath"] as String, fullModelName),
        ).filter(File::exists)
        if (forbidden.isNotEmpty()) {
            throw GradleException(
                "Full Gemma model must never be packaged in an asset pack: " +
                    forbidden.joinToString { it.path },
            )
        }
    }
}

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

allprojects {
    configurations.configureEach {
        resolutionStrategy.force(*mindlayerSecurityDependencyForces.toTypedArray())
    }
}
