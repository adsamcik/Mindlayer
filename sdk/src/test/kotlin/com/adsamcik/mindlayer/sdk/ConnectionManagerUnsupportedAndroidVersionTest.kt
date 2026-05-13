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
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [30])
class ConnectionManagerUnsupportedAndroidVersionTest {
    private lateinit var mgr: ConnectionManager
    private lateinit var context: Context
    private lateinit var appContext: Context

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
        appContext = mockk(relaxed = true) {
            every { bindService(any<Intent>(), any<ServiceConnection>(), any<Int>()) } throws
                SecurityException("Permission Denial: bindService")
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
    fun `pre-31 bind SecurityException surfaces typed unsupported android version`() {
        mgr.connect(context)

        val ex = assertThrows(MindlayerException::class.java) {
            runBlocking { mgr.awaitConnected(timeoutMs = 100) }
        }

        assertEquals(MindlayerErrorCode.UNSUPPORTED_ANDROID_VERSION, ex.code)
        assertEquals("UNSUPPORTED_ANDROID_VERSION", ex.codeName)
        assertTrue(ex.message!!.contains("requires Android 12 (API 31) or later"))
        assertTrue(ex.message!!.contains("running Android 30"))
    }
}