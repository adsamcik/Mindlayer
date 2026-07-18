package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.os.Build
import android.util.Log
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.MindlayerLog
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

        appInfo = ApplicationInfo().apply {
            nativeLibraryDir = nativeLibDir.absolutePath
            flags = ApplicationInfo.FLAG_DEBUGGABLE
        }
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
    fun `findModelFile - materialized on-demand model returns private path`() {
        val deliveryDir = File(filesDir, "model_delivery/chat").apply { mkdirs() }
        val expected = File(deliveryDir, EngineManager.DEFAULT_MODEL_FILENAME)
        expected.writeBytes(byteArrayOf(1, 2, 3, 4))
        val mgr = EngineManager(context)
        val path = mgr.modelPath

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
        verify(exactly = 0) { assetManager.open(EngineManager.DEFAULT_MODEL_FILENAME) }
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
    fun `initialized fast path rejects canonical chat removal before marker reconciliation`() = runTest {
        val mgr = EngineManager(context)
        val engineField = EngineManager::class.java.getDeclaredField("engine")
        engineField.isAccessible = true
        engineField.set(mgr, mockk<Engine>(relaxed = true))
        val family = com.adsamcik.mindlayer.service.modeldelivery.ModelFamily.CHAT
        com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryIntentStore(filesDir)
            .recordRemoval(family)
        val pending = com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryFileLock
            .pendingRemovalMarker(filesDir, family)
        val tombstone = com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryFileLock
            .removalTombstone(filesDir, family)
        assertTrue(pending.delete())
        assertTrue(tombstone.delete())

        assertNull(mgr.getEngine())
        val result = runCatching { mgr.requireEngine() }
        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(result.exceptionOrNull()?.message?.contains("removal") == true)
        assertTrue(runCatching { mgr.initialize() }.exceptionOrNull() is IllegalStateException)
        assertFalse(pending.exists())
        assertFalse(tombstone.exists())
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

    @Test
    fun `initialize records chat accelerator decision`() = runTest {
        File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME).writeText("fake-model")
        stubActivityManager()
        LiteRtAcceleratorResolver.resetForTesting()

        val mockEngine = mockk<Engine>(relaxed = true) {
            every { initialize() } returns Unit
        }
        val mgr = EngineManager(context, logRepository)
        mgr.engineFactory = { _ -> mockEngine }

        try {
            mgr.initialize(preferredBackend = "CPU")

            val decision = LiteRtAcceleratorResolver.latestDecision("chat")
            assertNotNull(decision)
            assertEquals("CPU", decision?.backend)
            assertEquals("REQUESTED_CPU", decision?.reason)
            verify {
                logRepository.logBackendDecision(
                    featureName = "chat",
                    backend = "CPU",
                    reason = "REQUESTED_CPU",
                    attempted = listOf("CPU" to "selected"),
                )
            }
        } finally {
            LiteRtAcceleratorResolver.resetForTesting()
        }
    }

    @Test
    fun `initial state - lastGpuFailureReason is null`() {
        val mgr = EngineManager(context)
        assertNull(mgr.lastGpuFailureReason)
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

    @Test
    fun `shutdown clears lastGpuFailureReason`() = runTest {
        val mgr = EngineManager(context)
        // F-077: lastGpuFailureReason is now a computed shim over
        // lastInitFailure. Drive the source of truth — setting the typed
        // field implicitly populates the shim. The shutdown invariant
        // is the same as before: a clean teardown clears prior init
        // failure state so it doesn't bleed into the next init run.
        val initFailureField = EngineManager::class.java.getDeclaredField("lastInitFailure")
        initFailureField.isAccessible = true
        initFailureField.set(
            mgr,
            InitFailure.BackendUnavailable(backend = "GPU", safeLabel = "RuntimeException"),
        )

        assertEquals("RuntimeException", mgr.lastGpuFailureReason)

        // Create a model file so selectedModel lazy doesn't throw during init
        File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME).writeText("fake-model")

        // Force engine field to non-null so shutdownInternal actually executes the cleanup block
        val engineField = EngineManager::class.java.getDeclaredField("engine")
        engineField.isAccessible = true
        val mockEngine = mockk<Engine>(relaxed = true)
        engineField.set(mgr, mockEngine)

        mgr.shutdown()

        assertNull(mgr.lastGpuFailureReason)
        assertNull(mgr.lastInitFailure)
    }

    // ---- initialize - partial-init cleanup (leak prevention) ----------------
    // The LiteRT-LM Engine constructor allocates native resources BEFORE
    // initialize() is even called. If initialize() throws, the partially-
    // constructed engine must be close()-d or the backend-fallback loop
    // leaks native heap on every retry. These tests pin that invariant.

    /**
     * The leak-prevention tests drive `EngineManager.initialize`, which
     * queries `ActivityManager` for a memory pre-flight check. The
     * top-level `context` mock is `relaxed = true` but generic
     * `getSystemService(Class)` returns `Object` by default, which then
     * fails the cast at `EngineManager.kt:initialize`. This helper wires
     * `getSystemService(ActivityManager::class.java)` to a mock that
     * answers `getMemoryInfo` with realistic values.
     */
    private fun stubActivityManager(availMb: Long = 4_096L, totalMb: Long = 8_192L) {
        val activityManager = mockk<android.app.ActivityManager>(relaxed = true)
        every { activityManager.getMemoryInfo(any()) } answers {
            val info = firstArg<android.app.ActivityManager.MemoryInfo>()
            info.availMem = availMb * 1024 * 1024
            info.totalMem = totalMb * 1024 * 1024
            info.lowMemory = false
        }
        every { context.getSystemService(android.app.ActivityManager::class.java) } returns activityManager
    }

    @Test
    fun `initialize - close called on every engine when initialize throws`() = runTest {
        File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME).writeText("fake-model")
        stubActivityManager()

        val mgr = EngineManager(context, logRepository)
        val createdEngines = mutableListOf<Engine>()
        mgr.engineFactory = { _ ->
            mockk<Engine>(relaxed = true) {
                every { initialize() } throws RuntimeException("simulated init failure")
            }.also { createdEngines.add(it) }
        }

        try {
            mgr.initialize(preferredBackend = null) // GPU then CPU
        } catch (_: IllegalStateException) {
            // expected: "All backends failed"
        }

        // Default chain is GPU + CPU; both attempts construct an engine, both
        // throw, and BOTH must have been closed before the loop moved on.
        assertEquals(2, createdEngines.size)
        createdEngines.forEach { engine ->
            verify(exactly = 1) { engine.close() }
        }

        // The leak guarantee also implies no engine is retained as the
        // current engine after a total failure.
        assertNull(mgr.getEngine())
        assertFalse(mgr.isInitialized)
    }

    @Test
    fun `initialize - secondary close exception does not mask original cause`() = runTest {
        File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME).writeText("fake-model")
        stubActivityManager()

        val mgr = EngineManager(context, logRepository)
        mgr.engineFactory = { _ ->
            mockk<Engine>(relaxed = true) {
                every { initialize() } throws RuntimeException("init failure")
                every { close() } throws RuntimeException("close failure too")
            }
        }

        try {
            mgr.initialize(preferredBackend = "CPU") // single-backend chain, deterministic
            throw AssertionError("Expected IllegalStateException for failed init")
        } catch (e: IllegalStateException) {
            // The "All backends failed" wrapper is what the caller should see;
            // the secondary close() failure must not mask it. The original
            // initialize() throwable is the wrapper's `cause`.
            assertTrue(
                "Wrapper message must reference all-backends failure, was: ${e.message}",
                e.message!!.contains("All backends failed"),
            )
            assertNotNull("Original initialize() exception must be preserved as cause", e.cause)
            assertEquals("init failure", e.cause!!.message)
        }
    }

    @Test
    fun `initialize - successful init does not call close on the engine`() = runTest {
        File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME).writeText("fake-model")
        stubActivityManager()

        val mgr = EngineManager(context, logRepository)
        val mockEngine = mockk<Engine>(relaxed = true) {
            every { initialize() } returns Unit
        }
        mgr.engineFactory = { _ -> mockEngine }

        val eng = mgr.initialize(preferredBackend = "CPU")

        assertSame(mockEngine, eng)
        verify(exactly = 0) { mockEngine.close() }
        assertTrue(mgr.isInitialized)
        assertEquals("CPU", mgr.currentBackend)
    }

    @Test
    fun `initialize - fast path on an already-loaded engine warns when a caller requests a larger maxTokens ceiling`() = runTest {
        // Regression test for the root cause of a multi-turn liblitertlm_jni.so
        // SIGSEGV: the fast path silently discarded a later caller's maxTokens
        // request once an Engine instance already existed, so a caller warmed
        // with a small ceiling (e.g. prewarm's old hardcoded 4096) kept that
        // ceiling for the engine's entire lifetime even though a real session
        // asked for more (8192). This test locks in that the mismatch is at
        // least now surfaced via a log warning.
        File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME).writeText("fake-model")
        stubActivityManager()

        val mgr = EngineManager(context, logRepository)
        val mockEngine = mockk<Engine>(relaxed = true) {
            every { initialize() } returns Unit
        }
        mgr.engineFactory = { _ -> mockEngine }

        val first = mgr.initialize(preferredBackend = "CPU", maxTokens = 4096)
        val second = mgr.initialize(preferredBackend = "CPU", maxTokens = 8192)

        assertSame(first, second)
        verify {
            MindlayerLog.w(
                any(),
                match { it.contains("maxTokens=8192") && it.contains("maxTokens=4096") },
                any(),
                any(),
                any(),
            )
        }
    }

    @Test
    fun `initialize - engine factory throwing is treated as init failure (no close to call)`() = runTest {
        File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME).writeText("fake-model")
        stubActivityManager()

        val mgr = EngineManager(context, logRepository)
        mgr.engineFactory = { _ -> throw RuntimeException("constructor failure") }

        try {
            mgr.initialize(preferredBackend = "CPU")
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // Constructor failure is caught by the same outer catch as init
            // failure; the loop falls through cleanly to "All backends failed".
            assertTrue(e.message!!.contains("All backends failed"))
        }
        assertNull(mgr.getEngine())
        assertFalse(mgr.isInitialized)
    }

    // ---- initialize - backend fallback chain execution ----------------------
    // These tests pin the NPU→GPU→CPU fallback BEHAVIOUR (not just the chain
    // shape, which the buildBackendChain tests above already cover). The
    // invariants: when an earlier backend fails, the next is tried; the
    // first to succeed wins; if all fail, the diagnostic message lists what
    // was tried; GPU failure label is preserved on lastGpuFailureReason
    // even when the chain ultimately succeeds via CPU; GPU success clears it.

    @Test
    fun `initialize - GPU init throws then CPU succeeds, currentBackend is CPU`() = runTest {
        File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME).writeText("fake-model")
        stubActivityManager()

        val mgr = EngineManager(context, logRepository)
        val attemptedBackends = mutableListOf<Backend>()
        val gpuEngine = mockk<Engine>(relaxed = true) {
            every { initialize() } throws RuntimeException("simulated GPU failure")
        }
        val cpuEngine = mockk<Engine>(relaxed = true) {
            every { initialize() } returns Unit
        }
        mgr.engineFactory = { config ->
            attemptedBackends.add(config.backend)
            when (config.backend) {
                is Backend.GPU -> gpuEngine
                is Backend.CPU -> cpuEngine
                else -> error("Unexpected backend in default chain: ${config.backend}")
            }
        }

        val resultEngine = mgr.initialize(preferredBackend = null) // chain = [GPU, CPU]

        // Order is the heart of the test: GPU MUST be tried first, then CPU.
        assertEquals(2, attemptedBackends.size)
        assertTrue("First attempt must be GPU, was ${attemptedBackends[0]}", attemptedBackends[0] is Backend.GPU)
        assertTrue("Fallback attempt must be CPU, was ${attemptedBackends[1]}", attemptedBackends[1] is Backend.CPU)

        // CPU's engine is the one returned and tracked as current.
        assertSame(cpuEngine, resultEngine)
        assertEquals("CPU", mgr.currentBackend)
        assertTrue(mgr.isInitialized)

        // Stacked invariant from F-070: the failed GPU engine MUST have been
        // closed before fallback proceeded; the surviving CPU engine MUST NOT.
        verify(exactly = 1) { gpuEngine.close() }
        verify(exactly = 0) { cpuEngine.close() }
    }

    @Test
    fun `initialize - GPU failure label is captured on lastGpuFailureReason after CPU fallback`() = runTest {
        File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME).writeText("fake-model")
        stubActivityManager()

        val mgr = EngineManager(context, logRepository)
        mgr.engineFactory = { config ->
            when (config.backend) {
                is Backend.GPU -> mockk<Engine>(relaxed = true) {
                    every { initialize() } throws RuntimeException("GPU driver crash")
                }
                is Backend.CPU -> mockk<Engine>(relaxed = true) {
                    every { initialize() } returns Unit
                }
                else -> error("Unexpected backend in default chain: ${config.backend}")
            }
        }

        mgr.initialize(preferredBackend = null)

        // F-006: safeLabel() reports class name only — the full GPU exception
        // detail is intentionally NOT included (could embed prompt fragments).
        // Just assert the label is non-null and references the throwable class.
        val reason = mgr.lastGpuFailureReason
        assertNotNull(
            "GPU failure reason must persist even when the chain ultimately succeeds via CPU " +
                "(the dashboard surfaces this so operators know GPU went bad)",
            reason,
        )
        assertTrue(
            "Reason should reference the throwable class for diagnostics, was: $reason",
            reason!!.contains("RuntimeException"),
        )
    }

    @Test
    fun `initialize - GPU success leaves lastGpuFailureReason null`() = runTest {
        File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME).writeText("fake-model")
        stubActivityManager()

        val mgr = EngineManager(context, logRepository)
        mgr.engineFactory = { _ ->
            mockk<Engine>(relaxed = true) {
                every { initialize() } returns Unit
            }
        }

        mgr.initialize(preferredBackend = "GPU")

        // GPU was the first (and successful) backend; no failure to record.
        // EngineManager:166-168 clears the field on GPU success, so even if a
        // prior GPU init had set it, the latest success wins.
        assertNull(mgr.lastGpuFailureReason)
        assertEquals("GPU", mgr.currentBackend)
    }

    @Test
    fun `initialize - all-backends-failed message identifies the attempted chain`() = runTest {
        File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME).writeText("fake-model")
        stubActivityManager()

        val mgr = EngineManager(context, logRepository)
        mgr.engineFactory = { _ ->
            mockk<Engine>(relaxed = true) {
                every { initialize() } throws RuntimeException("simulated")
            }
        }

        try {
            mgr.initialize(preferredBackend = null) // chain = [GPU, CPU]
            throw AssertionError("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // Diagnostic invariant: when all backends fail, the operator
            // must be told which backends were tried, so they can interpret
            // the failure (e.g., "NPU not in chain → SoC detection probably
            // wrong" vs "GPU and CPU both failed → model file is broken").
            val msg = e.message ?: ""
            assertTrue("Failure message must mention GPU was tried, was: $msg", msg.contains("GPU"))
            assertTrue("Failure message must mention CPU was tried, was: $msg", msg.contains("CPU"))
            assertTrue(
                "Failure message must be identifiable as the all-backends-failed wrapper, was: $msg",
                msg.contains("All backends failed"),
            )
        }
    }

    // ---- backendName (tested via resolveBackendChain path) ------------------

    // We can test backendName indirectly through logging. Since it's private, we
    // rely on the Backend type tests below and the initialize logging.

    @Test
    fun `Backend CPU class identity check`() {
        val cpu = Backend.CPU()
        assertNotNull(cpu)
    }

    @Test
    fun `Backend GPU class identity check`() {
        val gpu = Backend.GPU()
        assertNotNull(gpu)
    }

    @Test
    fun `Backend NPU class identity check`() {
        val npu = Backend.NPU(nativeLibraryDir = "/fake")
        assertNotNull(npu)
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
