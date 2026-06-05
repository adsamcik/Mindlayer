package com.adsamcik.mindlayer.service.ui

import android.view.WindowManager
import com.adsamcik.mindlayer.service.BuildConfig
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MainActivityTest {

    @Test
    fun `dashboard window applies FLAG_SECURE only in release builds`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java)
            .create()
            .get()

        val isSecure =
            activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0

        // FLAG_SECURE is gated to production (release) builds so debug builds stay
        // screenshottable for manual QA. The debug unit-test variant has
        // BuildConfig.DEBUG == true (flag absent); release has it false (flag set).
        assertEquals(!BuildConfig.DEBUG, isSecure)
    }
}
