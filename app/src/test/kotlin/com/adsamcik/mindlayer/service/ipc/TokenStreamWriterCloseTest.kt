package com.adsamcik.mindlayer.service.ipc

import android.util.Log
import com.adsamcik.mindlayer.shared.StreamHeader
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream

/**
 * Pins SECURITY_REVIEW F-020 — `TokenStreamWriter.close()` must call
 * `output.close()` even when `output.flush()` throws a non-IOException.
 * The pre-fix code wrapped both in a single `catch (IOException)` so a
 * non-IOException slipped past and the underlying FD was leaked.
 */
class TokenStreamWriterCloseTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    /**
     * OutputStream that throws a non-IOException from `flush()` to
     * exercise the F-020 branch.
     */
    private class FlushThrowing : OutputStream() {
        var closed = false
            private set

        override fun write(b: Int) { /* ignore */ }
        override fun flush() {
            throw IllegalStateException("flush failed (not IOException)")
        }
        override fun close() {
            closed = true
        }
    }

    @Test
    fun `close still closes underlying stream when flush throws non-IOException`() {
        val out = FlushThrowing()
        val w = TokenStreamWriter.forTesting(out)
        w.close()
        assertTrue("close() must run even if flush() raised non-IOException", out.closed)
    }

    @Test
    fun `close is idempotent`() {
        val out = ByteArrayOutputStream()
        val w = TokenStreamWriter.forTesting(out)
        w.close()
        // Second close must not throw — closed flag is honoured.
        w.close()
    }
}
