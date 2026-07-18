package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.nio.file.Files
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EmbeddingModelRegistryTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        clean(context.filesDir)
        clean(context.cacheDir)
    }

    @Test
    fun `discoverModels returns empty before Phase D asset pack lands`() {
        assertTrue(EmbeddingModelRegistry.discoverModels(context, requireIntegrity = false).isEmpty())
    }

    @Test
    fun `discoverModels requires model tokenizer pair`() {
        File(context.filesDir, "embedding-missing-tokenizer.tflite").writeText("model")

        assertTrue(EmbeddingModelRegistry.discoverModels(context, requireIntegrity = false).isEmpty())
    }

    @Test
    fun `canonical removal intent suppresses embedding discovery before markers are reconciled`() {
        File(context.filesDir, "embedding-gemma-300m-v1.tflite").writeText("model")
        File(context.filesDir, "sentencepiece.model").writeText("tok")
        val family = com.adsamcik.mindlayer.service.modeldelivery.ModelFamily.EMBEDDINGS
        com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryIntentStore(context.filesDir)
            .recordRemoval(family)
        val pending = com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryFileLock
            .pendingRemovalMarker(context.filesDir, family)
        val tombstone = com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryFileLock
            .removalTombstone(context.filesDir, family)
        assertTrue(pending.delete())
        assertTrue(tombstone.delete())

        assertTrue(EmbeddingModelRegistry.discoverModels(context, requireIntegrity = false).isEmpty())
        assertFalse(pending.exists())
        assertFalse(tombstone.exists())
    }

    @Test
    fun `discoverModels accepts sentencepiece tokenizer in same directory`() {
        val model = File(context.filesDir, "embedding-gemma-300m-v1.tflite").apply { writeText("model") }
        val tokenizer = File(context.filesDir, "sentencepiece.model").apply { writeText("tok") }

        val models = EmbeddingModelRegistry.discoverModels(context, requireIntegrity = false)

        assertEquals(1, models.size)
        assertEquals("embedding-gemma-300m-v1", models.single().id)
        assertEquals(model.absolutePath, models.single().modelPath)
        assertEquals(tokenizer.absolutePath, models.single().tokenizerPath)
        assertEquals(listOf(768, 512, 256, 128), models.single().supportedDims)
    }

    @Test
    fun `discoverModels accepts model-specific tokenizer sidecar`() {
        File(context.filesDir, "embedding-specific.tflite").writeText("model")
        val tokenizer = File(context.filesDir, "embedding-specific.spm.model").apply { writeText("tok") }

        val models = EmbeddingModelRegistry.discoverModels(context, requireIntegrity = false)

        assertEquals(1, models.size)
        assertEquals(tokenizer.absolutePath, models.single().tokenizerPath)
    }

    @Test
    fun `discoverModels ranks filesDir above cacheDir even when cache file is larger`() {
        File(context.filesDir, "embedding-good.tflite").writeBytes(ByteArray(10))
        File(context.filesDir, "sentencepiece.model").writeText("tok")
        File(context.cacheDir, "embedding-evil.tflite").writeBytes(ByteArray(10_000))
        File(context.cacheDir, "sentencepiece.model").writeText("tok")

        val models = EmbeddingModelRegistry.discoverModels(context, requireIntegrity = false)

        assertEquals("embedding-good", models.first().id)
    }

    @Test
    fun `findModelById returns matching model and default is first`() {
        val first = EmbeddingModelInfo("a", "A", "/a", "/t", 1, 768, listOf(768), 2048, null)
        val second = first.copy(id = "b")

        assertEquals(first, EmbeddingModelRegistry.getDefaultModel(listOf(first, second)))
        assertEquals(second, EmbeddingModelRegistry.findModelById(listOf(first, second), "b"))
        assertNull(EmbeddingModelRegistry.findModelById(listOf(first), "missing"))
    }

    @Test
    fun `discoverModels rejects unsafe embedding filenames`() {
        File(context.filesDir, "embedding bad.tflite").writeText("model")
        File(context.filesDir, "embedding-good.tflite").writeText("model")
        File(context.filesDir, "sentencepiece.model").writeText("tok")

        val models = EmbeddingModelRegistry.discoverModels(context, requireIntegrity = false)

        assertEquals(listOf("embedding-good"), models.map { it.id })
    }

    @Test
    fun `discoverModels rejects path traversal asset names`() {
        val assetManager = mockk<AssetManager> {
            every { list("") } returns arrayOf("../../etc/passwd.tflite", "sentencepiece.model")
            every { open("embedding_model_integrity.json") } throws java.io.IOException("no manifest")
            every { open("model_integrity.json") } throws java.io.IOException("no manifest")
        }
        val mocked = contextWithDirs(context.filesDir, null, context.cacheDir, assetManager)

        val models = EmbeddingModelRegistry.discoverModels(mocked, requireIntegrity = false)

        assertTrue(models.isEmpty())
        assertFalse(File(context.filesDir, "passwd.tflite").exists())
    }

    @Test
    fun `discoverModels rejects sidecar-only integrity under requireIntegrity (S-5)`() {
        // Security-review S-5: a sidecar `.sha256` file next to the model is
        // attacker-controllable (writable model dir / post-discovery swap).
        // Under requireIntegrity=true (release builds) the expected digest
        // must come ONLY from the packaged, build-pinned manifest — a
        // self-declared sidecar must NOT be trusted as the source of truth.
        val bytes = "trusted".toByteArray()
        File(context.filesDir, "embedding-trusted.tflite").writeBytes(bytes)
        File(context.filesDir, "sentencepiece.model").writeBytes("tok".toByteArray())
        File(context.filesDir, "embedding-trusted.tflite.sha256").writeText(sha256(bytes))
        File(context.filesDir, "sentencepiece.model.sha256").writeText(sha256("tok".toByteArray()))

        val models = EmbeddingModelRegistry.discoverModels(context, requireIntegrity = true)

        assertTrue(
            "sidecar-only integrity must be rejected when requireIntegrity=true",
            models.isEmpty(),
        )
    }

    @Test
    fun `discoverModels still accepts matching sidecar integrity in debug builds`() {
        // Debug builds (requireIntegrity=false) keep the sidecar fallback as
        // a convenience for local model iteration.
        val bytes = "trusted".toByteArray()
        File(context.filesDir, "embedding-trusted.tflite").writeBytes(bytes)
        File(context.filesDir, "sentencepiece.model").writeBytes("tok".toByteArray())
        File(context.filesDir, "embedding-trusted.tflite.sha256").writeText(sha256(bytes))
        File(context.filesDir, "sentencepiece.model.sha256").writeText(sha256("tok".toByteArray()))

        val models = EmbeddingModelRegistry.discoverModels(context, requireIntegrity = false)

        assertEquals(listOf("embedding-trusted"), models.map { it.id })
    }

    @Test
    fun `discoverModels prioritizes materialized on-demand pair before filesDir`() {
        val modelBytes = "asset-model".toByteArray()
        val assetManager = mockk<AssetManager> {
            every { list("") } returns arrayOf("embedding-asset.tflite", "sentencepiece.model")
            every { open("embedding_model_integrity.json") } returns ByteArrayInputStream(
                """{"models":[{"filename":"embedding-asset.tflite","sha256":"${sha256(modelBytes)}"},{"filename":"sentencepiece.model","sha256":"${sha256("tok".toByteArray())}"}]}""".toByteArray(),
            )
            every { open("embedding-asset.tflite") } returns ByteArrayInputStream(modelBytes)
            every { open("sentencepiece.model") } returns ByteArrayInputStream("tok".toByteArray())
        }
        val deliveryDir = File(context.filesDir, "model_delivery/embeddings").apply { mkdirs() }
        File(deliveryDir, "embedding-asset.tflite").writeBytes(modelBytes)
        File(deliveryDir, "sentencepiece.model").writeBytes("tok".toByteArray())
        val mocked = contextWithDirs(context.filesDir, null, context.cacheDir, assetManager)

        val models = EmbeddingModelRegistry.discoverModels(mocked, requireIntegrity = true)

        assertEquals(listOf("embedding-asset"), models.map { it.id })
        assertTrue(File(deliveryDir, "embedding-asset.tflite").isFile)
        assertTrue(File(deliveryDir, "sentencepiece.model").isFile)
    }


    @Test
    fun `discoverModels rejects AI pack asset when tokenizer is missing`() {
        val modelBytes = "asset-model".toByteArray()
        val assetManager = mockk<AssetManager> {
            every { list("") } returns arrayOf("embedding-asset.tflite")
            every { open("embedding_model_integrity.json") } returns ByteArrayInputStream(
                """{"models":[{"filename":"embedding-asset.tflite","sha256":"${sha256(modelBytes)}"}]}""".toByteArray(),
            )
        }
        val mocked = contextWithDirs(context.filesDir, null, context.cacheDir, assetManager)

        val models = EmbeddingModelRegistry.discoverModels(mocked, requireIntegrity = true)

        assertTrue(models.isEmpty())
        assertFalse(File(context.filesDir, "embedding-asset.tflite").exists())
    }

    @Test
    fun `discoverModels rejects AI pack asset when model sha mismatches`() {
        val assetManager = mockk<AssetManager> {
            every { list("") } returns arrayOf("embedding-asset.tflite", "sentencepiece.model")
            every { open("embedding_model_integrity.json") } returns ByteArrayInputStream(
                """{"models":[{"filename":"embedding-asset.tflite","sha256":"${"0".repeat(64)}"},{"filename":"sentencepiece.model","sha256":"${sha256("tok".toByteArray())}"}]}""".toByteArray(),
            )
            every { open("embedding-asset.tflite") } returns ByteArrayInputStream("tampered".toByteArray())
            every { open("sentencepiece.model") } returns ByteArrayInputStream("tok".toByteArray())
        }
        val mocked = contextWithDirs(context.filesDir, null, context.cacheDir, assetManager)

        val models = EmbeddingModelRegistry.discoverModels(mocked, requireIntegrity = true)

        assertTrue(models.isEmpty())
        assertFalse(File(context.filesDir, "embedding-asset.tflite").exists())
    }

    @Test
    fun `discoverModels rejects AI pack asset when tokenizer sha mismatches`() {
        val modelBytes = "asset-model".toByteArray()
        val assetManager = mockk<AssetManager> {
            every { list("") } returns arrayOf("embedding-asset.tflite", "sentencepiece.model")
            every { open("embedding_model_integrity.json") } returns ByteArrayInputStream(
                """{"models":[{"filename":"embedding-asset.tflite","sha256":"${sha256(modelBytes)}"},{"filename":"sentencepiece.model","sha256":"${"0".repeat(64)}"}]}""".toByteArray(),
            )
            every { open("embedding-asset.tflite") } returns ByteArrayInputStream(modelBytes)
            every { open("sentencepiece.model") } returns ByteArrayInputStream("tampered".toByteArray())
        }
        val mocked = contextWithDirs(context.filesDir, null, context.cacheDir, assetManager)

        val models = EmbeddingModelRegistry.discoverModels(mocked, requireIntegrity = true)

        assertTrue(models.isEmpty())
        assertFalse(File(context.filesDir, "embedding-asset.tflite").exists())
        assertFalse(File(context.filesDir, "sentencepiece.model").exists())
    }

    @Test
    fun `discoverModels skips symlinks in embedding model scan dirs`() {
        Assume.assumeFalse("Symlink test skipped on Windows", System.getProperty("os.name").lowercase().contains("win"))
        val dir = Files.createTempDirectory("mindlayer-embedding-symlink").toFile()
        val outside = Files.createTempFile("outside-embedding", ".tflite").toFile()
        try {
            File(dir, "embedding-real.tflite").writeText("model")
            File(dir, "sentencepiece.model").writeText("tok")
            try {
                Files.createSymbolicLink(dir.toPath().resolve("embedding-link.tflite"), outside.toPath())
            } catch (e: Exception) {
                Assume.assumeTrue("Symlink creation not supported: ${e.message}", false)
            }
            val mocked = contextWithDirs(dir, null, File(dir, "cache"), emptyAssets())

            val models = EmbeddingModelRegistry.discoverModels(mocked, requireIntegrity = false)

            assertEquals(listOf("embedding-real"), models.map { it.id })
        } finally {
            dir.deleteRecursively()
            outside.delete()
        }
    }

    private fun contextWithDirs(
        filesDir: File,
        externalDir: File?,
        cacheDir: File,
        assets: AssetManager,
    ): Context = mockk {
        every { this@mockk.filesDir } returns filesDir
        every { getExternalFilesDir(null) } returns externalDir
        every { this@mockk.cacheDir } returns cacheDir
        every { applicationInfo } returns ApplicationInfo().apply {
            flags = ApplicationInfo.FLAG_DEBUGGABLE
        }
        every { this@mockk.assets } returns assets
    }

    private fun emptyAssets(): AssetManager = mockk {
        every { list("") } returns emptyArray()
        every { open("embedding_model_integrity.json") } throws java.io.IOException("no manifest")
        every { open("model_integrity.json") } throws java.io.IOException("no manifest")
    }

    private fun clean(dir: File) {
        dir.listFiles()?.forEach { it.deleteRecursively() }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
}
