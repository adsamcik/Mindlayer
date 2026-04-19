package com.adsamcik.mindlayer.service.engine

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

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

        val models = ModelRegistry.discoverModels(context)
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

        val models = ModelRegistry.discoverModels(context)

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
}
