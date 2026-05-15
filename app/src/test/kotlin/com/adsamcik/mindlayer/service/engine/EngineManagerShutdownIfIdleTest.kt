package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.res.AssetManager
import android.util.Log
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * M-E1: regression coverage for [EngineManager.shutdownIfIdle].
 *
 * The TRIM_COMPLETE-driven engine unload used to be split between a
 * `hasActiveStreaming()` check on a binder thread and a `shutdown()`
 * call inside a coroutine launch — wide open to a race where a new
 * inference arrived between the two. `shutdownIfIdle` closes the gap
 * by re-evaluating the caller-supplied predicate while holding the
 * engine mutex, so:
 *  - if the predicate returns `true`, the engine is unloaded; or
 *  - if it returns `false`, the unload is skipped without side effects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EngineManagerShutdownIfIdleTest {

    private lateinit var context: Context
    private lateinit var filesDir: File

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        mockkObject(MindlayerLog)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { MindlayerLog.d(any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.i(any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.w(any(), any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.e(any(), any(), any(), any(), any()) } returns Unit

        val tmpRoot = File(System.getProperty("java.io.tmpdir"), "shutdownifidle-${System.nanoTime()}")
        filesDir = File(tmpRoot, "files").apply { mkdirs() }
        val assetManager = mockk<AssetManager>(relaxed = true) {
            every { list("") } returns emptyArray()
        }
        val appInfo = ApplicationInfo().apply {
            nativeLibraryDir = filesDir.absolutePath
            flags = ApplicationInfo.FLAG_DEBUGGABLE
        }
        context = mockk(relaxed = true) {
            every { filesDir } returns this@EngineManagerShutdownIfIdleTest.filesDir
            every { getExternalFilesDir(null) } returns null
            every { cacheDir } returns filesDir
            every { applicationInfo } returns appInfo
            every { assets } returns assetManager
        }
    }

    @After
    fun tearDown() {
        filesDir.parentFile?.deleteRecursively()
        unmockkAll()
    }

    @Test
    fun `shutdownIfIdle skips when predicate returns false`() = runTest {
        val mgr = EngineManager(context, logRepository = mockk<LogRepository>(relaxed = true))
        val unloaded = mgr.shutdownIfIdle { false }
        assertFalse("predicate=false must NOT unload", unloaded)
        // No active engine ever existed, but the contract is that the
        // method returns false without throwing — the assertion is on
        // the return value, not on side effects.
    }

    @Test
    fun `shutdownIfIdle invokes predicate exactly once under the mutex`() = runTest {
        val mgr = EngineManager(context, logRepository = mockk<LogRepository>(relaxed = true))
        val invocations = AtomicInteger(0)
        mgr.shutdownIfIdle {
            invocations.incrementAndGet()
            true
        }
        // Exactly one call: no retry loop, no double-evaluation.
        assertTrue(invocations.get() == 1)
    }

    @Test
    fun `shutdownIfIdle returns true when predicate is true even without prior init`() = runTest {
        val mgr = EngineManager(context, logRepository = mockk<LogRepository>(relaxed = true))
        // Engine is in Idle state with no engine handle. The contract is
        // that shutdownIfIdle still completes (no-op teardown of a null
        // engine) and returns `true` since the predicate held.
        val unloaded = mgr.shutdownIfIdle { true }
        assertTrue(unloaded)
    }
}
