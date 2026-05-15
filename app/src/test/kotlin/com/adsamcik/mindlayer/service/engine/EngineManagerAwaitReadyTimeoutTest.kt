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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * H-E2: regression coverage for the [EngineManager.awaitReady] timeout.
 *
 * Before this change, a wedged native init (driver hang, blocked load)
 * left every queued binder thread parked in `state.first { Ready | Failed }`
 * forever. Now, each waiter gets a typed [EngineState.Failed] carrying
 * [InitFailure.NativeError] ("init timeout") after the per-call timeout
 * elapses, so the SDK can surface a deterministic `engine_load_failed`
 * instead of hanging.
 *
 * The in-flight init job is intentionally left running on timeout — a
 * slow-but-eventual success still rearms the engine for later callers.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EngineManagerAwaitReadyTimeoutTest {

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

        val tmpRoot = File(System.getProperty("java.io.tmpdir"), "awaitready-${System.nanoTime()}")
        filesDir = File(tmpRoot, "files").apply { mkdirs() }
        val assetManager = mockk<AssetManager>(relaxed = true) {
            every { list("") } returns emptyArray()
        }
        val appInfo = ApplicationInfo().apply {
            nativeLibraryDir = filesDir.absolutePath
            flags = ApplicationInfo.FLAG_DEBUGGABLE
        }
        context = mockk(relaxed = true) {
            every { filesDir } returns this@EngineManagerAwaitReadyTimeoutTest.filesDir
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
    fun `awaitReady with short timeout while Initializing returns typed NativeError`() = runTest {
        val mgr = EngineManager(context, logRepository = mockk<LogRepository>(relaxed = true))
        // The state starts as Idle. awaitReady() immediately returns Idle?
        // No — the impl only short-circuits on Ready/Failed. From Idle it
        // suspends until terminal or timeout. We exercise that path here.
        val result = mgr.awaitReady(timeoutMs = 25L)
        assertTrue(
            "Expected Failed on timeout, got $result",
            result is EngineState.Failed,
        )
        val cause = (result as EngineState.Failed).cause
        assertTrue(
            "Expected typed NativeError cause, got $cause",
            cause is InitFailure.NativeError,
        )
        assertEquals("init timeout", (cause as InitFailure.NativeError).message)
    }

    @Test
    fun `awaitReady default constant matches contract`() {
        // Hard guard against silent edits to the default — the timeout
        // shows up in SDK retry math, dashboards, and the FGS budget.
        assertEquals(30_000L, EngineManager.DEFAULT_AWAIT_READY_TIMEOUT_MS)
    }
}
