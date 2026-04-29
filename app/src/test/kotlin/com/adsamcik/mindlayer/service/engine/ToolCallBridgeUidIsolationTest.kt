package com.adsamcik.mindlayer.service.engine

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * H3a — verifies that two unrelated client uids cannot interfere with each
 * other's pending tool calls even when they reuse the same raw [requestId].
 */
class ToolCallBridgeUidIsolationTest {

    private lateinit var bridge: ToolCallBridge

    @Before
    fun setUp() {
        bridge = ToolCallBridge()
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any<String>()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkStatic(Log::class)
    }

    @Test
    fun `submitResult from foreign uid does not satisfy victim's pending call`() = runTest {
        val victimUid = 10101
        val attackerUid = 20202

        bridge.registerPendingToolCalls(
            uid = victimUid,
            requestId = "shared-id",
            toolCalls = listOf("search" to """{"q":"victim"}"""),
        )

        // Attacker submits a result against the same raw requestId.
        bridge.submitResult(
            uid = attackerUid,
            requestId = "shared-id",
            callId = null,
            toolName = "search",
            resultJson = """{"answer":"attacker"}""",
        )

        // Victim's awaitResults must NOT complete from the attacker's submission.
        val victimResult = withTimeoutOrNull(50) {
            async { bridge.awaitResults(victimUid, "shared-id", timeoutMs = 1_000) }.await()
        }
        assertNull("Victim must not see attacker's result", victimResult)
    }

    @Test
    fun `submitResult from owning uid completes pending call`() = runTest {
        val uid = 30303
        bridge.registerPendingToolCalls(uid, "r1", listOf("calc" to """{"x":1}"""))
        bridge.submitResult(uid, "r1", callId = null, toolName = "calc", resultJson = """42""")

        val results = bridge.awaitResults(uid, "r1")
        assertEquals(1, results.size)
        assertEquals("calc" to "42", results[0])
    }

    @Test
    fun `cancel for one uid leaves another uid's pending intact`() = runTest {
        val uidA = 10101
        val uidB = 20202
        bridge.registerPendingToolCalls(uidA, "r", listOf("a" to "{}"))
        bridge.registerPendingToolCalls(uidB, "r", listOf("b" to "{}"))

        bridge.cancel(uidA, "r")

        // B's slot must still resolve.
        bridge.submitResult(uidB, "r", null, "b", """ok""")
        val res = bridge.awaitResults(uidB, "r")
        assertEquals("ok", res.single().second)

        // A's slot must be gone — re-registering does not throw, and an
        // attacker awaiting A's old slot fails to find anything.
        var attackerSawResults = false
        try {
            bridge.awaitResults(uidA, "r", timeoutMs = 50)
            attackerSawResults = true
        } catch (_: IllegalStateException) {
            // expected — slot was removed by cancel
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // also acceptable — depends on race
        }
        assertFalse("Cancelled slot must not yield results", attackerSawResults)
    }
}
