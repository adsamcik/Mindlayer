package com.adsamcik.mindlayer.service.engine

import android.content.Context
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
import org.junit.Assume
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
class ModelRegistryTest {

    // ---- deriveDisplayName ------------------------------------------------

    @Test
    fun `deriveDisplayName handles plain name without extension`() {
        assertEquals("Gemma", ModelRegistry.deriveDisplayName("gemma"))
    }

    @Test
    fun `deriveDisplayName strips extension and capitalises hyphenated tokens`() {
        assertEquals(
            "Gemma 4 E2B Instruct",
            ModelRegistry.deriveDisplayName("gemma-4-E2B-it.litertlm"),
        )
    }

    @Test
    fun `deriveDisplayName expands it token regardless of case`() {
        assertEquals("Foo Instruct", ModelRegistry.deriveDisplayName("foo-it.litertlm"))
        assertEquals("Foo Instruct", ModelRegistry.deriveDisplayName("foo-IT.litertlm"))
        assertEquals("Foo Instruct", ModelRegistry.deriveDisplayName("foo-It.litertlm"))
    }

    @Test
    fun `deriveDisplayName preserves single-letter tokens`() {
        assertEquals("A B C", ModelRegistry.deriveDisplayName("a-b-c.litertlm"))
    }

    @Test
    fun `deriveDisplayName works on input without extension`() {
        assertEquals("Gemma 4 E2B Instruct", ModelRegistry.deriveDisplayName("gemma-4-E2B-it"))
    }

    // ---- getDefaultModel --------------------------------------------------

    @Test
    fun `getDefaultModel returns null on empty list`() {
        assertNull(ModelRegistry.getDefaultModel(emptyList()))
    }

    @Test
    fun `getDefaultModel marks single model as default`() {
        val only = ModelInfo(id = "m", displayName = "M", path = "/m", sizeBytes = 100L)
        val result = ModelRegistry.getDefaultModel(listOf(only))
        assertNotNull(result)
        assertTrue(result!!.isDefault)
        assertEquals("m", result.id)
    }

    @Test
    fun `getDefaultModel returns largest as default and does not mutate others`() {
        val big = ModelInfo(id = "big", displayName = "Big", path = "/big", sizeBytes = 1000L)
        val small = ModelInfo(id = "small", displayName = "Small", path = "/small", sizeBytes = 10L)
        val models = listOf(big, small) // already sorted desc

        val result = ModelRegistry.getDefaultModel(models)

        assertNotNull(result)
        assertEquals("big", result!!.id)
        assertTrue(result.isDefault)
        // Original list unchanged
        assertFalse(models[0].isDefault)
        assertFalse(models[1].isDefault)
    }

    // ---- findModelById ----------------------------------------------------

    @Test
    fun `findModelById returns matching model`() {
        val a = ModelInfo("a", "A", "/a", 1L)
        val b = ModelInfo("b", "B", "/b", 2L)
        assertEquals(a, ModelRegistry.findModelById(listOf(a, b), "a"))
    }

    @Test
    fun `findModelById returns null when missing`() {
        val a = ModelInfo("a", "A", "/a", 1L)
        assertNull(ModelRegistry.findModelById(listOf(a), "missing"))
    }

    // ---- discoverModels (Robolectric) -------------------------------------

    @Test
    fun `discoverModels returns empty list when nothing installed`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        // Ensure dirs are clean
        context.filesDir.listFiles()?.forEach { it.delete() }
        context.cacheDir.listFiles()?.forEach { it.delete() }

