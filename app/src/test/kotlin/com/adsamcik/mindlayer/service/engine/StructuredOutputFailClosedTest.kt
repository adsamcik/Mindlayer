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

/**
 * F-038: structured output retry exhaustion fails closed via
 * `closeWithError(0, "structured_output_validation_failed")` instead of
 * silently emitting the last invalid output. Both PROMPT_AND_VALIDATE
 * and TOOL_ROUTING strategies share the [InferenceOrchestrator]'s
 * `validateAndMaybeRetry` helper, so this regression covers both.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StructuredOutputFailClosedTest {

    private lateinit var context: Context
    private lateinit var engineManager: EngineManager
    private lateinit var memoryBudget: MemoryBudget
    private lateinit var sharedMemoryPool: SharedMemoryPool
    private lateinit var service: MindlayerMlService
    private lateinit var mockEngine: Engine
    private lateinit var mockConversation: Conversation
    private lateinit var sessionManager: SessionManager
    private lateinit var orchestrator: InferenceOrchestrator
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
            every { engineManager } returns this@StructuredOutputFailClosedTest.engineManager
        }
        context = mockk(relaxed = true)
        sessionManager = SessionManager(context, engineManager, memoryBudget)
        outputStreamQueue.clear()
        orchestrator = InferenceOrchestrator(
            service, sessionManager, sharedMemoryPool,
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

    private fun textChunk(text: String): Message = mockk {
        every { contents } returns mockk {
            every { contents } returns listOf(
                mockk<Content.Text> inner@{ every { this@inner.text } returns text }
            )
        }
        every { toolCalls } returns emptyList()
    }

    private fun toolChunk(name: String, args: Map<String, Any>): Message = mockk {
        every { contents } returns mockk { every { contents } returns emptyList<Content>() }
        every { toolCalls } returns listOf(
            mockk<ToolCall> {
                every { this@mockk.name } returns name
                every { arguments } returns args
            }
        )
    }

    private fun driveAndParse(scopedKey: String, sid: String): List<TestPipeHelper.ParsedEvent> {
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val out = ByteArrayOutputStream()
        outputStreamQueue.add(out)
        orchestrator.infer(
            scopedKey,
            RequestMeta(requestId = scopedKey.substringAfter(':'), sessionId = sid, textContent = "hi"),
            image = null, audio = null, pipeWriteEnd = pfd,
        )
        // Wait for runInference to actually start (activeRequestId set
        // inside the session mutex). Polling on activeRequestId alone
        // races a fast-completing inference (it would be `null` both
        // before start and after finish).
        val startDeadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < startDeadline &&
            sessionManager.activeRequestIdForSession(sid) == null
        ) {
            Thread.sleep(10)
        }
        // Then wait for it to finish.
        val endDeadline = System.currentTimeMillis() + 10_000L
        while (System.currentTimeMillis() < endDeadline) {
            if (sessionManager.activeRequestIdForSession(sid) == null) {
                // Drain a moment for the finally to flush + close.
                Thread.sleep(50)
                break
            }
            Thread.sleep(25)
        }
        return TestPipeHelper.parseFrames(
            TestPipeHelper.readFrames(ByteArrayInputStream(out.toByteArray()))
        )
    }

    @Test
    fun `PROMPT_AND_VALIDATE fails closed on retry exhaustion`() {
        // Conversation always returns garbage; validator always rejects.
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns flow {
            emit(textChunk("not json at all"))
        }
        val sid = sessionManager.createSession(
            SessionConfig(
                maxTokens = 2048,
                extraContextJson = """
                    {"structured_output":{
                        "schema":{"type":"object","required":["x"]},
                        "strategy":"prompt_and_validate",
                        "max_retries":1
                    }}
                """.trimIndent(),
            )
        )
        val events = driveAndParse("100:req-pv", sid)

        // Must NOT contain a token_delta carrying the invalid payload
        // (i.e., the orchestrator must not have leaked the last invalid
        // output as if it were valid).
        val tokenDeltas = events.filter { it.kind == "token_delta" }
        assertTrue(
            "Expected no token_delta frames carrying invalid output, got $tokenDeltas",
            tokenDeltas.none { it.text == "not json at all" },
        )

        // Must terminate with a structured_output_validation_failed error.
        val errors = events.filter { it.kind == "error" }
        assertTrue(
            "Expected an error frame; events=$events",
            errors.any { it.errorMessage == "structured_output_validation_failed" },
        )
        // No `done` frame should follow.
        assertFalse(
            "No 'done' frame should appear after closeWithError",
            events.any { it.kind == "done" },
        )
    }

    @Test
    fun `TOOL_ROUTING fails closed on retry exhaustion`() {
        // Synthetic tool always emits args that do NOT match the schema
        // (missing "x").
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns flow {
            emit(toolChunk(StructuredOutputHelper.TOOL_NAME, mapOf("y" to 1)))
        }
        val sid = sessionManager.createSession(
            SessionConfig(
                maxTokens = 2048,
                extraContextJson = """
                    {"structured_output":{
                        "schema":{"type":"object","required":["x"]},
                        "strategy":"tool_routing",
                        "max_retries":1
                    }}
                """.trimIndent(),
            )
        )
        val events = driveAndParse("100:req-tr", sid)

        val tokenDeltas = events.filter { it.kind == "token_delta" }
        // Even a partial leak of the bad JSON would be a fail-open.
        assertTrue(
            "Expected no token_delta frames carrying invalid args, got $tokenDeltas",
            tokenDeltas.none { it.text?.contains("\"y\"") == true },
        )

        val errors = events.filter { it.kind == "error" }
        assertTrue(
            "Expected an error frame; events=$events",
            errors.any { it.errorMessage == "structured_output_validation_failed" },
        )
        assertFalse(
            "No 'done' frame should appear after closeWithError",
            events.any { it.kind == "done" },
        )
    }

    @Test
    fun `TOOL_ROUTING prose output fails closed without streaming prose`() {
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns flow {
            emit(textChunk("this is prose, not a tool call"))
        }
        val sid = sessionManager.createSession(
            SessionConfig(
                maxTokens = 2048,
                extraContextJson = """
                    {"structured_output":{
                        "schema":{"type":"object","required":["x"]},
                        "strategy":"tool_routing",
                        "max_retries":0
                    }}
                """.trimIndent(),
            )
        )
        val events = driveAndParse("100:req-tr-prose", sid)

        assertTrue(
            "TOOL_ROUTING prose must not be streamed; events=$events",
            events.none { it.kind == "token_delta" && it.text?.contains("this is prose") == true },
        )
        val errors = events.filter { it.kind == "error" }
        assertTrue("Expected a typed error frame; events=$events", errors.isNotEmpty())
        assertTrue(
            "Expected structured_output_fail_closed; errors=$errors",
            errors.any { it.errorMessage == "structured_output_fail_closed" && it.errorCode == "INVALID_REQUEST" },
        )
        assertFalse(
            "No 'done' frame should appear after fail-closed error",
            events.any { it.kind == "done" },
        )
    }

}
