package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.util.Log
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * M-T1: when `bindService` *returns false* (rather than throwing
 * SecurityException) on a pre-API-31 device, the SDK must still surface the
 * typed `UNSUPPORTED_ANDROID_VERSION` error instead of leaving the state
 * machine silently `DISCONNECTED`. On Android <12 a cross-signer `<service>`
 * bind to Mindlayer can fail either path depending on OEM behaviour; we treat
 * both as the same terminal condition.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ConnectionManagerBindReturnsFalseTest {
    private lateinit var mgr: ConnectionManager
    private lateinit var context: Context
    private lateinit var appContext: Context

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        appContext = mockk(relaxed = true) {
            every { bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } returns false
        }
        context = mockk(relaxed = true) {
            every { applicationContext } returns appContext
        }
        mgr = ConnectionManager()
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `pre-31 bindService returning false surfaces typed unsupported android version`() {
        mgr.connect(context)

        // State must transition to terminal BIND_GAVE_UP synchronously inside connect()
        // — connect() drives doBind() on the calling thread, so by the time it returns
        // the bindService==false branch has already classified the failure.
        assertEquals(ConnectionState.BIND_GAVE_UP, mgr.state.value)

        val ex = assertThrows(MindlayerException::class.java) {
            runBlocking { mgr.awaitConnected(timeoutMs = 1_000) }
        }

        assertEquals(MindlayerErrorCode.UNSUPPORTED_ANDROID_VERSION, ex.code)
        assertEquals("UNSUPPORTED_ANDROID_VERSION", ex.codeName)
        assertTrue(ex.message!!.contains("requires Android 12 (API 31) or later"))
        assertTrue(ex.message!!.contains("running Android 30"))
        // bindService returning false has no underlying throwable.
        assertNull(ex.cause)
    }
}