        val models = ModelRegistry.discoverModels(context, requireIntegrity = false)
        assertTrue("Expected empty, got $models", models.isEmpty())
    }

    @Test
    fun `discoverModels dedups by filename and sorts by size descending`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.filesDir.listFiles()?.forEach { it.delete() }
        context.cacheDir.listFiles()?.forEach { it.delete() }

        // filesDir: small "shared.litertlm" (wins dedup), large "big.litertlm"
        val sharedInFiles = File(context.filesDir, "shared.litertlm").apply {
            writeBytes(ByteArray(100))
        }
        val bigInFiles = File(context.filesDir, "big.litertlm").apply {
            writeBytes(ByteArray(5_000))
        }
        // Non-model file should be ignored
        File(context.filesDir, "notes.txt").writeText("ignore me")

        // cacheDir: duplicate "shared.litertlm" with different size (loses dedup),
        // and a unique "tiny.litertlm".
        File(context.cacheDir, "shared.litertlm").writeBytes(ByteArray(9_999))
        File(context.cacheDir, "tiny.litertlm").writeBytes(ByteArray(10))

        val models = ModelRegistry.discoverModels(context, requireIntegrity = false)

        // Three distinct filenames discovered
        assertEquals(3, models.size)

        // Sorted by size descending
        assertEquals(listOf(5_000L, 100L, 10L), models.map { it.sizeBytes })

        // The "shared.litertlm" entry must come from filesDir, not cacheDir
        val shared = models.first { it.id == "shared" }
        assertEquals(sharedInFiles.absolutePath, shared.path)
        assertEquals(100L, shared.sizeBytes)

        // Big one is correctly mapped
        val big = models.first { it.id == "big" }
        assertEquals(bigInFiles.absolutePath, big.path)

        // Display names derived
        assertEquals("Shared", shared.displayName)
        assertEquals("Tiny", models.first { it.id == "tiny" }.displayName)

        // No non-model files crept in
        assertTrue(models.none { it.path.endsWith("notes.txt") })
    }

    @Test
    fun `discoverModels requires valid integrity metadata when requested`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.filesDir.listFiles()?.forEach { it.delete() }
        context.cacheDir.listFiles()?.forEach { it.delete() }

        val goodBytes = "trusted model".toByteArray()
        val good = File(context.filesDir, "good.litertlm").apply {
            writeBytes(goodBytes)
        }
        File(context.filesDir, "good.litertlm.sha256").writeText(sha256(goodBytes))

        File(context.filesDir, "tampered.litertlm").writeText("tampered")
        File(context.filesDir, "tampered.litertlm.sha256").writeText("0".repeat(64))
        File(context.filesDir, "missing-hash.litertlm").writeText("missing")

        val models = ModelRegistry.discoverModels(context, requireIntegrity = true)

        assertEquals(1, models.size)
        assertEquals("good", models.single().id)
        assertEquals(good.absolutePath, models.single().path)
        assertEquals(sha256(goodBytes), models.single().sha256)
    }

    @Test
    fun `discoverModels accepts sidecar hash with uppercase digest and filename suffix`() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        context.filesDir.listFiles()?.forEach { it.delete() }
        context.cacheDir.listFiles()?.forEach { it.delete() }

        val bytes = "sidecar model".toByteArray()
        val model = File(context.filesDir, "sidecar-model.litertlm").apply {
            writeBytes(bytes)
        }
        File(context.filesDir, "sidecar-model.sha256").writeText(
            "${sha256(bytes).uppercase()}  sidecar-model.litertlm\n",
        )

        val models = ModelRegistry.discoverModels(context, requireIntegrity = true)

        assertEquals(listOf("sidecar-model"), models.map { it.id })
        assertEquals(model.absolutePath, models.single().path)
        assertEquals(sha256(bytes), models.single().sha256)
    }

    @Test
    fun `discoverModels accepts asset manifest metadata without sidecar`() {
        val baseContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        baseContext.filesDir.listFiles()?.forEach { it.delete() }
        baseContext.cacheDir.listFiles()?.forEach { it.delete() }
        val context = contextWithManifest(baseContext)

        val model = File(baseContext.filesDir, "manifest-empty.litertlm").apply {
            writeBytes(ByteArray(0))
        }

        val models = ModelRegistry.discoverModels(context, requireIntegrity = true)

        assertEquals(listOf("manifest-empty"), models.map { it.id })
        assertEquals(model.absolutePath, models.single().path)
        assertEquals(sha256(ByteArray(0)), models.single().sha256)
    }

    @Test
    fun `discoverModels rejects manifest entries with size mismatch or invalid sha`() {
        val baseContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        baseContext.filesDir.listFiles()?.forEach { it.delete() }
        baseContext.cacheDir.listFiles()?.forEach { it.delete() }
        val context = contextWithManifest(baseContext)

        File(baseContext.filesDir, "manifest-size-mismatch.litertlm").writeBytes(ByteArray(0))
        File(baseContext.filesDir, "manifest-invalid-sha.litertlm").writeBytes(ByteArray(0))

        val models = ModelRegistry.discoverModels(context, requireIntegrity = true)

        assertTrue(models.isEmpty())
    }

    @Test
    fun `discoverModels falls back to sidecar when asset manifest is malformed`() {
        val baseContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        baseContext.filesDir.listFiles()?.forEach { it.delete() }
        baseContext.cacheDir.listFiles()?.forEach { it.delete() }
        val context = contextWithManifest(baseContext, manifest = "{not-json")

        val bytes = "sidecar survives bad manifest".toByteArray()
        val model = File(baseContext.filesDir, "sidecar-after-bad-manifest.litertlm").apply {
            writeBytes(bytes)
        }
        File(baseContext.filesDir, "sidecar-after-bad-manifest.litertlm.sha256")
            .writeText(sha256(bytes))

        val models = ModelRegistry.discoverModels(context, requireIntegrity = true)

        assertEquals(listOf("sidecar-after-bad-manifest"), models.map { it.id })
        assertEquals(model.absolutePath, models.single().path)
    }

    @Test
    fun `discoverModels default fail-closes on non-debug context without integrity metadata`() {
        val baseContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        baseContext.filesDir.listFiles()?.forEach { it.delete() }
        baseContext.cacheDir.listFiles()?.forEach { it.delete() }
        val context = contextWithManifest(baseContext, manifest = """{"models":[]}""")

        File(baseContext.filesDir, "untrusted.litertlm").writeText("no hash")

        assertTrue(ModelRegistry.discoverModels(context).isEmpty())
    }

    @Test
    fun `discoverModels deletes invalid extracted ai pack model and rediscovers on next pass`() {
        val baseContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        baseContext.filesDir.listFiles()?.forEach { it.delete() }
        baseContext.cacheDir.listFiles()?.forEach { it.delete() }
        val context = contextWithManifest(
            baseContext = baseContext,
            assetNames = arrayOf("manifest-empty.litertlm"),
            assetContents = mapOf("manifest-empty.litertlm" to ByteArray(0)),
        )
        val extracted = File(baseContext.filesDir, "manifest-empty.litertlm").apply {
            writeText("corrupted existing extraction")
        }

        assertTrue(ModelRegistry.discoverModels(context, requireIntegrity = true).isEmpty())
        assertFalse(extracted.exists())

        val rediscovered = ModelRegistry.discoverModels(context, requireIntegrity = true)

        assertEquals(listOf("manifest-empty"), rediscovered.map { it.id })
        assertEquals(0L, extracted.length())
    }

    @Test
    fun `discoverModels keeps valid local model when ai pack asset open fails`() {
        val baseContext = ApplicationProvider.getApplicationContext<android.content.Context>()
        baseContext.filesDir.listFiles()?.forEach { it.delete() }
        baseContext.cacheDir.listFiles()?.forEach { it.delete() }
        val bytes = "trusted local model".toByteArray()
        val local = File(baseContext.filesDir, "trusted-local.litertlm").apply {
            writeBytes(bytes)
        }
        File(baseContext.filesDir, "trusted-local.litertlm.sha256").writeText(sha256(bytes))
        val context = contextWithManifest(
            baseContext = baseContext,
            assetNames = arrayOf("manifest-empty.litertlm"),
            failAssetOpenFor = "manifest-empty.litertlm",
        )

        val models = ModelRegistry.discoverModels(context, requireIntegrity = true)

        assertEquals(listOf("trusted-local"), models.map { it.id })
        assertEquals(local.absolutePath, models.single().path)
    }

    @Test
    fun `discoverModels skips symlinks in model scan dirs`() {
        Assume.assumeFalse(
            "Symlink test skipped on Windows",
            System.getProperty("os.name").lowercase().contains("win"),
        )

        val tempDir = Files.createTempDirectory("mindlayer-symlink-test").toFile()
        val outsideFile = Files.createTempFile("outside-target", ".litertlm").toFile()
        try {
            // Real model file directly in the scan dir
            val realFile = File(tempDir, "real-model.litertlm").apply { writeBytes(ByteArray(100)) }

            // Symlink inside the dir pointing to a file outside it
            val symlinkPath = tempDir.toPath().resolve("link.litertlm")
            try {
                Files.createSymbolicLink(symlinkPath, outsideFile.toPath())
            } catch (e: Exception) {
                when (e) {
                    is UnsupportedOperationException,
                    is java.nio.file.AccessDeniedException ->
                        Assume.assumeTrue("Symlink creation not supported on this platform: ${e.message}", false)
                    else -> throw e
                }
            }

            val context = mockk<android.content.Context> {
                every { filesDir } returns tempDir
                every { getExternalFilesDir(null) } returns null
                every { cacheDir } returns File(tempDir, "nonexistent-cache") // won't exist
                every { applicationInfo } returns ApplicationInfo()
                every { assets } returns mockk {
                    every { list("") } returns emptyArray()
                    every { open("model_integrity.json") } throws java.io.IOException("no manifest")
                }
            }

            val models = ModelRegistry.discoverModels(context, requireIntegrity = false)

            assertEquals("Only the real file should be discovered, not the symlink", 1, models.size)
            assertEquals(realFile.absolutePath, models.single().path)
            assertEquals("real-model", models.single().id)
            assertTrue(models.none { it.id == "link" })
        } finally {
            tempDir.deleteRecursively()
            outsideFile.delete()
        }
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }

    private fun contextWithManifest(
        baseContext: Context,
        manifest: String = MODEL_INTEGRITY_MANIFEST,
        assetNames: Array<String> = emptyArray(),
        assetContents: Map<String, ByteArray> = emptyMap(),
        failAssetOpenFor: String? = null,
    ): Context {
        val assetManager = mockk<AssetManager> {
            every { open(any<String>()) } answers {
                val name = firstArg<String>()
                if (name == failAssetOpenFor) throw java.io.IOException("asset unavailable")
                val bytes = when (name) {
                    "model_integrity.json" -> manifest.encodeToByteArray()
                    else -> assetContents[name] ?: ByteArray(0)
                }
                ByteArrayInputStream(bytes)
            }
            every { list("") } returns assetNames
        }
        return mockk {
            every { filesDir } returns baseContext.filesDir
            every { getExternalFilesDir(null) } returns null
            every { cacheDir } returns baseContext.cacheDir
            every { applicationInfo } returns ApplicationInfo()
            every { assets } returns assetManager
        }
    }

    private companion object {
        private const val MODEL_INTEGRITY_MANIFEST = """
            {
              "models": [
                {
                  "filename": "manifest-empty.litertlm",
                  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
                },
                {
                  "filename": "manifest-size-mismatch.litertlm",
                  "sha256": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                  "sizeBytes": 42
                },
                {
                  "filename": "manifest-invalid-sha.litertlm",
                  "sha256": "not-a-sha256"
                }
              ]
            }
        """
    }
}
