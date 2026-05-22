package com.adsamcik.mindlayer.sdk.camerax

import androidx.camera.core.ImageProxy
import com.adsamcik.mindlayer.sdk.OcrSession
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrImageAnalyzerErrorPathTest {
    @Test fun `extraction failure emits typed error event instead of synthetic busy`() = runTest {
        val failure = IllegalStateException("closed image")
        val image = mockk<ImageProxy>(relaxed = true) {
            every { width } throws failure
        }
        var ackCalled = false
        val analyzer = OcrImageAnalyzer(
            session = mockk<OcrSession>(relaxed = true),
            scope = this,
            onAck = { _, _ -> ackCalled = true },
        )

        analyzer.analyze(image)

        val error = analyzer.events.first()
        assertTrue(error is OcrAnalyzerEvent.Error)
        assertSame(failure, (error as OcrAnalyzerEvent.Error).throwable)
        assertTrue("extraction failure must not be reported as a busy ack", !ackCalled)
        verify(exactly = 1) { image.close() }
    }
}
