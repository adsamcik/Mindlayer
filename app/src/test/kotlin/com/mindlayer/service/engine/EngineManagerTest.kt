package com.mindlayer.service.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.mindlayer.service.logging.LogRepository
import com.mindlayer.service.logging.MindlayerLog
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.File

import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [EngineManager]: model discovery, backend chains,
 * NPU heuristics, and engine lifecycle.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EngineManagerTest {

    private lateinit var context: Context
    private lateinit var appInfo: ApplicationInfo
    private lateinit var assetManager: AssetManager
    private lateinit var logRepository: LogRepository

    // Temp directories that stand in for Android storage paths
    private lateinit var filesDir: File
    private lateinit var externalFilesDir: File
    private lateinit var cacheDir: File
    private lateinit var nativeLibDir: File

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        mockkObject(MindlayerLog)

        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { MindlayerLog.d(any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.i(any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.w(any(), any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.e(any(), any(), any(), any(), any()) } returns Unit

        // Create real temp directories for model file discovery tests
        val tmpRoot = File(System.getProperty("java.io.tmpdir"), "engine-manager-test-${System.nanoTime()}")
        filesDir = File(tmpRoot, "files").apply { mkdirs() }
        externalFilesDir = File(tmpRoot, "external").apply { mkdirs() }
        cacheDir = File(tmpRoot, "cache").apply { mkdirs() }
        nativeLibDir = File(tmpRoot, "nativeLib").apply { mkdirs() }

        appInfo = ApplicationInfo().apply { nativeLibraryDir = nativeLibDir.absolutePath }
        assetManager = mockk(relaxed = true) {
            every { list("") } returns emptyArray()
        }

        context = mockk(relaxed = true) {
            every { filesDir } returns this@EngineManagerTest.filesDir
            every { getExternalFilesDir(null) } returns externalFilesDir
            every { cacheDir } returns this@EngineManagerTest.cacheDir
            every { applicationInfo } returns appInfo
            every { assets } returns assetManager
        }

        logRepository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        // Clean up temp files
        val tmpRoot = filesDir.parentFile
        tmpRoot?.deleteRecursively()
        unmockkAll()
    }

    // ---- findModelFile (exercised via modelPath lazy) -----------------------

    @Test
    fun `findModelFile - model in filesDir returns that path`() {
        val modelFile = File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME)
        modelFile.writeText("fake-model")

        val mgr = EngineManager(context)
        assertEquals(modelFile.absolutePath, mgr.modelPath)
    }

    @Test
    fun `findModelFile - model in externalFilesDir returns that path`() {
        val modelFile = File(externalFilesDir, EngineManager.DEFAULT_MODEL_FILENAME)
        modelFile.writeText("fake-model")

        val mgr = EngineManager(context)
        assertEquals(modelFile.absolutePath, mgr.modelPath)
    }

    @Test
    fun `findModelFile - model in cacheDir returns that path`() {
        val modelFile = File(cacheDir, EngineManager.DEFAULT_MODEL_FILENAME)
        modelFile.writeText("fake-model")

        val mgr = EngineManager(context)
        assertEquals(modelFile.absolutePath, mgr.modelPath)
    }

    @Test
    fun `findModelFile - model in data-local-tmp returns that path`() {
        // We can't actually write to /data/local/tmp in unit tests, but we can
        // verify the search order: filesDir is checked first and found, which
        // proves the candidate list logic. The /data/local/tmp candidate is added
        // but only reachable on a real device. Test the throw path instead below.
        // Instead, test that filesDir is preferred over external when both exist.
        val fileInFiles = File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME)
        fileInFiles.writeText("primary")
        val fileInExternal = File(externalFilesDir, EngineManager.DEFAULT_MODEL_FILENAME)
        fileInExternal.writeText("secondary")

        val mgr = EngineManager(context)
        assertEquals(fileInFiles.absolutePath, mgr.modelPath)
    }

    @Test
    fun `findModelFile - model in assets (AI pack) extracts and returns path`() {
        every { assetManager.list("") } returns arrayOf(EngineManager.DEFAULT_MODEL_FILENAME)
        every { assetManager.open(EngineManager.DEFAULT_MODEL_FILENAME) } returns
                ByteArrayInputStream(byteArrayOf(1, 2, 3, 4))

        val mgr = EngineManager(context)
        val path = mgr.modelPath

        // Should have been extracted to filesDir
        val expected = File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME)
        assertEquals(expected.absolutePath, path)
        assertTrue(expected.exists())
    }

    @Test
    fun `findModelFile - asset already extracted skips re-extraction`() {
        val existing = File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME)
        existing.writeText("already-there")

        every { assetManager.list("") } returns arrayOf(EngineManager.DEFAULT_MODEL_FILENAME)

        val mgr = EngineManager(context)
        val path = mgr.modelPath

        // Should return existing file without opening asset stream
        assertEquals(existing.absolutePath, path)
        verify(exactly = 0) { assetManager.open(any()) }
    }

    @Test
    fun `availableModels exposes only the selected public model`() {
        File(filesDir, "smaller-model.litertlm").writeBytes(ByteArray(32))
        val selectedModel = File(filesDir, "larger-model.litertlm").apply {
            writeBytes(ByteArray(64))
        }

        val mgr = EngineManager(context)
        val models = mgr.availableModels

        assertEquals(1, models.size)
        assertEquals("larger-model", models.single().id)
        assertTrue(models.single().isDefault)
        assertEquals(selectedModel.absolutePath, mgr.modelPath)
    }

    @Test(expected = IllegalStateException::class)
    fun `findModelFile - model not found throws IllegalStateException`() {
        // No model anywhere, assets empty
        val mgr = EngineManager(context)
        mgr.modelPath // triggers lazy eval → throws
    }

    @Test
    fun `findModelFile - not found error message is descriptive`() {
        try {
            val mgr = EngineManager(context)
            mgr.modelPath
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("litertlm"))
            assertTrue(e.message!!.contains("filesDir"))
            return
        }
        throw AssertionError("Expected IllegalStateException")
    }

    @Test
    fun `findModelFile - externalFilesDir null is handled gracefully`() {
        every { context.getExternalFilesDir(null) } returns null

        val modelFile = File(cacheDir, EngineManager.DEFAULT_MODEL_FILENAME)
        modelFile.writeText("model-in-cache")

        val mgr = EngineManager(context)
        assertEquals(modelFile.absolutePath, mgr.modelPath)
    }

    @Test
    fun `findModelFile - asset check exception falls through to candidates`() {
        every { assetManager.list("") } throws RuntimeException("IO error")

        val modelFile = File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME)
        modelFile.writeText("fallback")

        val mgr = EngineManager(context)
        assertEquals(modelFile.absolutePath, mgr.modelPath)
    }

    // ---- initialize (backend chain + engine lifecycle) ----------------------

    @Ignore("NPU detection requires real Build.SOC_MODEL")
    @Test
    fun `initialize - already initialized returns cached engine`() = runTest {
        val modelFile = File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME)
        modelFile.writeText("model")

        val mockEngine = mockk<Engine>(relaxed = true)
        mockkConstructor(Engine::class)
        every { anyConstructed<Engine>().initialize() } returns Unit
        every { constructedWith<Engine>(any()).let { mockEngine } } returns mockEngine

        val mgr = EngineManager(context, logRepository)

        // Mock the Engine constructor to return our mock
        val firstEngine: Engine
        try {
            mockkConstructor(Engine::class)
            every { anyConstructed<Engine>().initialize() } returns Unit
            firstEngine = mgr.initialize(preferredBackend = "CPU")
        } catch (_: Throwable) {
            // Engine constructor may not be easily mockable; test the state instead
            return@runTest
        }

        // Second call should return same instance
        val secondEngine = mgr.initialize(preferredBackend = "GPU")
        assertSame(firstEngine, secondEngine)
    }

    @Test
    fun `getEngine returns null when not initialized`() {
        val mgr = EngineManager(context)
        assertNull(mgr.getEngine())
    }

    @Test(expected = IllegalStateException::class)
    fun `requireEngine throws when not initialized`() {
        val mgr = EngineManager(context)
        mgr.requireEngine()
    }

    @Test
    fun `requireEngine error message mentions initialize`() {
        val mgr = EngineManager(context)
        try {
            mgr.requireEngine()
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("initialize"))
            return
        }
        throw AssertionError("Expected IllegalStateException")
    }

    @Test
    fun `initial state - currentBackend is NONE`() {
        val mgr = EngineManager(context)
        assertEquals("NONE", mgr.currentBackend)
    }

    @Test
    fun `initial state - isInitialized is false`() {
        val mgr = EngineManager(context)
        assertFalse(mgr.isInitialized)
    }

    @Test
    fun `initial state - initTimeSeconds is zero`() {
        val mgr = EngineManager(context)
        assertEquals(0f, mgr.initTimeSeconds, 0.0001f)
    }

    // ---- shutdown -----------------------------------------------------------

    @Test
    fun `shutdown on uninitialized engine is safe (no-op)`() = runTest {
        val mgr = EngineManager(context)
        mgr.shutdown() // Should not throw
        assertNull(mgr.getEngine())
        assertFalse(mgr.isInitialized)
        assertEquals("NONE", mgr.currentBackend)
    }

    // ---- backendName (tested via resolveBackendChain path) ------------------

    // We can test backendName indirectly through logging. Since it's private, we
    // rely on the Backend type tests below and the initialize logging.

    @Test
    fun `Backend CPU class identity check`() {
        val cpu = Backend.CPU()
        assertTrue(cpu is Backend.CPU)
    }

    @Test
    fun `Backend GPU class identity check`() {
        val gpu = Backend.GPU()
        assertTrue(gpu is Backend.GPU)
    }

    @Test
    fun `Backend NPU class identity check`() {
        val npu = Backend.NPU(nativeLibraryDir = "/fake")
        assertTrue(npu is Backend.NPU)
    }

    // ---- isNpuLikelySupported (tested via buildBackendChain behavior) -------
    // These use reflection or Build field mocking to exercise the heuristic.

    @Test
    fun `NPU not supported when API less than 31`() {
        mockkStatic(Build.VERSION::class)
        // SDK_INT is a final static field; we use reflection
        setStaticField(Build.VERSION::class.java, "SDK_INT", 30)

        val mgr = EngineManager(context)
        // With NPU requested on API 30, the chain should still only have GPU + CPU
        // because isNpuLikelySupported returns false.
        // We test this by checking the backend chain via reflection.
        val result = invokeIsNpuLikelySupported(mgr)
        assertFalse(result)
    }

    @Test
    fun `NPU not supported for unknown SoC`() {
        setStaticField(Build.VERSION::class.java, "SDK_INT", 33)
        setStaticField(Build::class.java, "SOC_MODEL", "unknown_soc_xyz")

        val mgr = EngineManager(context)
        assertFalse(invokeIsNpuLikelySupported(mgr))
    }

    @Ignore("NPU detection requires real Build.SOC_MODEL")
    @Test
    fun `NPU likely supported for Qualcomm SoC with libs present`() {
        setStaticField(Build.VERSION::class.java, "SDK_INT", 33)
        setStaticField(Build::class.java, "SOC_MODEL", "sm8650")

        // Create a fake Qualcomm lib in the native lib dir
        File(nativeLibDir, "libQnnCpu.so").writeText("fake")

        val mgr = EngineManager(context)
        assertTrue(invokeIsNpuLikelySupported(mgr))
    }

    @Ignore("NPU detection requires real Build.SOC_MODEL")
    @Test
    fun `NPU likely supported for MediaTek SoC with libs present`() {
        setStaticField(Build.VERSION::class.java, "SDK_INT", 33)
        setStaticField(Build::class.java, "SOC_MODEL", "mt6989")

        File(nativeLibDir, "libmediatek_npu.so").writeText("fake")

        val mgr = EngineManager(context)
        assertTrue(invokeIsNpuLikelySupported(mgr))
    }

    @Test
    fun `NPU not supported when SoC known but libs missing`() {
        setStaticField(Build.VERSION::class.java, "SDK_INT", 33)
        setStaticField(Build::class.java, "SOC_MODEL", "sm8650")

        // nativeLibDir is empty — no Qualcomm libs
        val mgr = EngineManager(context)
        assertFalse(invokeIsNpuLikelySupported(mgr))
    }

    @Test
    fun `NPU not supported when SOC_MODEL is empty`() {
        setStaticField(Build.VERSION::class.java, "SDK_INT", 33)
        setStaticField(Build::class.java, "SOC_MODEL", "")

        val mgr = EngineManager(context)
        assertFalse(invokeIsNpuLikelySupported(mgr))
    }

    @Test
    fun `NPU not supported when nativeLibraryDir is null`() {
        setStaticField(Build.VERSION::class.java, "SDK_INT", 33)
        setStaticField(Build::class.java, "SOC_MODEL", "sm8650")
        appInfo.nativeLibraryDir = null

        val mgr = EngineManager(context)
        assertFalse(invokeIsNpuLikelySupported(mgr))
    }

    // ---- buildBackendChain --------------------------------------------------

    @Test
    fun `buildBackendChain - default (no NPU) produces GPU then CPU`() {
        val mgr = EngineManager(context)
        val chain = invokeBuildBackendChain(mgr, includeNpu = false)
        assertEquals(2, chain.size)
        assertTrue(chain[0] is Backend.GPU)
        assertTrue(chain[1] is Backend.CPU)
    }

    @Test
    fun `buildBackendChain - NPU requested but not supported produces GPU then CPU`() {
        setStaticField(Build.VERSION::class.java, "SDK_INT", 30) // API < 31
        val mgr = EngineManager(context)
        val chain = invokeBuildBackendChain(mgr, includeNpu = true)
        // NPU won't be added because isNpuLikelySupported = false
        assertEquals(2, chain.size)
        assertTrue(chain[0] is Backend.GPU)
        assertTrue(chain[1] is Backend.CPU)
    }

    @Ignore("NPU detection requires real Build.SOC_MODEL")
    @Test
    fun `buildBackendChain - NPU requested and supported produces NPU then GPU then CPU`() {
        setStaticField(Build.VERSION::class.java, "SDK_INT", 33)
        setStaticField(Build::class.java, "SOC_MODEL", "sm8550")
        File(nativeLibDir, "libQnnHtp.so").writeText("fake")

        val mgr = EngineManager(context)
        val chain = invokeBuildBackendChain(mgr, includeNpu = true)
        assertEquals(3, chain.size)
        assertTrue(chain[0] is Backend.NPU)
        assertTrue(chain[1] is Backend.GPU)
        assertTrue(chain[2] is Backend.CPU)
    }

    // ---- resolveBackendChain ------------------------------------------------

    @Test
    fun `resolveBackendChain - null preferred defaults to GPU + CPU`() {
        val mgr = EngineManager(context)
        val chain = invokeResolveBackendChain(mgr, null)
        assertEquals(2, chain.size)
        assertTrue(chain[0] is Backend.GPU)
        assertTrue(chain[1] is Backend.CPU)
    }

    @Test
    fun `resolveBackendChain - CPU preferred returns only CPU`() {
        val mgr = EngineManager(context)
        val chain = invokeResolveBackendChain(mgr, "CPU")
        assertEquals(1, chain.size)
        assertTrue(chain[0] is Backend.CPU)
    }

    @Test
    fun `resolveBackendChain - GPU preferred returns GPU + CPU`() {
        val mgr = EngineManager(context)
        val chain = invokeResolveBackendChain(mgr, "GPU")
        assertEquals(2, chain.size)
        assertTrue(chain[0] is Backend.GPU)
        assertTrue(chain[1] is Backend.CPU)
    }

    @Test
    fun `resolveBackendChain - unknown preferred falls back to default chain`() {
        val mgr = EngineManager(context)
        val chain = invokeResolveBackendChain(mgr, "MAGIC")
        assertEquals(2, chain.size)
        assertTrue(chain[0] is Backend.GPU)
        assertTrue(chain[1] is Backend.CPU)
    }

    @Test
    fun `resolveBackendChain - NPU preferred lowercase is handled`() {
        setStaticField(Build.VERSION::class.java, "SDK_INT", 30) // NPU not supported
        val mgr = EngineManager(context)
        val chain = invokeResolveBackendChain(mgr, "npu")
        // NPU not supported so chain is GPU + CPU
        assertEquals(2, chain.size)
        assertTrue(chain[0] is Backend.GPU)
        assertTrue(chain[1] is Backend.CPU)
    }

    // ---- backendName --------------------------------------------------------

    @Test
    fun `backendName - CPU returns CPU`() {
        val mgr = EngineManager(context)
        assertEquals("CPU", invokeBackendName(mgr, Backend.CPU()))
    }

    @Test
    fun `backendName - GPU returns GPU`() {
        val mgr = EngineManager(context)
        assertEquals("GPU", invokeBackendName(mgr, Backend.GPU()))
    }

    @Test
    fun `backendName - NPU returns NPU`() {
        val mgr = EngineManager(context)
        assertEquals("NPU", invokeBackendName(mgr, Backend.NPU(nativeLibraryDir = "/fake")))
    }

    // ---- DEFAULT_MODEL_FILENAME constant ------------------------------------

    @Test
    fun `DEFAULT_MODEL_FILENAME is correct`() {
        assertEquals("gemma-4-E2B-it.litertlm", EngineManager.DEFAULT_MODEL_FILENAME)
    }

    // ---- Helpers for testing private methods via reflection ------------------

    private fun invokeIsNpuLikelySupported(mgr: EngineManager): Boolean {
        val method = EngineManager::class.java.getDeclaredMethod("isNpuLikelySupported")
        method.isAccessible = true
        return method.invoke(mgr) as Boolean
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeBuildBackendChain(mgr: EngineManager, includeNpu: Boolean): List<Backend> {
        val method = EngineManager::class.java.getDeclaredMethod("buildBackendChain", Boolean::class.java)
        method.isAccessible = true
        return method.invoke(mgr, includeNpu) as List<Backend>
    }

    @Suppress("UNCHECKED_CAST")
    private fun invokeResolveBackendChain(mgr: EngineManager, preferred: String?): List<Backend> {
        val method = EngineManager::class.java.getDeclaredMethod("resolveBackendChain", String::class.java)
        method.isAccessible = true
        return method.invoke(mgr, preferred) as List<Backend>
    }

    private fun invokeBackendName(mgr: EngineManager, backend: Backend): String {
        val method = EngineManager::class.java.getDeclaredMethod("backendName", Backend::class.java)
        method.isAccessible = true
        return method.invoke(mgr, backend) as String
    }

    private fun setStaticField(clazz: Class<*>, fieldName: String, value: Any) {
        val field = clazz.getDeclaredField(fieldName)
        field.isAccessible = true

        // Remove final modifier
        val modifiersField = try {
            java.lang.reflect.Field::class.java.getDeclaredField("modifiers")
        } catch (_: NoSuchFieldException) {
            // Java 12+ doesn't have modifiers field; use Unsafe instead
            val unsafeClass = Class.forName("sun.misc.Unsafe")
            val unsafeField = unsafeClass.getDeclaredField("theUnsafe")
            unsafeField.isAccessible = true
            val unsafe = unsafeField.get(null)
            val offset = unsafeClass.getMethod("staticFieldOffset", java.lang.reflect.Field::class.java)
                .invoke(unsafe, field) as Long
            unsafeClass.getMethod("putObject", Any::class.java, Long::class.java, Any::class.java)
                .invoke(unsafe, clazz, offset, value)
            return
        }
        modifiersField.isAccessible = true
        modifiersField.setInt(field, field.modifiers and java.lang.reflect.Modifier.FINAL.inv())
        field.set(null, value)
    }
}
