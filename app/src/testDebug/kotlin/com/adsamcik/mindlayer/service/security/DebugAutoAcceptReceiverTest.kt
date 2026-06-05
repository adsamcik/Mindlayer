package com.adsamcik.mindlayer.service.security

import android.content.Context
import android.content.Intent
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies the adb/CI hook [DebugAutoAcceptReceiver] flips the same flag the
 * gate reads, and reports the resulting state via the broadcast result code.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DebugAutoAcceptReceiverTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context

    @Before fun setUp() {
        context = mockk(relaxed = true) {
            every { applicationContext } returns this
            every { filesDir } returns temp.root
        }
    }

    private fun deliver(intent: Intent) {
        DebugAutoAcceptReceiver().onReceive(context, intent)
    }

    @Test fun `broadcast with enabled true turns the flag on`() {
        deliver(Intent().putExtra("enabled", true))

        assertTrue(DebugAutoAcceptStore(context).isEnabled())
    }

    @Test fun `broadcast with enabled false turns the flag off`() {
        DebugAutoAcceptStore(context).setEnabled(true)

        deliver(Intent().putExtra("enabled", false))

        assertFalse(DebugAutoAcceptStore(context).isEnabled())
    }

    @Test fun `broadcast with no extra defaults to enabling`() {
        deliver(Intent())

        assertTrue(DebugAutoAcceptStore(context).isEnabled())
    }
}
