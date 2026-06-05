package com.adsamcik.mindlayer.service.security

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the privacy/security invariant that the DEBUG-only "auto-accept all
 * callers" bypass is physically absent from release builds: the backing store
 * and the adb receiver classes must not be on the release classpath, and the
 * release seam must always report the toggle as disabled.
 *
 * Registered in the `testReleaseUnitTest` filter in `app/build.gradle.kts`.
 */
class DebugAutoAcceptReleaseAbsenceTest {

    @Test fun `debug auto-accept store class is absent in release`() {
        val ex = runCatching {
            Class.forName("com.adsamcik.mindlayer.service.security.DebugAutoAcceptStore")
        }.exceptionOrNull()
        assertTrue(ex is ClassNotFoundException)
    }

    @Test fun `debug auto-accept receiver class is absent in release`() {
        val ex = runCatching {
            Class.forName("com.adsamcik.mindlayer.service.security.DebugAutoAcceptReceiver")
        }.exceptionOrNull()
        assertTrue(ex is ClassNotFoundException)
    }

    @Test fun `release seam reports the toggle as disabled`() {
        // The release seam ignores the context and never touches disk, so a
        // bare mock is sufficient. It must always be false. (We avoid calling
        // debugSetAutoAcceptAll here because its release no-op logs via
        // MindlayerLog → android.util.Log, which is not available in this
        // pure-JVM release unit test.)
        assertFalse(debugAutoAcceptAllEnabled(mockk<Context>()))
    }
}
