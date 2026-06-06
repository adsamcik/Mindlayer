package com.adsamcik.mindlayer.service.engine.mock

import android.content.Context
import io.mockk.mockk
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards the privacy/security invariant that the DEBUG-only "CI mock engines"
 * mode is physically absent from release builds: the concrete mock backend
 * classes must not be on the release classpath, and the release seam must
 * always return `null` (real engines wired unconditionally).
 *
 * Registered in the `testReleaseUnitTest` filter in `app/build.gradle.kts`.
 */
class MockEnginesReleaseAbsenceTest {

    @Test fun `mock embedding backend class is absent in release`() {
        assertClassAbsent("com.adsamcik.mindlayer.service.engine.mock.MockEmbeddingBackend")
    }

    @Test fun `mock paddleocr backend class is absent in release`() {
        assertClassAbsent("com.adsamcik.mindlayer.service.engine.mock.MockPaddleOcrBackend")
    }

    @Test fun `mock ocr-llm extractor class is absent in release`() {
        assertClassAbsent("com.adsamcik.mindlayer.service.engine.mock.MockOcrLlmExtractor")
    }

    @Test fun `release seam returns null`() {
        assertNull(mockEnginesOrNull(mockk<Context>()))
    }

    private fun assertClassAbsent(fqcn: String) {
        val ex = runCatching { Class.forName(fqcn) }.exceptionOrNull()
        assertTrue("$fqcn must be absent from the release classpath", ex is ClassNotFoundException)
    }
}
