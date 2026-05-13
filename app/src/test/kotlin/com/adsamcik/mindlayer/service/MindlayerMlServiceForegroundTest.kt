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
    fun `enterForeground logs foreground-start denial and continues`() {
        val service = Robolectric.buildService(MindlayerMlService::class.java).get()
        setField(service, "logRepository", mockk<LogRepository>(relaxed = true))
        mockkObject(MindlayerLog)
        mockkStatic(ServiceCompat::class)
        every {
            ServiceCompat.startForeground(any(), any(), any(), any())
        } throws ForegroundServiceStartNotAllowedException("denied")
        every { MindlayerLog.e(any(), any(), any(), any(), any()) } returns Unit
        every { MindlayerLog.i(any(), any(), any(), any()) } returns Unit

        service.enterForeground()

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
