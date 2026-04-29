package com.adsamcik.mindlayer.service.ipc

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.OutputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * H4 — verifies that [TokenStreamWriter] does not block forever when the
 * underlying [OutputStream] stalls. A stalled reader (dead client) must
 * surface as a [CancellationException] (the in-band cancellation signal
 * used by the inference coroutine) within the configured timeout.
 *
 * L6 — `closed` is `@Volatile`; we cannot directly assert visibility from a
 * unit test, but exercising close-from-another-thread plus the timeout path
 * gives reasonable coverage and pins the contract.
 */
class TokenStreamWriterTimeoutTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>(), any()) } returns 0
        every { Log.i(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `writeFrame surfaces cancellation when underlying stream stalls past timeout`() {
        val gate = CountDownLatch(1)
        val stalling = object : OutputStream() {
            override fun write(b: Int) {
                // Block forever — simulates a dead reader holding the pipe.
                gate.await()
            }
            override fun write(b: ByteArray) = write(b, 0, b.size)
            override fun write(b: ByteArray, off: Int, len: Int) {
                gate.await()
            }
        }
        val writer = TokenStreamWriter.forTesting(stalling, writeTimeoutMs = 200)

        val start = System.nanoTime()
        try {
            writer.writeHeader("req-1")
            fail("Expected CancellationException after write timeout")
        } catch (_: CancellationException) {
            // expected — pipe-write timeout converts to CE so the inference
            // coroutine unwinds promptly and triggers cancelProcess().
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000
        assertTrue(
            "Should bail out in ~200ms, took ${elapsedMs}ms",
            elapsedMs in 100..2_000,
        )

        // Subsequent writes must short-circuit (closed flag set).
        writer.writeTokenDelta(1, "ignored")

        // Release the stalled write so the executor's worker can finish.
        gate.countDown()
        writer.close()
    }

    @Test
    fun `closeWithError swallows pipe failure and does not throw`() {
        val gate = CountDownLatch(1)
        val stalling = object : OutputStream() {
            override fun write(b: Int) { gate.await() }
            override fun write(b: ByteArray, off: Int, len: Int) { gate.await() }
        }
        val writer = TokenStreamWriter.forTesting(stalling, writeTimeoutMs = 100)

        // closeWithError catches both IOException and CancellationException.
        writer.closeWithError(0, "client died")

        gate.countDown()
    }
}
