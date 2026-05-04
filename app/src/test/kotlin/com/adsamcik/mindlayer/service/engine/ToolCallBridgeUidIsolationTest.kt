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
 * other's pending tool calls even when they reuse the same raw `requestId`.
 *
 * Post-merge note: [ToolCallBridge] now operates exclusively on a
 * pre-composed `scopedKey` of the form `"$uid:$requestId"`. Composition
 * happens in the binder (see `ServiceBinder.authorizeCall` →
 * `inferenceKey`); these tests recompose it directly so the bridge-level
 * invariant — "different scopedKeys never cross-pollinate" — is exercised
 * exactly the same way the binder will exercise it at runtime.
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

    private fun scopedKey(uid: Int, requestId: String): String = "$uid:$requestId"

    @Test
    fun `submitResult from foreign uid does not satisfy victim's pending call`() = runTest {
        val victimUid = 10101
        val attackerUid = 20202
        val sharedRequestId = "shared-id"
        val victimKey = scopedKey(victimUid, sharedRequestId)
        val attackerKey = scopedKey(attackerUid, sharedRequestId)

        val victimPending = bridge.registerPendingToolCalls(
            scopedKey = victimKey,
            toolCalls = listOf("search" to """{"q":"victim"}"""),
        )
        val victimCallId = victimPending.single().callId

        // Attacker submits a result under their own scopedKey (same raw
        // requestId, different uid). The bridge sees a fully unrelated key
        // and returns false because no pending entry exists there.
        val accepted = bridge.submitResult(
            scopedKey = attackerKey,
            callId = victimCallId,
            toolName = "search",
            resultJson = """{"answer":"attacker"}""",
        )
        assertFalse("Attacker key has no pending entry", accepted)

        // Victim's awaitResults must NOT complete from the attacker's submission.
        val victimResult = withTimeoutOrNull(50) {
            async { bridge.awaitResults(victimKey, timeoutMs = 1_000) }.await()
        }
        assertNull("Victim must not see attacker's result", victimResult)
    }

    @Test
    fun `submitResult from owning uid completes pending call`() = runTest {
        val key = scopedKey(30303, "r1")
        val pending = bridge.registerPendingToolCalls(key, listOf("calc" to """{"x":1}"""))
        val callId = pending.single().callId
        bridge.submitResult(key, callId = callId, toolName = "calc", resultJson = """42""")

        val results = bridge.awaitResults(key)
        assertEquals(1, results.size)
        assertEquals("calc" to "42", results[0])
    }

    @Test
    fun `cancel for one uid leaves another uid's pending intact`() = runTest {
        val keyA = scopedKey(10101, "r")
        val keyB = scopedKey(20202, "r")
        bridge.registerPendingToolCalls(keyA, listOf("a" to "{}"))
        val pendingB = bridge.registerPendingToolCalls(keyB, listOf("b" to "{}"))
        val callIdB = pendingB.single().callId

        bridge.cancel(keyA)

        // B's slot must still resolve.
        bridge.submitResult(keyB, callIdB, "b", """ok""")
        val res = bridge.awaitResults(keyB)
        assertEquals("ok", res.single().second)

        // A's slot must be gone — awaitResults throws because pending was removed.
        var attackerSawResults = false
        try {
            bridge.awaitResults(keyA, timeoutMs = 50)
            attackerSawResults = true
        } catch (_: IllegalStateException) {
            // expected — slot was removed by cancel
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // also acceptable — depends on race
        }
        assertFalse("Cancelled slot must not yield results", attackerSawResults)
    }
}
