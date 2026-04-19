package com.adsamcik.mindlayer.service.engine

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.verify
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class ToolCallBridgeTest {

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

    // ─── Basic flow ────────────────────────────────────────────────────

    @Test
    fun `register tool calls returns pending calls with correct metadata`() {
        val calls = bridge.registerPendingToolCalls(
            "req-1",
            listOf("calculator" to """{"expr":"1+1"}"""),
        )
        assertEquals(1, calls.size)
        assertEquals("req-1", calls[0].requestId)
        assertEquals("calculator", calls[0].toolName)
        assertEquals("""{"expr":"1+1"}""", calls[0].arguments)
        assertFalse(calls[0].resultDeferred.isCompleted)
    }

    @Test
    fun `single tool call register-submit-await roundtrip`() = runTest {
        bridge.registerPendingToolCalls("req-1", listOf("search" to """{"q":"hello"}"""))
        bridge.submitResult("req-1", "search", """{"answer":"world"}""")

        val results = bridge.awaitResults("req-1")
        assertEquals(1, results.size)
        assertEquals("search" to """{"answer":"world"}""", results[0])
    }

    @Test
    fun `register multiple tool calls and submit all returns all in registration order`() = runTest {
        bridge.registerPendingToolCalls(
            "req-1",
            listOf(
                "toolA" to "argsA",
                "toolB" to "argsB",
                "toolC" to "argsC",
            ),
        )

        bridge.submitResult("req-1", "toolA", "resultA")
        bridge.submitResult("req-1", "toolB", "resultB")
        bridge.submitResult("req-1", "toolC", "resultC")

        val results = bridge.awaitResults("req-1")
        assertEquals(
            listOf("toolA" to "resultA", "toolB" to "resultB", "toolC" to "resultC"),
            results,
        )
    }

    @Test
    fun `each registered call gets a unique callId`() {
        val calls = bridge.registerPendingToolCalls(
            "req-1",
            listOf("t" to "a", "t" to "b", "t" to "c"),
        )
        val ids = calls.map { it.callId }.toSet()
        assertEquals(3, ids.size)
    }

    // ─── Concurrent behavior ───────────────────────────────────────────

    @Test
    fun `submit result from different coroutine while another awaits`() = runTest {
        bridge.registerPendingToolCalls("req-1", listOf("tool" to "args"))

        val awaiting = async { bridge.awaitResults("req-1") }

        launch {
            bridge.submitResult("req-1", "tool", "result-from-other-coroutine")
        }

        val results = awaiting.await()
        assertEquals(listOf("tool" to "result-from-other-coroutine"), results)
    }

    @Test
    fun `submit result before await is called still resolves`() = runTest {
        bridge.registerPendingToolCalls("req-1", listOf("tool" to "args"))
        bridge.submitResult("req-1", "tool", "early-result")

        val results = bridge.awaitResults("req-1")
        assertEquals(listOf("tool" to "early-result"), results)
    }

    @Test
    fun `multiple requests registered simultaneously do not interfere`() = runTest {
        bridge.registerPendingToolCalls("req-A", listOf("toolX" to "a1"))
        bridge.registerPendingToolCalls("req-B", listOf("toolY" to "b1"))

        bridge.submitResult("req-A", "toolX", "resultA")
        bridge.submitResult("req-B", "toolY", "resultB")

        val resA = bridge.awaitResults("req-A")
        val resB = bridge.awaitResults("req-B")

        assertEquals(listOf("toolX" to "resultA"), resA)
        assertEquals(listOf("toolY" to "resultB"), resB)
    }

    // ─── Edge cases ────────────────────────────────────────────────────

    @Test
    fun `submitResult for non-existent requestId does not crash and logs warning`() {
        bridge.submitResult("ghost-request", "tool", "result")

        verify { Log.w("Mindlayer.ToolCallBridge", match<String> { it.contains("ghost-request") }) }
    }

    @Test
    fun `submitResult for non-existent tool name does not crash and logs warning`() {
        bridge.registerPendingToolCalls("req-1", listOf("realTool" to "args"))
        bridge.submitResult("req-1", "nonexistentTool", "result")

        verify { Log.w("Mindlayer.ToolCallBridge", match<String> { it.contains("nonexistentTool") }) }
    }

    @Test
    fun `submitResult for already-completed tool call is ignored`() = runTest {
        bridge.registerPendingToolCalls("req-1", listOf("tool" to "args"))
        bridge.submitResult("req-1", "tool", "first")
        bridge.submitResult("req-1", "tool", "second") // should be ignored

        val results = bridge.awaitResults("req-1")
        assertEquals(listOf("tool" to "first"), results)
    }

    @Test(expected = IllegalStateException::class)
    fun `await with no pending calls throws IllegalStateException`() = runTest {
        bridge.awaitResults("nonexistent")
    }

    @Test
    fun `register overwrites previous pending calls for same requestId`() = runTest {
        bridge.registerPendingToolCalls("req-1", listOf("oldTool" to "oldArgs"))
        bridge.registerPendingToolCalls("req-1", listOf("newTool" to "newArgs"))

        bridge.submitResult("req-1", "newTool", "newResult")

        val results = bridge.awaitResults("req-1")
        assertEquals(listOf("newTool" to "newResult"), results)
    }

    @Test
    fun `empty tool call list registration returns empty and await resolves empty`() = runTest {
        val calls = bridge.registerPendingToolCalls("req-1", emptyList())
        assertTrue(calls.isEmpty())

        val results = bridge.awaitResults("req-1")
        assertTrue(results.isEmpty())
    }

    // ─── Cancellation ──────────────────────────────────────────────────

    @Test
    fun `cancel removes pending calls`() {
        bridge.registerPendingToolCalls("req-1", listOf("tool" to "args"))
        bridge.cancel("req-1")

        // awaitResults should fail because pending is removed
        try {
            kotlinx.coroutines.test.runTest { bridge.awaitResults("req-1") }
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("req-1"))
        }
    }

    @Test
    fun `cancel completes deferreds with cancellation`() {
        val calls = bridge.registerPendingToolCalls("req-1", listOf("tool" to "args"))
        bridge.cancel("req-1")

        assertTrue(calls[0].resultDeferred.isCancelled)
    }

    @Test
    fun `cancel for non-existent request does not crash`() {
        bridge.cancel("never-registered") // should not throw
    }

    @Test
    fun `await after cancel throws IllegalStateException`() = runTest {
        bridge.registerPendingToolCalls("req-1", listOf("tool" to "args"))
        bridge.cancel("req-1")

        try {
            bridge.awaitResults("req-1")
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("req-1"))
        }
    }

    @Test
    fun `cancel completes multiple deferreds`() {
        val calls = bridge.registerPendingToolCalls(
            "req-1",
            listOf("a" to "1", "b" to "2", "c" to "3"),
        )
        bridge.cancel("req-1")

        calls.forEach { assertTrue(it.resultDeferred.isCancelled) }
    }

    // ─── Timeout ───────────────────────────────────────────────────────

    @Test(expected = TimeoutCancellationException::class)
    fun `await with very short timeout throws TimeoutCancellationException`() = runTest {
        bridge.registerPendingToolCalls("req-1", listOf("tool" to "args"))
        bridge.awaitResults("req-1", timeoutMs = 1L)
    }

    @Test
    fun `await succeeds when results arrive before timeout`() = runTest {
        bridge.registerPendingToolCalls("req-1", listOf("tool" to "args"))
        bridge.submitResult("req-1", "tool", "fast-result")

        val results = bridge.awaitResults("req-1", timeoutMs = 5_000L)
        assertEquals(listOf("tool" to "fast-result"), results)
    }

    @Test
    fun `timeout cleans up pending state`() = runTest {
        bridge.registerPendingToolCalls("req-1", listOf("tool" to "args"))

        try {
            bridge.awaitResults("req-1", timeoutMs = 1L)
            fail("Expected TimeoutCancellationException")
        } catch (_: TimeoutCancellationException) {
            // expected
        }

        // Pending should be cleaned up by the finally block
        try {
            bridge.awaitResults("req-1")
            fail("Expected IllegalStateException after timeout cleanup")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("req-1"))
        }
    }

    // ─── Multiple tool calls matching ──────────────────────────────────

    @Test
    fun `two calls with same tool name - submit matches first unfinished`() = runTest {
        bridge.registerPendingToolCalls(
            "req-1",
            listOf("search" to "query1", "search" to "query2"),
        )

        bridge.submitResult("req-1", "search", "result-for-first")
        bridge.submitResult("req-1", "search", "result-for-second")

        val results = bridge.awaitResults("req-1")
        assertEquals(
            listOf("search" to "result-for-first", "search" to "result-for-second"),
            results,
        )
    }

    @Test
    fun `submit results in different order than registration`() = runTest {
        bridge.registerPendingToolCalls(
            "req-1",
            listOf("alpha" to "a", "beta" to "b", "gamma" to "g"),
        )

        // Submit in reverse order
        bridge.submitResult("req-1", "gamma", "result-g")
        bridge.submitResult("req-1", "alpha", "result-a")
        bridge.submitResult("req-1", "beta", "result-b")

        val results = bridge.awaitResults("req-1")
        // Results should be in registration order, not submission order
        assertEquals(
            listOf(
                "alpha" to "result-a",
                "beta" to "result-b",
                "gamma" to "result-g",
            ),
            results,
        )
    }

    @Test
    fun `three calls with same name submitted one at a time match sequentially`() = runTest {
        val calls = bridge.registerPendingToolCalls(
            "req-1",
            listOf("fetch" to "url1", "fetch" to "url2", "fetch" to "url3"),
        )

        bridge.submitResult("req-1", "fetch", "res1")
        assertTrue(calls[0].resultDeferred.isCompleted)
        assertFalse(calls[1].resultDeferred.isCompleted)
        assertFalse(calls[2].resultDeferred.isCompleted)

        bridge.submitResult("req-1", "fetch", "res2")
        assertTrue(calls[1].resultDeferred.isCompleted)
        assertFalse(calls[2].resultDeferred.isCompleted)

        bridge.submitResult("req-1", "fetch", "res3")
        assertTrue(calls[2].resultDeferred.isCompleted)

        val results = bridge.awaitResults("req-1")
        assertEquals(
            listOf("fetch" to "res1", "fetch" to "res2", "fetch" to "res3"),
            results,
        )
    }

    // ─── Await cleanup ─────────────────────────────────────────────────

    @Test
    fun `successful await removes entry from pending map`() = runTest {
        bridge.registerPendingToolCalls("req-1", listOf("tool" to "args"))
        bridge.submitResult("req-1", "tool", "result")
        bridge.awaitResults("req-1")

        // Second await should fail because the first cleaned up
        try {
            bridge.awaitResults("req-1")
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            assertTrue(e.message!!.contains("req-1"))
        }
    }

    @Test
    fun `awaitResults with deferred completed from separate launch`() = runTest {
        bridge.registerPendingToolCalls(
            "req-1",
            listOf("slow" to "args1", "fast" to "args2"),
        )

        val awaiter = async { bridge.awaitResults("req-1", timeoutMs = 10_000L) }

        launch {
            bridge.submitResult("req-1", "fast", "fast-result")
            bridge.submitResult("req-1", "slow", "slow-result")
        }

        val results = awaiter.await()
        assertEquals(
            listOf("slow" to "slow-result", "fast" to "fast-result"),
            results,
        )
    }

    @Test
    fun `concurrent registrations and submissions across many requests`() = runTest {
        val requestIds = (1..10).map { "req-$it" }

        requestIds.forEach { id ->
            bridge.registerPendingToolCalls(id, listOf("tool" to "args-$id"))
        }

        requestIds.forEach { id ->
            bridge.submitResult(id, "tool", "result-$id")
        }

        requestIds.forEach { id ->
            val results = bridge.awaitResults(id)
            assertEquals(listOf("tool" to "result-$id"), results)
        }
    }

    @Test
    fun `partial submit then cancel leaves no pending state`() {
        bridge.registerPendingToolCalls(
            "req-1",
            listOf("a" to "1", "b" to "2"),
        )
        bridge.submitResult("req-1", "a", "done-a")
        bridge.cancel("req-1")

        // Nothing should be pending
        try {
            kotlinx.coroutines.test.runTest { bridge.awaitResults("req-1") }
            fail("Expected IllegalStateException")
        } catch (e: IllegalStateException) {
            // expected
        }
    }

    @Test
    fun `default timeout constant is 60 seconds`() {
        assertEquals(60_000L, ToolCallBridge.DEFAULT_TIMEOUT_MS)
    }
}
