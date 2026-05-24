package com.adsamcik.mindlayer.service.engine

import android.app.ActivityManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.util.Log
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * F-071: regression coverage for the low-memory hard-fail in
 * [EngineManager.initialize].
 *
 * The previous implementation logged a warning and proceeded into native
 * model load, which SIGABRT'd inside LiteRT-LM on 4–6 GB devices. The fix
 * raises [LowMemoryException] *before* any backend is attempted so the
 * SDK retry loop can stop instead of fanning out into a storm.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EngineManagerLowMemoryTest {

    private lateinit var context: Context
    private lateinit var activityManager: ActivityManager

    private lateinit var filesDir: File
    private lateinit var cacheDir: File

    // Tiny "model" file — content size is irrelevant, only the reported
    // length is consumed by the pre-flight check via `ModelInfo.sizeBytes`.
    // Keep small to avoid slow disk allocation on Windows test runs.
    private val modelSizeBytes: Long = 4096L

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

        val tmpRoot = File(System.getProperty("java.io.tmpdir"), "engine-low-mem-${System.nanoTime()}")
        filesDir = File(tmpRoot, "files").apply { mkdirs() }
        cacheDir = File(tmpRoot, "cache").apply { mkdirs() }

        // A "model" file the registry can pick up. Tiny content; the
        // pre-flight check only cares about `file.length()` (mapped to
        // `ModelInfo.sizeBytes`) — we exercise the comparison by stubbing
        // `availMem` low rather than by sizing the model up.
        val modelFile = File(filesDir, EngineManager.DEFAULT_MODEL_FILENAME)
        java.io.RandomAccessFile(modelFile, "rw").use { raf ->
            raf.setLength(modelSizeBytes)
        }

        activityManager = mockk(relaxed = true)
        val nativeLib = File(tmpRoot, "lib").apply { mkdirs() }
        // FLAG_DEBUGGABLE makes ModelRegistry.discoverModels() default to
        // requireIntegrity = false so the bogus 4 KB stub file is
        // accepted; otherwise PR #21's fail-closed default rejects it
        // before initialize() can reach the low-memory check. Mirrors
        // EngineManagerTest's setup.
        val appInfo = ApplicationInfo().apply {
            nativeLibraryDir = nativeLib.absolutePath
            flags = ApplicationInfo.FLAG_DEBUGGABLE
        }

        val assetManager = mockk<android.content.res.AssetManager>(relaxed = true) {
            every { list("") } returns emptyArray()
        }

        context = mockk(relaxed = true) {
            every { filesDir } returns this@EngineManagerLowMemoryTest.filesDir
            every { cacheDir } returns this@EngineManagerLowMemoryTest.cacheDir
            every { getExternalFilesDir(null) } returns null
            every { applicationInfo } returns appInfo
            every { assets } returns assetManager
            every { getSystemService(ActivityManager::class.java) } returns activityManager
        }
    }

    @After
    fun tearDown() {
        filesDir.parentFile?.deleteRecursively()
        unmockkAll()
    }

    @Test
    fun `initialize throws LowMemoryException when availMem is below model + 256MB on small device`() = runTest {
        // 50 MB available, 4 GB total — small-device path applies.
        // 50 MB < 4 KB model + 256 MB headroom = ~256 MB required → refuse.
        stubMemInfo(availBytes = 50L * 1024 * 1024, totalBytes = 4L * 1024 * 1024 * 1024)

        val mgr = EngineManager(context)
        try {
            mgr.initialize(preferredBackend = "CPU")
            fail("Expected LowMemoryException")
        } catch (e: LowMemoryException) {
            // 50 * 1024 * 1024 / 1024 / 1024 == 50
            assertEquals("availMb should match availMem/1MiB", 50L, e.availMb)
            // (4096 + 256 * 1024 * 1024) / 1024 / 1024 == 256
            assertEquals("requiredMb should be model + 256 (new headroom)", 256L, e.requiredMb)
            assertTrue(
                "exception message should embed both numbers",
                e.message!!.contains("availMb=50") && e.message!!.contains("requiredMb=256"),
            )
        }
    }

    @Test
    fun `initialize does not throw LowMemoryException when availMem comfortably exceeds required`() = runTest {
        // 4 GB available, 4 GB total — small-device path, well above
        // 4 KB model + 256 MB headroom.
        stubMemInfo(
            availBytes = 4L * 1024 * 1024 * 1024,
            totalBytes = 4L * 1024 * 1024 * 1024,
        )

        val mgr = EngineManager(context).apply {
            // Replace native engine with a no-op so we don't reach LiteRT-LM
            engineFactory = { mockk(relaxed = true) }
        }

        // Should not throw LowMemoryException — initialize() may still
        // surface other failures from the relaxed mock, but the pre-flight
        // gate itself MUST NOT fire.
        try {
            mgr.initialize(preferredBackend = "CPU")
        } catch (e: LowMemoryException) {
            fail("Did not expect LowMemoryException for 4 GB avail; got $e")
        } catch (_: Throwable) {
            // Other failures are acceptable — this test pins the gate, not
            // the full backend-fallback path.
        }
    }

    /**
     * F-079: large-device path. Reproduces the Samsung Galaxy S24 Ultra
     * failure mode (12 GB total, ~2.3 GB `availMem` because Linux uses
     * ~2 GB as reclaimable page cache). The legacy single-band check
     * refused engine init here; the new heuristic admits it because
     * `availMb` is above the 1 GB runway floor and `totalMb` has room
     * for the model plus a 1 GB system reserve.
     */
    @Test
    fun `initialize allows load on 12GB device with 2_3GB availMem (S24 scenario)`() = runTest {
        stubMemInfo(
            availBytes = 2348L * 1024 * 1024,
            totalBytes = 12L * 1024 * 1024 * 1024,
        )

        val mgr = EngineManager(context).apply {
            engineFactory = { mockk(relaxed = true) }
        }

        try {
            mgr.initialize(preferredBackend = "CPU")
        } catch (e: LowMemoryException) {
            fail(
                "Did not expect LowMemoryException for 12 GB device with 2.3 GB " +
                    "availMem (large-device cache-reclaim path); got $e",
            )
        } catch (_: Throwable) {
            // Other failures from the relaxed engine mock are acceptable.
        }
    }

    /**
     * F-079: large-device path with too little availMem. Even with 12 GB
     * total, if `availMem` drops below the 1 GB runway floor the gate
     * MUST refuse — there isn't enough breathing room to start the load
     * without immediate OOM-kill pressure.
     */
    @Test
    fun `initialize throws LowMemoryException on 12GB device when availMem below 1GB floor`() = runTest {
        stubMemInfo(
            availBytes = 512L * 1024 * 1024,
            totalBytes = 12L * 1024 * 1024 * 1024,
        )

        val mgr = EngineManager(context)
        try {
            mgr.initialize(preferredBackend = "CPU")
            fail("Expected LowMemoryException for 512 MB availMem on 12 GB device")
        } catch (e: LowMemoryException) {
            assertEquals(512L, e.availMb)
        }
    }

    private fun stubMemInfo(availBytes: Long, totalBytes: Long) {
        val slot = slot<ActivityManager.MemoryInfo>()
        every { activityManager.getMemoryInfo(capture(slot)) } answers {
            slot.captured.availMem = availBytes
            slot.captured.totalMem = totalBytes
            Unit
        }
    }
}
