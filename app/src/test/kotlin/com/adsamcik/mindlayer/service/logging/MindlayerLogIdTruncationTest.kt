package com.adsamcik.mindlayer.service.logging

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * F-046: in non-debug builds, the formatted logcat line truncates request
 * and session IDs to the first 8 chars. The persisted Room entry continues
 * to carry the full IDs (asserted in `LogRepositoryTest`) so DB-side
 * cross-correlation is unaffected.
 */
class MindlayerLogIdTruncationTest {

    private val originalFlag = MindlayerLog.truncateIdsInLogcat

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        MindlayerLog.truncateIdsInLogcat = originalFlag
        unmockkAll()
    }

    @Test
    fun `non-debug truncates ids to 8 chars in formatted message`() {
        MindlayerLog.truncateIdsInLogcat = true
        val captured = slot<String>()
        every { Log.d("Mindlayer.Test", capture(captured)) } returns 0

        MindlayerLog.d(
            component = "Test",
            message = "hello",
            requestId = "abcdef0123456789-long-uuid",
            sessionId = "sess-deadbeef-cafe-1234",
        )

        assertEquals("[req=abcdef01 sess=sess-dea] hello", captured.captured)
    }

    @Test
    fun `debug emits full ids in formatted message`() {
        MindlayerLog.truncateIdsInLogcat = false
        val captured = slot<String>()
        every { Log.i("Mindlayer.Test", capture(captured)) } returns 0

        MindlayerLog.i(
            component = "Test",
            message = "hello",
            requestId = "abcdef0123456789",
            sessionId = "sess-1234567",
        )

        assertEquals("[req=abcdef0123456789 sess=sess-1234567] hello", captured.captured)
    }

    @Test
    fun `short ids are unchanged regardless of mode`() {
        MindlayerLog.truncateIdsInLogcat = true
        val captured = slot<String>()
        every { Log.d("Mindlayer.X", capture(captured)) } returns 0

        MindlayerLog.d("X", "msg", requestId = "r1", sessionId = "s1")

        assertEquals("[req=r1 sess=s1] msg", captured.captured)
    }

    @Test
    fun `messages without ids are unchanged regardless of mode`() {
        MindlayerLog.truncateIdsInLogcat = true
        val captured = slot<String>()
        every { Log.d("Mindlayer.Y", capture(captured)) } returns 0

        MindlayerLog.d("Y", "no-context")

        assertEquals("no-context", captured.captured)
    }

    @Test
    fun `truncation does not break tag emission`() {
        MindlayerLog.truncateIdsInLogcat = true
        MindlayerLog.w(
            component = "Engine",
            message = "warning",
            requestId = "0123456789abcdef",
        )
        verify { Log.w("Mindlayer.Engine", any<String>()) }
    }
}
