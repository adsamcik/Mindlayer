package com.adsamcik.mindlayer.service.modeldelivery

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OnDemandAssetPackStructureTest {

    @Test
    fun `all model delivery uses standard on-demand PAD without app network permissions`() {
        val root = repoRoot()
        val appBuild = root.resolve("app/build.gradle.kts").readText()
        val settings = root.resolve("settings.gradle.kts").readText()
        val manifest = root.resolve("app/src/main/AndroidManifest.xml").readText()
        val versions = root.resolve("gradle/libs.versions.toml").readText()

        assertTrue(appBuild.contains("implementation(libs.play.asset.delivery)"))
        assertTrue(versions.contains("com.google.android.play:asset-delivery"))
        assertTrue(versions.contains("playAssetDelivery = \"2.3.0\""))
        assertTrue(settings.contains("include(\":gemma_model_part_2\")"))
        assertTrue(appBuild.contains("bundle(\"mindlayer.bundleGemma\", \":gemma_model\")"))
        assertTrue(appBuild.contains("\":gemma_model_part_2\""))
        assertTrue(
            appBuild.contains(
                """exclude(group = "com.google.android.play", module = "ai-delivery")""",
            ),
        )
        assertTrue(appBuild.contains("validateNoAiDeliveryDependency"))
        assertTrue(appBuild.contains("validateReleaseBundleAssetPackNames"))

        // The standard on-demand PAD wiring now lives in the shared
        // `mindlayer.assetpack` convention plugin (build-logic) instead of being
        // copy-pasted into each pack module. Assert the invariant there, then
        // assert each module applies it. `validateReleaseBundleAssetPackNames`
        // (checked above) additionally hard-verifies the four stable pack names
        // in the final AAB.
        val assetPackConvention = root.resolve(
            "build-logic/src/main/kotlin/mindlayer.assetpack.gradle.kts",
        ).readText()
        assertTrue(
            "asset-pack convention must use standard PAD",
            assetPackConvention.contains("com.android.asset-pack"),
        )
        assertTrue(
            "asset-pack convention must be on demand",
            assetPackConvention.contains("deliveryType = \"on-demand\""),
        )
        assertTrue(
            "asset-pack convention must derive the stable pack identity from the module name",
            assetPackConvention.contains("packName = project.name"),
        )
        assertFalse(
            "asset-pack convention must not use the beta AI pack plugin",
            assetPackConvention.contains("com.android.ai-pack"),
        )

        listOf(
            "gemma_model",
            "gemma_model_part_2",
            "gemma_embed_model",
            "paddleocr_model",
        ).forEach { module ->
            val build = root.resolve("$module/build.gradle.kts").readText()
            assertTrue(
                "$module must apply the standard on-demand asset-pack convention",
                build.contains("id(\"mindlayer.assetpack\")"),
            )
            assertTrue(
                "$module must be included with its stable pack identity",
                settings.contains("include(\":$module\")"),
            )
            assertFalse("$module must not use the beta AI pack plugin", build.contains("com.android.ai-pack"))
        }
        assertFalse(manifest.contains("android.permission.INTERNET"))
        assertTrue(manifest.contains("android.permission.ACCESS_NETWORK_STATE"))
        assertTrue(manifest.contains("tools:node=\"remove\""))
    }

    private fun repoRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath()
        while (!Files.exists(current.resolve("settings.gradle.kts"))) {
            current = current.parent ?: error("Cannot find repository root")
        }
        return current
    }
}
