package com.adsamcik.mindlayer.service.security

import org.junit.Assert.assertTrue
import org.junit.Test

class DebugAllowlistSeederReleaseAbsenceTest {
    @Test
    fun debug_seeder_class_is_absent_in_release() {
        val exception = runCatching {
            Class.forName("com.adsamcik.mindlayer.service.security.DebugAllowlistSeeder")
        }.exceptionOrNull()
        assertTrue(exception is ClassNotFoundException)
    }
}
