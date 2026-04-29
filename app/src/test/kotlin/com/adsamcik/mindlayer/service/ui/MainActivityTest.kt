package com.adsamcik.mindlayer.service.ui

import android.view.WindowManager
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityTest {

    @Test
    fun `dashboard window uses FLAG_SECURE`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .get()

        assertTrue(
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0,
        )
    }
}
