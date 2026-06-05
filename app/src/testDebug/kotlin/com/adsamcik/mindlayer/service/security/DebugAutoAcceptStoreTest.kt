package com.adsamcik.mindlayer.service.security

import android.content.Context
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DebugAutoAcceptStoreTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context

    @Before fun setUp() {
        context = mockk(relaxed = true) {
            every { applicationContext } returns this
            every { filesDir } returns temp.root
        }
    }

    @Test fun `default is disabled`() {
        assertFalse(DebugAutoAcceptStore(context).isEnabled())
    }

    @Test fun `enable then disable round-trips and persists across instances`() {
        assertTrue(DebugAutoAcceptStore(context).setEnabled(true))
        // A fresh instance (e.g. the gate in another process) sees the flag.
        assertTrue(DebugAutoAcceptStore(context).isEnabled())

        assertFalse(DebugAutoAcceptStore(context).setEnabled(false))
        assertFalse(DebugAutoAcceptStore(context).isEnabled())
    }

    @Test fun `disabling when already disabled is idempotent`() {
        val store = DebugAutoAcceptStore(context)
        assertFalse(store.setEnabled(false))
        assertFalse(store.isEnabled())
    }
}
