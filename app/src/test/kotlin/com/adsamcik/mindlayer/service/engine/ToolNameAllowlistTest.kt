package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Log
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.service.MindlayerMlService
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.ipc.TokenStreamWriter
import com.adsamcik.mindlayer.service.logging.LogCategory
import com.adsamcik.mindlayer.service.logging.LogEntry
import com.adsamcik.mindlayer.service.logging.LogEvent
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.testutil.TestPipeHelper
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.ToolCall
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CopyOnWriteArrayList

/**
 * F-036: regression coverage for the orchestrator's tool-name allowlist
 * and arg-size cap. The orchestrator must:
 *
 *  - drop model-emitted tool calls whose `name` is not in the session's
 *    declared tool set (logged as [LogEvent.TOOL_CALL_REJECTED] under
 *    [LogCategory.SECURITY]),
 *  - truncate `gson.toJson(arguments)` to [InferenceOrchestrator.MAX_TOOL_ARGS_LEN]
 *    when oversize, and
 *  - apply the same filter on the first-pass `chunk.toolCalls` AND the
 *    tool-call-loop continuation.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ToolNameAllowlistTest {

    private lateinit var context: Context
    private lateinit var engineManager: EngineManager
    private lateinit var memoryBudget: MemoryBudget
    private lateinit var sharedMemoryPool: SharedMemoryPool
    private lateinit var service: MindlayerMlService
    private lateinit var mockEngine: Engine
    private lateinit var mockConversation: Conversation
    private lateinit var sessionManager: SessionManager
    private lateinit var orchestrator: InferenceOrchestrator
    private lateinit var logRepository: LogRepository
    private val capturedLogs = CopyOnWriteArrayList<LogEntry>()
    private val outputStreamQueue = ConcurrentLinkedQueue<ByteArrayOutputStream>()

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        mockkStatic(SystemClock::class)
        every { SystemClock.elapsedRealtime() } returns 100_000L
        every { Log.v(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockConversation = mockk(relaxed = true)
        mockEngine = mockk(relaxed = true) {
            every { createConversation(any()) } returns mockConversation
        }
        engineManager = mockk(relaxed = true) {
            every { requireEngine() } returns mockEngine
            every { currentBackend } returns "GPU"
            every { isInitialized } returns true
        }
        val tier = DeviceTier(
            maxSessions = 6,
            defaultMaxTokens = 4096,
            maxMaxTokens = 8192,
            deviceRamMb = 16 * 1024L,
        )
        memoryBudget = mockk(relaxed = true) {
            every { deviceTier } returns tier
            every { currentSnapshot() } returns MemorySnapshot(
                availableMb = 4000L,
                totalMb = 16 * 1024L,
                lowMemory = false,
                pressure = MemoryPressure.NORMAL,
                recommendedMaxTokens = 8192,
            )
        }
        sharedMemoryPool = mockk(relaxed = true) {
            every { cleanup(any()) } returns Unit
            every { cleanupAll() } returns Unit
        }
        service = mockk(relaxed = true) {
            every { enterForeground() } returns Unit
            every { exitForeground() } returns Unit
            every { engineManager } returns this@ToolNameAllowlistTest.engineManager
        }
        context = mockk(relaxed = true)
        capturedLogs.clear()
        logRepository = mockk(relaxed = true) {
            every { log(any()) } answers {
                capturedLogs.add(firstArg())
                Unit
            }
        }
        sessionManager = SessionManager(context, engineManager, memoryBudget, logRepository)
        outputStreamQueue.clear()
        orchestrator = InferenceOrchestrator(
            service, sessionManager, sharedMemoryPool,
            logRepository = logRepository,
            writerFactory = { _ ->
                val out = outputStreamQueue.poll() ?: ByteArrayOutputStream()
                TokenStreamWriter.forTesting(out, writeTimeoutMs = 2_000L)
            },
        )
    }

    @After
    fun tearDown() {
        orchestrator.shutdown()
        unmockkAll()
    }

    private fun chunk(
        text: String? = null,
        calls: List<Pair<String, Map<String, Any>>> = emptyList(),
    ): Message = mockk {
        val parts: List<Content> = if (text.isNullOrEmpty()) emptyList()
        else listOf(mockk<Content.Text> inner@{ every { this@inner.text } returns text })
        every { contents } returns mockk { every { contents } returns parts }
        every { toolCalls } returns calls.map { (name, args) ->
            mockk<ToolCall> {
                every { this@mockk.name } returns name
                every { arguments } returns args
            }
        }
    }

    @Test
    fun `unknown tool name from first-pass chunk is dropped and logged`() {
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns flow {
            emit(chunk(calls = listOf("delete_account" to mapOf("confirm" to true))))
        }
        val sid = sessionManager.createSession(
            SessionConfig(
                maxTokens = 2048,
                toolsJson = """[{"name":"weather","description":"d"}]""",
            )
        )
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val out = ByteArrayOutputStream()
        outputStreamQueue.add(out)
        orchestrator.infer(
            "100:req-drop",
            RequestMeta(requestId = "req-drop", sessionId = sid, textContent = "hi"),
            image = null, audio = null, pipeWriteEnd = pfd,
        )
        // Wait for runInference to start (avoid the start/finish race
        // where activeRequestId is null both pre- and post-inference).
        val startDeadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < startDeadline &&
            sessionManager.activeRequestIdForSession(sid) == null
        ) {
            Thread.sleep(10)
        }
        val endDeadline = System.currentTimeMillis() + 8_000L
        while (System.currentTimeMillis() < endDeadline) {
            if (sessionManager.activeRequestIdForSession(sid) == null) {
                Thread.sleep(50)
                break
            }
            Thread.sleep(25)
        }

        val events = TestPipeHelper.parseFrames(
            TestPipeHelper.readFrames(ByteArrayInputStream(out.toByteArray()))
        )
        // Dropped tool must not yield a tool_call frame.
        val toolCalls = events.filter { it.kind == "tool_call" }
        assertTrue("Expected no tool_call frames; got $toolCalls", toolCalls.isEmpty())

        val rejected = capturedLogs.filter {
            it.category == LogCategory.SECURITY && it.event == LogEvent.TOOL_CALL_REJECTED
        }
        assertTrue(
            "Expected at least one TOOL_CALL_REJECTED log entry, got ${capturedLogs.size}",
            rejected.isNotEmpty(),
        )
        assertTrue(
            "log payload must include safe metadata",
            rejected.any { it.extraJson?.contains("unknown_tool") == true && it.extraJson.contains("hash8") },
        )
        assertFalse(
            "raw model-emitted tool name must not be persisted",
            rejected.any { it.extraJson?.contains("delete_account") == true },
        )
    }

    @Test
    fun `unknown tool name from continuation chunk is dropped`() {
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns flow {
            emit(chunk(calls = listOf("weather" to mapOf("city" to "Prague"))))
        }
        every { mockConversation.sendMessage(any<Message>()) } returns
            chunk(text = "done", calls = listOf("delete_account" to mapOf("confirm" to true)))

        val sid = sessionManager.createSession(
            SessionConfig(
                maxTokens = 2048,
                toolsJson = """[{"name":"weather","description":"d"}]""",
            )
        )

        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val out = ByteArrayOutputStream()
        outputStreamQueue.add(out)
        orchestrator.infer(
            "100:req-cont",
            RequestMeta(requestId = "req-cont", sessionId = sid, textContent = "hi"),
            image = null, audio = null, pipeWriteEnd = pfd,
        )

        // Wait for the first-round tool_call frame, then submit a result.
        val deadline = System.currentTimeMillis() + 5_000L
        var submitted = false
        while (System.currentTimeMillis() < deadline && !submitted) {
            val events = TestPipeHelper.parseFrames(
                TestPipeHelper.readFrames(ByteArrayInputStream(out.toByteArray()))
            )
            val tc = events.firstOrNull { it.kind == "tool_call" }
            if (tc != null) {
                orchestrator.toolCallBridge.submitResult(
                    "100:req-cont",
                    tc.callId!!,
                    tc.toolName!!,
                    """{"temp":21}""",
                )
                submitted = true
            }
            Thread.sleep(25)
        }
        assertTrue("Tool call frame never appeared", submitted)

        val finishDeadline = System.currentTimeMillis() + 5_000L
        while (System.currentTimeMillis() < finishDeadline &&
            sessionManager.activeRequestIdForSession(sid) != null
        ) {
            Thread.sleep(25)
        }

        val rejected = capturedLogs.filter {
            it.category == LogCategory.SECURITY && it.event == LogEvent.TOOL_CALL_REJECTED
        }
        assertTrue(
            "Expected continuation-chunk drop log; logs=$capturedLogs",
            rejected.any { it.extraJson?.contains("unknown_tool") == true },
        )
        assertFalse(
            "raw model-emitted tool name must not be persisted",
            rejected.any { it.extraJson?.contains("delete_account") == true },
        )
    }

    @Test
    fun `oversize args are truncated to MAX_TOOL_ARGS_LEN`() {
        val giant = "X".repeat(InferenceOrchestrator.MAX_TOOL_ARGS_LEN + 8_000)
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns flow {
            emit(chunk(calls = listOf("weather" to mapOf("payload" to giant))))
        }
        val sid = sessionManager.createSession(
            SessionConfig(
                maxTokens = 2048,
                toolsJson = """[{"name":"weather","description":"d"}]""",
            )
        )

        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val out = ByteArrayOutputStream()
        outputStreamQueue.add(out)
        orchestrator.infer(
            "100:req-oversize",
            RequestMeta(requestId = "req-oversize", sessionId = sid, textContent = "hi"),
            image = null, audio = null, pipeWriteEnd = pfd,
        )

        val deadline = System.currentTimeMillis() + 5_000L
        var asserted = false
        while (System.currentTimeMillis() < deadline && !asserted) {
            val events = TestPipeHelper.parseFrames(
                TestPipeHelper.readFrames(ByteArrayInputStream(out.toByteArray()))
            )
            val tc = events.firstOrNull { it.kind == "tool_call" }
            if (tc?.toolArgs != null) {
                val argsLen = tc.toolArgs!!.length
                assertTrue(
                    "Tool call args length should be <= MAX_TOOL_ARGS_LEN, got $argsLen",
                    argsLen <= InferenceOrchestrator.MAX_TOOL_ARGS_LEN,
                )
                val rejected = capturedLogs.filter {
                    it.category == LogCategory.SECURITY && it.event == LogEvent.TOOL_CALL_REJECTED
                }
                assertTrue(
                    "Expected an oversize_args log entry, got logs=$capturedLogs",
                    rejected.any { it.extraJson?.contains("oversize_args") == true },
                )
                asserted = true
            }
            Thread.sleep(25)
        }
        orchestrator.cancelInference("100:req-oversize")
        assertTrue("Tool call frame never appeared before timeout", asserted)
    }

    /**
     * Regression for the PR-2A PII fix on the oversize-args branch.
     *
     * Even though the tool name passed the allowlist gate (so it was
     * declared by a trusted client app), it can still encode user/model
     * context, so the orchestrator must NOT echo the raw name into either
     * logcat or [LogEntry.extraJson]. The log payload must look exactly
     * like the unknown-tool path: `{len, reason, hash8, size}` only.
     */
    @Test
    fun `oversize args log path keeps raw tool name out of extraJson`() {
        // A distinctive, allowed tool name so a substring match would be
        // unambiguous if regressions slip the raw name back into the log.
        val sensitiveName = "search_email_for_alice_jane_doe"
        val giant = "X".repeat(InferenceOrchestrator.MAX_TOOL_ARGS_LEN + 1_024)
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns flow {
            emit(chunk(calls = listOf(sensitiveName to mapOf("payload" to giant))))
        }
        val sid = sessionManager.createSession(
            SessionConfig(
                maxTokens = 2048,
                toolsJson = """[{"name":"$sensitiveName","description":"d"}]""",
            )
        )

        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val out = ByteArrayOutputStream()
        outputStreamQueue.add(out)
        orchestrator.infer(
            "100:req-oversize-pii",
            RequestMeta(requestId = "req-oversize-pii", sessionId = sid, textContent = "hi"),
            image = null, audio = null, pipeWriteEnd = pfd,
        )

        val deadline = System.currentTimeMillis() + 5_000L
        var sawRejected = false
        while (System.currentTimeMillis() < deadline && !sawRejected) {
            val rejected = capturedLogs.filter {
                it.category == LogCategory.SECURITY && it.event == LogEvent.TOOL_CALL_REJECTED
            }
            if (rejected.any { it.extraJson?.contains("oversize_args") == true }) {
                sawRejected = true
                val payloads = rejected.mapNotNull { it.extraJson }
                assertTrue(
                    "Expected oversize_args metadata payload",
                    payloads.any { it.contains("oversize_args") && it.contains("hash8") && it.contains("\"len\"") },
                )
                assertFalse(
                    "Raw tool name must NOT appear in any rejected-log extraJson; payloads=$payloads",
                    payloads.any { it.contains(sensitiveName) },
                )
            } else {
                Thread.sleep(25)
            }
        }
        orchestrator.cancelInference("100:req-oversize-pii")
        assertTrue("oversize_args log entry never appeared before timeout", sawRejected)

        // Defense-in-depth: verify the logcat string never carries the raw
        // tool name either. The `Log.w(...)` mocks throw on unmatched args,
        // so we capture all `Log.w(tag, msg)` calls and inspect their msg.
        io.mockk.verify(atLeast = 1) {
            Log.w(any<String>(), match<String> { msg ->
                msg.contains("oversize tool args") && !msg.contains(sensitiveName)
            })
        }
        io.mockk.verify(exactly = 0) {
            Log.w(any<String>(), match<String> { msg -> msg.contains(sensitiveName) })
        }
    }

    @Test
    fun `known tool name is forwarded`() {
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns flow {
            emit(chunk(calls = listOf("weather" to mapOf("city" to "Prague"))))
        }
        val sid = sessionManager.createSession(
            SessionConfig(
                maxTokens = 2048,
                toolsJson = """[{"name":"weather","description":"d"}]""",
            )
        )
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val out = ByteArrayOutputStream()
        outputStreamQueue.add(out)
        orchestrator.infer(
            "100:req-known",
            RequestMeta(requestId = "req-known", sessionId = sid, textContent = "hi"),
            image = null, audio = null, pipeWriteEnd = pfd,
        )
        val deadline = System.currentTimeMillis() + 5_000L
        var seen = false
        while (System.currentTimeMillis() < deadline && !seen) {
            val events = TestPipeHelper.parseFrames(
                TestPipeHelper.readFrames(ByteArrayInputStream(out.toByteArray()))
            )
            val tc = events.firstOrNull { it.kind == "tool_call" }
            if (tc != null) {
                assertEquals("weather", tc.toolName)
                seen = true
            }
            Thread.sleep(25)
        }
        orchestrator.cancelInference("100:req-known")
        assertTrue("Known tool call frame never appeared", seen)
    }

    @Test
    fun `SessionHandle exposes declared tool names plus synthetic tool for TOOL_ROUTING`() {
        val sid = sessionManager.createSession(
            SessionConfig(
                maxTokens = 2048,
                toolsJson = """[{"name":"weather"},{"name":"calc"}]""",
                extraContextJson = """{"structured_output":{"schema":{"type":"object"},"strategy":"tool_routing"}}""",
            )
        )
        val handle = sessionManager.getSession(sid)!!
        assertTrue("weather" in handle.allowedToolNames)
        assertTrue("calc" in handle.allowedToolNames)
        assertTrue(StructuredOutputHelper.TOOL_NAME in handle.allowedToolNames)
    }

    @Test
    fun `SessionHandle allowedToolNames matches declared set when no structured output`() {
        val sid = sessionManager.createSession(
            SessionConfig(
                maxTokens = 2048,
                toolsJson = """[{"name":"weather"},{"name":"calc"}]""",
            )
        )
        val handle = sessionManager.getSession(sid)!!
        assertEquals(setOf("weather", "calc"), handle.allowedToolNames)
        assertFalse(StructuredOutputHelper.TOOL_NAME in handle.allowedToolNames)
    }
}
