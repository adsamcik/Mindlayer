package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest

/**
 * Unit tests for [PaddleOcrModelRegistry] — mirrors
 * [EmbeddingModelRegistryTest] in shape.
 *
 * Uses Robolectric (sdk=33) so we can mock [Context] / [AssetManager]
 * with the MockK pattern the rest of `:app` uses. The test never reads
 * real PaddleOCR weights (those are GB-scale and not committed); it
 * fabricates tiny byte arrays and verifies the discovery + integrity
 * pathway.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class PaddleOcrModelRegistryTest {

    private lateinit var realContext: Context
    private lateinit var filesDir: File

    @Before fun setUp() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        filesDir = File(baseContext.filesDir, "paddleocr-registry-test").apply {
            deleteRecursively()
            mkdirs()
        }
        realContext = object : ContextWrapper(baseContext) {
            override fun getFilesDir(): File = this@PaddleOcrModelRegistryTest.filesDir
        }
    }

    // ── filesDir scan ────────────────────────────────────────────────────

    @Test fun `discoverBundles finds a complete filesDir bundle without integrity`() {
        writeFile("paddleocr-ppocrv5-mobile-det.tflite", byteArrayOf(1, 2, 3))
        writeFile("paddleocr-ppocrv5-mobile-rec.tflite", byteArrayOf(4, 5, 6))
        writeFile("paddleocr-ppocrv5-mobile-cls.tflite", byteArrayOf(7, 8, 9))
        writeFile("paddleocr-ppocrv5-mobile-dict.txt", byteArrayOf(10, 11))

        val bundles = PaddleOcrModelRegistry.discoverBundles(
            realContext,
            requireIntegrity = false,
        )
        assertEquals(1, bundles.size)
        val bundle = bundles.first()
        assertEquals("paddleocr-ppocrv5-mobile", bundle.id)
        assertEquals(3L + 3L + 3L + 2L, bundle.totalSizeBytes)
        assertTrue(bundle.hasOrientationClassifier)
    }

    @Test fun `discoverBundles accepts bundle without orientation classifier`() {
        writeFile("paddleocr-ppocrv5-mobile-det.tflite", byteArrayOf(1, 2, 3))
        writeFile("paddleocr-ppocrv5-mobile-rec.tflite", byteArrayOf(4, 5, 6))
        writeFile("paddleocr-ppocrv5-mobile-dict.txt", byteArrayOf(10, 11))

        val bundles = PaddleOcrModelRegistry.discoverBundles(
            realContext,
            requireIntegrity = false,
        )
        assertEquals(1, bundles.size)
        assertFalse(bundles.first().hasOrientationClassifier)
        assertNull(bundles.first().classifierPath)
    }

    @Test fun `canonical removal intent suppresses OCR discovery before markers are reconciled`() {
        writeFile("paddleocr-ppocrv5-mobile-det.tflite", byteArrayOf(1, 2, 3))
        writeFile("paddleocr-ppocrv5-mobile-rec.tflite", byteArrayOf(4, 5, 6))
        writeFile("paddleocr-ppocrv5-mobile-dict.txt", byteArrayOf(10, 11))
        val family = com.adsamcik.mindlayer.service.modeldelivery.ModelFamily.OCR
        com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryIntentStore(filesDir)
            .recordRemoval(family)
        val pending = com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryFileLock
            .pendingRemovalMarker(filesDir, family)
        val tombstone = com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryFileLock
            .removalTombstone(filesDir, family)
        assertTrue(pending.delete())
        assertTrue(tombstone.delete())

        assertTrue(
            PaddleOcrModelRegistry.discoverBundles(realContext, requireIntegrity = false).isEmpty(),
        )
        assertFalse(pending.exists())
        assertFalse(tombstone.exists())
    }

    @Test fun `discoverBundles rejects bundle missing rec head`() {
        writeFile("paddleocr-ppocrv5-mobile-det.tflite", byteArrayOf(1, 2, 3))
        writeFile("paddleocr-ppocrv5-mobile-dict.txt", byteArrayOf(10, 11))
        val bundles = PaddleOcrModelRegistry.discoverBundles(
            realContext,
            requireIntegrity = false,
        )
        assertEquals(0, bundles.size)
    }

    @Test fun `discoverBundles rejects bundle missing dict`() {
        writeFile("paddleocr-ppocrv5-mobile-det.tflite", byteArrayOf(1, 2, 3))
        writeFile("paddleocr-ppocrv5-mobile-rec.tflite", byteArrayOf(4, 5, 6))
        val bundles = PaddleOcrModelRegistry.discoverBundles(
            realContext,
            requireIntegrity = false,
        )
        assertEquals(0, bundles.size)
    }

    @Test fun `discoverBundles rejects unexpected filename`() {
        // Substitution attack: a file with the right extension but wrong
        // name should be rejected by the strict regex.
        writeFile("paddleocr-evil-det.tflite", byteArrayOf(1, 2, 3))
        writeFile("paddleocr-ppocrv5-mobile-rec.tflite", byteArrayOf(4, 5, 6))
        writeFile("paddleocr-ppocrv5-mobile-dict.txt", byteArrayOf(10, 11))
        val bundles = PaddleOcrModelRegistry.discoverBundles(
            realContext,
            requireIntegrity = false,
        )
        assertEquals(0, bundles.size)
    }

    // ── Integrity verification ───────────────────────────────────────────

    @Test fun `discoverBundles with integrity required rejects when manifest absent and using mocked assets`() {
        // Use a mocked context where assets.open(INTEGRITY_MANIFEST) throws
        // (no manifest in classpath assets). filesDir-only path. With
        // requireIntegrity=true and no manifest, EVERY file fails
        // verification (no expected entry).
        writeFile("paddleocr-ppocrv5-mobile-det.tflite", byteArrayOf(1, 2, 3))
        writeFile("paddleocr-ppocrv5-mobile-rec.tflite", byteArrayOf(4, 5, 6))
        writeFile("paddleocr-ppocrv5-mobile-dict.txt", byteArrayOf(10, 11))

        val ctx = mockContext(
            filesDir = filesDir,
            manifestJson = null, // no manifest -> all integrity checks fail
            assetNames = emptyList(),
            debuggable = false,
        )
        val bundles = PaddleOcrModelRegistry.discoverBundles(
            ctx,
            requireIntegrity = true,
        )
        assertEquals(0, bundles.size)
    }

    @Test fun `discoverBundles with integrity accepts when sha matches`() {
        val detBytes = byteArrayOf(1, 2, 3)
        val recBytes = byteArrayOf(4, 5, 6)
        val dictBytes = byteArrayOf(10, 11)
        writeFile("paddleocr-ppocrv5-mobile-det.tflite", detBytes)
        writeFile("paddleocr-ppocrv5-mobile-rec.tflite", recBytes)
        writeFile("paddleocr-ppocrv5-mobile-dict.txt", dictBytes)

        val manifest = """
            {
              "schema": 1,
              "engine": "paddleocr-ppocrv5-mobile",
              "models": [
                {"filename": "paddleocr-ppocrv5-mobile-det.tflite", "sha256": "${sha256(detBytes)}", "role": "detection"},
                {"filename": "paddleocr-ppocrv5-mobile-rec.tflite", "sha256": "${sha256(recBytes)}", "role": "recognition"},
                {"filename": "paddleocr-ppocrv5-mobile-dict.txt",   "sha256": "${sha256(dictBytes)}", "role": "dictionary"}
              ]
            }
        """.trimIndent()
        val ctx = mockContext(
            filesDir = filesDir,
            manifestJson = manifest,
            assetNames = emptyList(),
            debuggable = false,
        )
        val bundles = PaddleOcrModelRegistry.discoverBundles(ctx, requireIntegrity = true)
        assertEquals(1, bundles.size)
        assertEquals(sha256(detBytes), bundles.first().detSha256)
    }

    @Test fun `discoverBundles with integrity rejects when sha mismatches`() {
        val detBytes = byteArrayOf(1, 2, 3)
        writeFile("paddleocr-ppocrv5-mobile-det.tflite", detBytes)
        writeFile("paddleocr-ppocrv5-mobile-rec.tflite", byteArrayOf(4, 5, 6))
        writeFile("paddleocr-ppocrv5-mobile-dict.txt", byteArrayOf(10, 11))

        // Manifest has the WRONG sha for det.
        val manifest = """
            {
              "schema": 1,
              "models": [
                {"filename": "paddleocr-ppocrv5-mobile-det.tflite", "sha256": "${"a".repeat(64)}", "role": "detection"},
                {"filename": "paddleocr-ppocrv5-mobile-rec.tflite", "sha256": "${"b".repeat(64)}", "role": "recognition"},
                {"filename": "paddleocr-ppocrv5-mobile-dict.txt",   "sha256": "${"c".repeat(64)}", "role": "dictionary"}
              ]
            }
        """.trimIndent()
        val ctx = mockContext(filesDir = filesDir, manifestJson = manifest, assetNames = emptyList(), debuggable = false)
        val bundles = PaddleOcrModelRegistry.discoverBundles(ctx, requireIntegrity = true)
        assertEquals(0, bundles.size)
    }

    @Test fun `manifest with all-zero sha256 placeholder is treated as no integrity entry`() {
        // The committed paddleocr_model_integrity.json has all zeros — the
        // registry must treat this as "no expected sha" rather than "trust
        // the all-zero hash", to prevent a forgotten-to-replace manifest
        // from validating any random bytes.
        val detBytes = byteArrayOf(1, 2, 3)
        writeFile("paddleocr-ppocrv5-mobile-det.tflite", detBytes)
        writeFile("paddleocr-ppocrv5-mobile-rec.tflite", byteArrayOf(4, 5, 6))
        writeFile("paddleocr-ppocrv5-mobile-dict.txt", byteArrayOf(10, 11))

        val manifest = """
            {
              "schema": 1,
              "models": [
                {"filename": "paddleocr-ppocrv5-mobile-det.tflite", "sha256": "${"0".repeat(64)}", "role": "detection"},
                {"filename": "paddleocr-ppocrv5-mobile-rec.tflite", "sha256": "${"0".repeat(64)}", "role": "recognition"},
                {"filename": "paddleocr-ppocrv5-mobile-dict.txt",   "sha256": "${"0".repeat(64)}", "role": "dictionary"}
              ]
            }
        """.trimIndent()
        val ctx = mockContext(filesDir = filesDir, manifestJson = manifest, assetNames = emptyList(), debuggable = false)
        // With requireIntegrity=true and no real manifest entries, the
        // bundle must be rejected — never silently accepted.
        val bundles = PaddleOcrModelRegistry.discoverBundles(ctx, requireIntegrity = true)
        assertEquals(0, bundles.size)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    @Test fun `getDefaultBundle returns first`() {
        writeFile("paddleocr-ppocrv5-mobile-det.tflite", byteArrayOf(1, 2, 3))
        writeFile("paddleocr-ppocrv5-mobile-rec.tflite", byteArrayOf(4, 5, 6))
        writeFile("paddleocr-ppocrv5-mobile-dict.txt", byteArrayOf(10, 11))
        val bundles = PaddleOcrModelRegistry.discoverBundles(realContext, requireIntegrity = false)
        assertNotNull(PaddleOcrModelRegistry.getDefaultBundle(bundles))
        assertNull(PaddleOcrModelRegistry.getDefaultBundle(emptyList()))
    }

    @Test fun `findBundleById returns matching bundle`() {
        writeFile("paddleocr-ppocrv5-mobile-det.tflite", byteArrayOf(1, 2, 3))
        writeFile("paddleocr-ppocrv5-mobile-rec.tflite", byteArrayOf(4, 5, 6))
        writeFile("paddleocr-ppocrv5-mobile-dict.txt", byteArrayOf(10, 11))
        val bundles = PaddleOcrModelRegistry.discoverBundles(realContext, requireIntegrity = false)
        assertNotNull(PaddleOcrModelRegistry.findBundleById(bundles, "paddleocr-ppocrv5-mobile"))
        assertNull(PaddleOcrModelRegistry.findBundleById(bundles, "nope"))
    }

    private fun writeFile(name: String, bytes: ByteArray) {
        File(filesDir, name).writeBytes(bytes)
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it) }
    }

    private fun mockContext(
        filesDir: File,
        manifestJson: String?,
        assetNames: List<String>,
        debuggable: Boolean,
    ): Context {
        val ctx = mockk<Context>(relaxed = true)
        val assets = mockk<AssetManager>(relaxed = true)
        every { ctx.filesDir } returns filesDir
        every { ctx.getExternalFilesDir(any()) } returns null
        every { ctx.cacheDir } returns File(filesDir.parentFile, "mock-cache-${System.nanoTime()}").apply { mkdirs() }
        every { ctx.assets } returns assets
        every { ctx.applicationInfo } returns ApplicationInfo().apply {
            flags = if (debuggable) ApplicationInfo.FLAG_DEBUGGABLE else 0
        }
        every { assets.list("") } returns assetNames.toTypedArray()
        if (manifestJson != null) {
            every { assets.open(PaddleOcrModelRegistry.INTEGRITY_MANIFEST) } answers {
                ByteArrayInputStream(manifestJson.toByteArray())
            }
        } else {
            every { assets.open(PaddleOcrModelRegistry.INTEGRITY_MANIFEST) } throws
                java.io.FileNotFoundException("no manifest in test")
        }
        return ctx
    }
}
