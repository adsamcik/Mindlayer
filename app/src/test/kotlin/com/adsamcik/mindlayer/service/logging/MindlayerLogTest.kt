package com.adsamcik.mindlayer.service.logging

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Tests for [MindlayerLog] structured logcat wrapper.
 */
class MindlayerLogTest {

    @Before
    fun setup() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun teardown() {
        unmockkAll()
    }

    // ── tag() ────────────────────────────────────────────────────────────

    @Test
    fun `tag prefixes component with Mindlayer dot`() {
        MindlayerLog.d("Engine", "hello")
        verify { Log.d("Mindlayer.Engine", any()) }
    }

    @Test
    fun `empty component produces Mindlayer dot tag`() {
        MindlayerLog.d("", "hello")
        verify { Log.d("Mindlayer.", any()) }
    }

    // ── format() ─────────────────────────────────────────────────────────

    @Test
    fun `format with both requestId and sessionId`() {
        MindlayerLog.d("X", "msg", requestId = "r1", sessionId = "s1")
        verify { Log.d("Mindlayer.X", "[req=r1 sess=s1] msg") }
    }

    @Test
    fun `format with only requestId`() {
        MindlayerLog.d("X", "msg", requestId = "r1")
        verify { Log.d("Mindlayer.X", "[req=r1] msg") }
    }

    @Test
    fun `format with only sessionId`() {
        MindlayerLog.d("X", "msg", sessionId = "s1")
        verify { Log.d("Mindlayer.X", "[sess=s1] msg") }
    }

    @Test
    fun `format with neither requestId nor sessionId returns plain message`() {
        MindlayerLog.d("X", "msg")
        verify { Log.d("Mindlayer.X", "msg") }
    }

    // ── d() ──────────────────────────────────────────────────────────────

    @Test
    fun `d calls Log_d with correct tag and formatted message`() {
        MindlayerLog.d("Decoder", "token ready", requestId = "r1")
        verify { Log.d("Mindlayer.Decoder", "[req=r1] token ready") }
    }

    // ── i() ──────────────────────────────────────────────────────────────

    @Test
    fun `i calls Log_i with correct tag and formatted message`() {
        MindlayerLog.i("Init", "engine loaded", sessionId = "s2")
        verify { Log.i("Mindlayer.Init", "[sess=s2] engine loaded") }
    }

    // ── w() ──────────────────────────────────────────────────────────────

    @Test
    fun `w without throwable calls Log_w with tag and message`() {
        MindlayerLog.w("Thermal", "throttling", requestId = "r3")
        verify { Log.w("Mindlayer.Thermal", "[req=r3] throttling") }
    }

    @Test
    fun `w with throwable calls Log_w with tag message and throwable`() {
        val ex = RuntimeException("boom")
        MindlayerLog.w("Thermal", "oops", requestId = "r4", throwable = ex)
        verify { Log.w("Mindlayer.Thermal", "[req=r4] oops", ex) }
    }

    // ── e() ──────────────────────────────────────────────────────────────

    @Test
    fun `e without throwable calls Log_e with tag and message`() {
        MindlayerLog.e("Fatal", "oom", sessionId = "s5")
        verify { Log.e("Mindlayer.Fatal", "[sess=s5] oom") }
    }

    @Test
    fun `e with throwable calls Log_e with tag message and throwable`() {
        val ex = OutOfMemoryError("heap")
        MindlayerLog.e("Fatal", "crash", requestId = "r6", throwable = ex)
        verify { Log.e("Mindlayer.Fatal", "[req=r6] crash", ex) }
    }

    // ── format edge cases ────────────────────────────────────────────────

    @Test
    fun `format strips trailing space before bracket when only requestId`() {
        // "req=r1 " → should become "req=r1]" not "req=r1 ]"
        MindlayerLog.i("X", "m", requestId = "r1")
        verify { Log.i("Mindlayer.X", "[req=r1] m") }
    }

    @Test
    fun `format preserves full context bracket pair`() {
        MindlayerLog.e("X", "m", requestId = "a", sessionId = "b")
        verify { Log.e("Mindlayer.X", "[req=a sess=b] m") }
    }

    // ── visibility: private helpers are tested indirectly ────────────────

    @Test
    fun `tag function returns PREFIX dot component`() {
        // Tested indirectly through any log call
        MindlayerLog.w("MyComponent", "test")
        verify { Log.w("Mindlayer.MyComponent", "test") }
    }

    @Test
    fun `format returns bracket-free string when both ids null`() {
        MindlayerLog.i("Z", "plain message")
        verify { Log.i("Mindlayer.Z", "plain message") }
    }

    // ── L11: sanitizeLogField ────────────────────────────────────────────

    @Test
    fun `sanitizeLogField returns 'null' for null input`() {
        assertEquals("null", sanitizeLogField(null))
    }

    @Test
    fun `sanitizeLogField passes through alphanumerics`() {
        assertEquals("abc123XYZ", sanitizeLogField("abc123XYZ"))
    }

    @Test
    fun `sanitizeLogField passes through allowed punctuation`() {
        assertEquals("a.b_c-d:e/f", sanitizeLogField("a.b_c-d:e/f"))
    }

    @Test
    fun `sanitizeLogField replaces newline and CR with underscore`() {
        assertEquals("ab__cd", sanitizeLogField("ab\r\ncd"))
    }

    @Test
    fun `sanitizeLogField replaces ESC and tab with underscore`() {
        assertEquals("a__b", sanitizeLogField("a\u001b\tb"))
    }

    @Test
    fun `sanitizeLogField caps at 64 chars`() {
        val input = "a".repeat(200)
        val out = sanitizeLogField(input)
        assertEquals(64, out.length)
        assertEquals("a".repeat(64), out)
    }

    @Test
    fun `format sanitizes requestId with newline`() {
        MindlayerLog.d("X", "msg", requestId = "r1\nINJECT")
        verify { Log.d("Mindlayer.X", "[req=r1_INJECT] msg") }
    }

    @Test
    fun `format sanitizes sessionId with control chars`() {
        MindlayerLog.i("X", "msg", sessionId = "s\u001b1")
        verify { Log.i("Mindlayer.X", "[sess=s_1] msg") }
    }
}
