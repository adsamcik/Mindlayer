package com.adsamcik.mindlayer.service

import android.app.ForegroundServiceStartNotAllowedException
import androidx.core.app.ServiceCompat
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MindlayerMlServiceForegroundTest {

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `enterForeground aborts (rethrows) and rolls back the refcount when promotion is denied`() {
        val service = Robolectric.buildService(MindlayerMlService::class.java).get()
        setField(service, "logRepository", mockk<LogRepository>(relaxed = true))
        mockkObject(MindlayerLog)
        mockkStatic(ServiceCompat::class)
        every {
            ServiceCompat.startForeground(any(), any(), any(), any())
        } throws ForegroundServiceStartNotAllowedException("denied")
        every { MindlayerLog.e(any(), any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.i(any(), any(), any(), any()) } returns Unit

        // R-16: promotion failure must NOT silently continue (running the
        // inference unprotected). It rethrows so the orchestrator aborts the
        // request with a typed error, and the foreground refcount is rolled
        // back so a later successful inference can re-promote cleanly.
        var thrown: Throwable? = null
        try {
            service.enterForeground()
        } catch (t: Throwable) {
            thrown = t
        }

        assertTrue(
            "enterForeground must rethrow the promotion failure",
            thrown is ForegroundServiceStartNotAllowedException,
        )
        assertEquals(
            "refcount must be rolled back to 0 after a denied promotion",
            0,
            service.activeInferenceCount,
        )
        verify {
            MindlayerLog.e(
                "MindlayerMlService",
                match { it.contains("Foreground service start not allowed") },
                null,
                null,
                null,
            )
        }
    }

    private fun setField(target: Any, name: String, value: Any) {
        val field = MindlayerMlService::class.java.getDeclaredField(name)
        field.isAccessible = true
        field.set(target, value)
    }
}
