package com.adsamcik.mindlayer.service.engine

import android.util.Log
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

/**
 * Pins SECURITY_REVIEW F-007 / F-008 — cross-UID hijack via shared
 * requestId / sessionId.
 *
 * Two UIDs that pick the same [requestId] must NOT collide in the
 * orchestrator's internal maps. We exercise [ToolCallBridge] directly
 * because the orchestrator-end keying is verified there end-to-end.
 */
class ScopedKeyIsolationTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `toolCallBridge keys by scopedKey not bare requestId`() = runTest {
        val bridge = ToolCallBridge()
        val callsA = bridge.registerPendingToolCalls(
            "1234:req-A", listOf("toolA" to "argsA"),
        )
        val callsB = bridge.registerPendingToolCalls(
            "5678:req-A", listOf("toolB" to "argsB"),
        )

        bridge.submitResult("5678:req-A", callsB[0].callId, "toolB", "ok-B")
        val resB = bridge.awaitResults("5678:req-A", timeoutMs = 1_000)
        assertEquals(1, resB.size)
        assertEquals("toolB" to "ok-B", resB[0])

        bridge.submitResult("1234:req-A", callsA[0].callId, "toolA", "ok-A")
        val resA = bridge.awaitResults("1234:req-A", timeoutMs = 1_000)
        assertEquals(1, resA.size)
        assertEquals("toolA" to "ok-A", resA[0])
    }

    @Test
    fun `toolCallBridge cancel of one scopedKey does not affect another`() = runTest {
        val bridge = ToolCallBridge()
        val callsA = bridge.registerPendingToolCalls(
            "1234:req-A", listOf("toolA" to "argsA"),
        )
        bridge.registerPendingToolCalls(
            "5678:req-A", listOf("toolB" to "argsB"),
        )

        bridge.cancel("5678:req-A")
        bridge.submitResult("1234:req-A", callsA[0].callId, "toolA", "ok-A")
        val resA = bridge.awaitResults("1234:req-A", timeoutMs = 1_000)
        assertEquals("ok-A", resA[0].second)
    }

    @Test
    fun `toolCallBridge pending entry is keyed by exact scopedKey`() {
        val bridge = ToolCallBridge()
        val regs = bridge.registerPendingToolCalls(
            "999:req-Z", listOf("foo" to "bar"),
        )
        assertEquals(1, regs.size)
        assertEquals("999:req-Z", regs[0].scopedKey)
    }
}
