package com.adsamcik.mindlayer.service.engine

import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Pins the raw Y-plane read DoS guard (security review V-B).
 *
 * `MediaPartYPlaneExtractor.readExactlyAndClose` previously looped on a
 * blocking `read()` with no timeout, so an allowlisted caller could pass
 * a pipe/socket PFD, declare a large `payloadBytes`, and never write the
 * bytes — wedging a Binder thread forever. The watchdog must force-close
 * the FD and unwind the call within the configured budget.
 */
class MediaPartYPlaneReadWatchdogTest {

    private val originalTimeout = MediaPartYPlaneExtractor.rawYReadTimeoutMs

    @After
    fun restore() {
        MediaPartYPlaneExtractor.rawYReadTimeoutMs = originalTimeout
    }

    /** An InputStream that blocks in read() until [unblock] is closed. */
    private class StallingInputStream : InputStream() {
        val readEntered = CountDownLatch(1)
        private val released = CountDownLatch(1)
        val closed = AtomicBoolean(false)

        override fun read(): Int {
            readEntered.countDown()
            released.await()
            return -1
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
            readEntered.countDown()
            released.await()
            return -1
        }

        override fun close() {
            closed.set(true)
            released.countDown()
        }
    }

    @Test(timeout = 5_000)
    fun `watchdog unblocks a stalled read and force-closes the fd`() {
        MediaPartYPlaneExtractor.rawYReadTimeoutMs = 200L
        val stream = StallingInputStream()
        val forceClosed = AtomicBoolean(false)

        val start = System.nanoTime()
        try {
            MediaPartYPlaneExtractor.readExactlyWithWatchdog(stream, size = 1024) {
                forceClosed.set(true)
            }
            fail("expected the watchdog to abort the stalled read")
        } catch (t: Throwable) {
            // Either our wireError ("ended early or timed out") or an
            // IOException from the forced close — both are acceptable
            // "did not hang" outcomes.
        }
        val elapsedMs = (System.nanoTime() - start) / 1_000_000

        assertTrue("read entered before watchdog fired", stream.readEntered.count == 0L)
        assertTrue("forceClose callback invoked", forceClosed.get())
        assertTrue("stream closed by watchdog", stream.closed.get())
        assertTrue("aborted close to the timeout, not hung (was ${elapsedMs}ms)", elapsedMs < 2_000)
    }

    @Test(timeout = 5_000)
    fun `fully-fed stream reads exactly and does not trip the watchdog`() {
        MediaPartYPlaneExtractor.rawYReadTimeoutMs = 2_000L
        val payload = ByteArray(2048) { (it % 251).toByte() }
        val input: InputStream = ByteArrayInputStream(payload)
        val forceClosed = AtomicBoolean(false)

        val out = MediaPartYPlaneExtractor.readExactlyWithWatchdog(input, payload.size) {
            forceClosed.set(true)
        }

        assertArrayEquals(payload, out)
        assertTrue("watchdog must not force-close a healthy read", !forceClosed.get())
    }
}
