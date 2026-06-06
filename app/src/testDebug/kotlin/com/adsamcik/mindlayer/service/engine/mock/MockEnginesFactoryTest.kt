package com.adsamcik.mindlayer.service.engine.mock

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Default-off guard for the DEBUG mock-engines seam.
 *
 * Even on a debuggable build, [mockEnginesOrNull] must return `null` unless the
 * `debug.mindlayer.mock_engines` system property is explicitly armed. In the
 * pure-JVM unit runtime the hidden `android.os.SystemProperties` read fails
 * closed, so the seam is off — proving a normal debug build (property unset)
 * behaves like production.
 */
class MockEnginesFactoryTest {

    @Test
    fun `mock engines are off by default`() {
        assertNull(mockEnginesOrNull(mockk<Context>()))
    }
}
