package com.adsamcik.mindlayer.service.engine

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Engine
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * F-077: regression coverage for the typed [InitFailure] categorisation
 * that replaces the opaque `lastGpuFailureReason: String?`.
 *
 * Every variant of the sealed class must be reachable from a real init
 * code path (not just the `when` branch on the dashboard side):
 *  - [InitFailure.LowMemory] — F-071's pre-flight RAM check
 *  - [InitFailure.BackendUnavailable] — per-backend init throw inside
 *    the fallback loop
 *  - [InitFailure.ModelMissing] — `selectedModel` lazy throws
 *  - [InitFailure.IntegrityMismatch] — SHA-256 mismatch on the on-disk
 *    file (test seam: `EngineManager.expectedModelSha256`)
 *  - [InitFailure.NativeError] — reserved for the catch-all bucket; not
 *    yet reachable through `initialize()` (every site in the body now
 *    categorises specifically), so its mapping is exercised by the
 *    serialise/deserialise round-trip in DashboardViewModelTest.
 *
 * The tests also pin the `recordInitFailure` invariant: every
 * categorisation site MUST also call
 * [LogRepository.logInitFailureCategorized] so the dashboard's polling
 * read-path sees the typed signal.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EngineManagerInitFailureTest {

    private lateinit var context: Context
    private lateinit var activityManager: ActivityManager
    private lateinit var logRepository: LogRepository

    private lateinit var filesDir: File
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

        val tmpRoot = File(System.getProperty("java.io.tmpdir"), "engine-init-failure-${System.nanoTime()}")
        filesDir = File(tmpRoot, "files").apply { mkdirs() }
        cacheDir = File(tmpRoot, "cache").apply { mkdirs() }
        nativeLibDir = File(tmpRoot, "lib").apply { mkdirs() }

        activityManager = mockk(relaxed = true)
        // FLAG_DEBUGGABLE makes ModelRegistry.discoverModels() default to
        // requireIntegrity = false, so the bogus 4 KB file from
        // writeModelFile() is accepted at discovery time. Without this,
        // PR #21's correctly-restored fail-closed default rejects the
        // file as missing-integrity-metadata and every test in this
        // class crashes with "No .litertlm model files found" before it
        // can exercise its actual assertion. Mirrors EngineManagerTest.
        val appInfo = ApplicationInfo().apply {
            nativeLibraryDir = nativeLibDir.absolutePath
            flags = ApplicationInfo.FLAG_DEBUGGABLE
        }
        val assetManager = mockk<android.content.res.AssetManager>(relaxed = true) {
            every { list("") } returns emptyArray()
        }
        context = mockk(relaxed = true) {
            every { filesDir } returns this@EngineManagerInitFailureTest.filesDir
            every { cacheDir } returns this@EngineManagerInitFailureTest.cacheDir
            every { getExternalFilesDir(null) } returns null
            every { applicationInfo } returns appInfo
            every { assets } returns assetManager
            every { getSystemService(ActivityManager::class.java) } returns activityManager
        }

        logRepository = mockk(relaxed = true)
    }

    @After
    fun tearDown() {
        filesDir.parentFile?.deleteRecursively()
        unmockkAll()
    }

    // ---- LowMemory ---------------------------------------------------------

    @Test
    fun `LowMemoryException categorises lastInitFailure as LowMemory`() = runTest {
        writeModelFile()
        stubAvailMem(50L * 1024 * 1024) // 50 MB, far below the 1 GB runway floor

        val mgr = EngineManager(context, logRepository)
        try {
            mgr.initialize(preferredBackend = "CPU")
            fail("Expected LowMemoryException")
        } catch (_: LowMemoryException) {
            // expected
        }

        assertEquals(InitFailure.LowMemory, mgr.lastInitFailure)
        verify(exactly = 1) { logRepository.logInitFailureCategorized(InitFailure.LowMemory) }
    }

    @Test
    fun `LowMemory categorisation happens BEFORE LowMemoryException is thrown`() = runTest {
        // Pin the ordering: observers checking lastInitFailure on the
        // exception-handling path see a typed value, not null.
        writeModelFile()
        stubAvailMem(50L * 1024 * 1024)

        val mgr = EngineManager(context, logRepository)
        try {
            mgr.initialize(preferredBackend = "CPU")
            fail("Expected LowMemoryException")
        } catch (_: LowMemoryException) {
            assertEquals(
                "lastInitFailure must be set BEFORE the throw so it's visible to catchers",
                InitFailure.LowMemory,
                mgr.lastInitFailure,
            )
        }
    }

    // ---- BackendUnavailable ------------------------------------------------

    @Test
    fun `Backend init failure categorises lastInitFailure as BackendUnavailable for that backend`() = runTest {
        writeModelFile()
        stubAvailMem()

        val mgr = EngineManager(context, logRepository)
        mgr.engineFactory = { _ ->
            mockk<Engine>(relaxed = true) {
                every { initialize() } throws RuntimeException("simulated init failure")
            }
        }

        try {
            mgr.initialize(preferredBackend = "CPU") // single-backend chain
            fail("Expected IllegalStateException all-backends-failed wrapper")
        } catch (_: IllegalStateException) {
            // expected: single CPU attempt failed → all-backends wrapper
        }

        val failure = mgr.lastInitFailure
        assertTrue(
            "Expected BackendUnavailable, got $failure",
            failure is InitFailure.BackendUnavailable,
        )
        val typed = failure as InitFailure.BackendUnavailable
        assertEquals("CPU", typed.backend)
        // F-006: safeLabel returns class names only — never the raw
        // exception message. The label is class-name-or-chain
        // (`Foo -> Bar` when a cause is present), so we assert the
        // start-with shape rather than equality. Crucially, none of
        // the original "simulated init failure" message must leak.
        assertTrue(
            "safeLabel must start with the throwable's class name, was: ${typed.safeLabel}",
            typed.safeLabel.startsWith("RuntimeException"),
        )
        assertTrue(
            "safeLabel must not contain the raw exception message — F-006",
            !typed.safeLabel.contains("simulated"),
        )
        verify(atLeast = 1) { logRepository.logInitFailureCategorized(any<InitFailure.BackendUnavailable>()) }
    }

    @Test
    fun `Backend chain failure - lastInitFailure reflects the LAST attempted backend`() = runTest {
        // GPU then CPU both fail; lastInitFailure should be CPU's category
        // (the most recently observed failure), not GPU's.
        writeModelFile()
        stubAvailMem()

        val mgr = EngineManager(context, logRepository)
        mgr.engineFactory = { config ->
            mockk<Engine>(relaxed = true) {
                every { initialize() } throws when (config.backend) {
                    is Backend.GPU -> RuntimeException("gpu boom")
                    is Backend.CPU -> IllegalStateException("cpu boom")
                    else -> Error("unexpected backend in default chain")
                }
            }
        }

        try {
            mgr.initialize(preferredBackend = null) // [GPU, CPU]
            fail("Expected IllegalStateException all-backends-failed wrapper")
        } catch (_: IllegalStateException) {
            // expected
        }

        val failure = mgr.lastInitFailure as? InitFailure.BackendUnavailable
        assertNotNull("Expected BackendUnavailable categorisation", failure)
        assertEquals(
            "lastInitFailure must reflect the LATEST failure (CPU), not the first (GPU)",
            "CPU",
            failure!!.backend,
        )
        assertTrue(
            "safeLabel must start with the throwable's class name, was: ${failure.safeLabel}",
            failure.safeLabel.startsWith("IllegalStateException"),
        )
        assertTrue(
            "safeLabel must not contain the raw exception message — F-006",
            !failure.safeLabel.contains("cpu boom"),
        )
    }

    @Test
    fun `Successful CPU fallback after GPU failure preserves BackendUnavailable for GPU`() = runTest {
        // Dashboard relies on this: "GPU failed -> running on CPU" must
        // remain visible after the engine is up and serving requests.
        writeModelFile()
        stubAvailMem()

        val mgr = EngineManager(context, logRepository)
        mgr.engineFactory = { config ->
            when (config.backend) {
                is Backend.GPU -> mockk<Engine>(relaxed = true) {
                    every { initialize() } throws RuntimeException("GPU driver crash")
                }
                is Backend.CPU -> mockk<Engine>(relaxed = true) {
                    every { initialize() } returns Unit
                }
                else -> error("unexpected backend in default chain")
            }
        }

        mgr.initialize(preferredBackend = null) // GPU then CPU; CPU wins

        val failure = mgr.lastInitFailure as? InitFailure.BackendUnavailable
        assertNotNull(
            "GPU failure must survive CPU-fallback success so dashboard can render diagnostics",
            failure,
        )
        assertEquals("GPU", failure!!.backend)
        assertTrue(
            "safeLabel must start with the throwable's class name, was: ${failure.safeLabel}",
            failure.safeLabel.startsWith("RuntimeException"),
        )
        assertTrue(
            "safeLabel must not contain the raw exception message — F-006",
            !failure.safeLabel.contains("GPU driver crash"),
        )
        // Shim contract: lastGpuFailureReason resolves to the same safeLabel.
        assertEquals(failure.safeLabel, mgr.lastGpuFailureReason)
        assertEquals("CPU", mgr.currentBackend)
    }

    @Test
    fun `Fresh successful first-attempt init resets lastInitFailure to null`() = runTest {
        writeModelFile()
        stubAvailMem()

        val mgr = EngineManager(context, logRepository)
        // Seed a stale value to pin the reset-at-entry semantics.
        val initFailureField = EngineManager::class.java.getDeclaredField("lastInitFailure")
        initFailureField.isAccessible = true
        initFailureField.set(mgr, InitFailure.NativeError("StaleLeftover"))

        mgr.engineFactory = { _ ->
            mockk<Engine>(relaxed = true) {
                every { initialize() } returns Unit
            }
        }

        mgr.initialize(preferredBackend = "CPU")

        assertNull(
            "A fresh init that succeeds on the first attempt must clear stale failure state",
            mgr.lastInitFailure,
        )
        assertNull(mgr.lastGpuFailureReason)
    }

    // ---- ModelMissing ------------------------------------------------------

    @Test
    fun `Missing model file categorises lastInitFailure as ModelMissing`() = runTest {
        // No model file written → selectedModel lazy throws noModelFoundException
        stubAvailMem()

        val mgr = EngineManager(context, logRepository)
        try {
            mgr.initialize(preferredBackend = "CPU")
            fail("Expected IllegalStateException for missing model")
        } catch (e: IllegalStateException) {
            assertTrue(
                "Expected no-model-file message, got: ${e.message}",
                e.message!!.contains("No .litertlm"),
            )
        }

        assertEquals(InitFailure.ModelMissing, mgr.lastInitFailure)
        verify(exactly = 1) { logRepository.logInitFailureCategorized(InitFailure.ModelMissing) }
    }

    // ---- IntegrityMismatch -------------------------------------------------

    @Test
    fun `SHA-256 manifest mismatch categorises lastInitFailure as IntegrityMismatch`() = runTest {
        writeModelFile() // some content; on-disk hash will not match the bogus manifest
        stubAvailMem()

        val mgr = EngineManager(context, logRepository)
        // Pin a non-empty manifest with a hash that cannot match any
        // file (length is right, content is deliberately wrong).
        mgr.expectedModelSha256 = "0".repeat(64)

        try {
            mgr.initialize(preferredBackend = "CPU")
            fail("Expected SecurityException for integrity mismatch")
        } catch (e: SecurityException) {
            assertTrue(
                "Expected integrity check failure message, got: ${e.message}",
                e.message!!.contains("Model integrity check failed"),
            )
        }

        assertEquals(InitFailure.IntegrityMismatch, mgr.lastInitFailure)
        verify(exactly = 1) { logRepository.logInitFailureCategorized(InitFailure.IntegrityMismatch) }
    }

    @Test
    fun `Empty SHA-256 manifest does NOT categorise as IntegrityMismatch`() = runTest {
        // Default dev/CI build path — manifest empty, verifier early-returns
        // with a warning, no SecurityException, no categorisation.
        writeModelFile()
        stubAvailMem()

        val mgr = EngineManager(context, logRepository)
        mgr.expectedModelSha256 = "" // explicit, mirrors default
        mgr.engineFactory = { _ ->
            mockk<Engine>(relaxed = true) {
                every { initialize() } returns Unit
            }
        }

        mgr.initialize(preferredBackend = "CPU") // succeeds

        assertNull(
            "Empty manifest must not trigger IntegrityMismatch categorisation",
            mgr.lastInitFailure,
        )
        verify(exactly = 0) { logRepository.logInitFailureCategorized(InitFailure.IntegrityMismatch) }
    }

    // ---- F-006 safeLabel invariant ----------------------------------------

    @Test
    fun `BackendUnavailable safeLabel is class-name-only (no exception message leaks)`() = runTest {
        // F-006: native LiteRT-LM exception messages can embed prompt
        // fragments. The categorisation pipeline MUST go through
        // safeLabel() and never expose the raw `message` field.
        writeModelFile()
        stubAvailMem()

        val sensitiveMessage =
            "Tokenizer failure while processing prompt: 'My SSN is 123-45-6789'"

        val mgr = EngineManager(context, logRepository)
        mgr.engineFactory = { _ ->
            mockk<Engine>(relaxed = true) {
                every { initialize() } throws IllegalArgumentException(sensitiveMessage)
            }
        }

        try {
            mgr.initialize(preferredBackend = "CPU")
            fail("Expected IllegalStateException all-backends-failed wrapper")
        } catch (_: IllegalStateException) { /* expected */ }

        val failure = mgr.lastInitFailure as InitFailure.BackendUnavailable
        assertTrue(
            "safeLabel must start with the throwable's class name, was: ${failure.safeLabel}",
            failure.safeLabel.startsWith("IllegalArgumentException"),
        )
        assertTrue(
            "safeLabel must NOT include any of the sensitive message text — F-006",
            !failure.safeLabel.contains("SSN") &&
                !failure.safeLabel.contains("123-45-6789") &&
                !failure.safeLabel.contains("Tokenizer"),
        )
    }

    // ---- shim consistency --------------------------------------------------

    @Test
    fun `lastGpuFailureReason shim returns null when lastInitFailure is non-GPU BackendUnavailable`() = runTest {
        writeModelFile()
        stubAvailMem()

        val mgr = EngineManager(context, logRepository)
        mgr.engineFactory = { _ ->
            mockk<Engine>(relaxed = true) {
                every { initialize() } throws RuntimeException("CPU boom")
            }
        }

        try {
            mgr.initialize(preferredBackend = "CPU")
        } catch (_: IllegalStateException) { /* expected */ }

        // CPU failed, GPU was never tried → shim filter rejects.
        assertNull(
            "Shim must return null for non-GPU BackendUnavailable to preserve historical semantics",
            mgr.lastGpuFailureReason,
        )
        // But the typed signal IS available to consumers that want the new API.
        val failure = mgr.lastInitFailure as InitFailure.BackendUnavailable
        assertEquals("CPU", failure.backend)
    }

    @Test
    fun `lastGpuFailureReason shim returns null for non-BackendUnavailable variants`() = runTest {
        // Sanity: ModelMissing / LowMemory / IntegrityMismatch / NativeError
        // do not surface as `lastGpuFailureReason`. Pin via the
        // ModelMissing path (cheapest to set up).
        stubAvailMem()
        val mgr = EngineManager(context, logRepository)
        try {
            mgr.initialize(preferredBackend = "CPU")
        } catch (_: IllegalStateException) { /* expected */ }
        assertEquals(InitFailure.ModelMissing, mgr.lastInitFailure)
        assertNull(mgr.lastGpuFailureReason)
    }

    // ---- helpers -----------------------------------------------------------

    private fun writeModelFile() {
        val modelFile = File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME)
        java.io.RandomAccessFile(modelFile, "rw").use { raf ->
            raf.setLength(4096L)
        }
    }

    private fun stubAvailMem(availBytes: Long = 4L * 1024 * 1024 * 1024) {
        val slot = slot<ActivityManager.MemoryInfo>()
        every { activityManager.getMemoryInfo(capture(slot)) } answers {
            slot.captured.availMem = availBytes
            slot.captured.totalMem = 8L * 1024 * 1024 * 1024
            slot.captured.lowMemory = false
            Unit
        }
    }
}
