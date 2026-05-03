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
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.Message
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * F-072: regression coverage for the KV-cache budget gate.
 *
 * The four scenarios mirror the F-072 acceptance criteria:
 *  1. Service-owned overhead + oversized user input → orchestrator throws
 *     [ContextOverflowException] **before** `Conversation.sendMessageAsync`
 *     is ever invoked.
 *  2. Same overhead, modest user input → inference proceeds normally
 *     (the gate must not false-positive).
 *  3. Service-owned overhead alone exhausts the device-tier ceiling →
 *     `createSession` throws and no engine `createConversation` is made.
 *  4. Multimodal-only path (image/audio without text) is also gated, so
 *     a malicious caller can't bypass the check by setting
 *     `textContent = null`.
 *
 * The ≤6 GB tier (`maxMaxTokens = 2048`) is the worst-case device shape;
 * tests pin to it explicitly so the budget arithmetic doesn't depend on
 * fixture defaults.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SessionManagerContextBudgetTest {

    private lateinit var context: Context
    private lateinit var engineManager: EngineManager
    private lateinit var memoryBudget: MemoryBudget
    private lateinit var sharedMemoryPool: SharedMemoryPool
    private lateinit var service: MindlayerMlService
    private lateinit var mockEngine: Engine
    private lateinit var mockConversation: Conversation
    private lateinit var sessionManager: SessionManager
    private lateinit var orchestrator: InferenceOrchestrator

    private val outputStreamQueue = ConcurrentLinkedQueue<OutputStream>()

    /** Tier under test: 2048 KV cap, 6 GB device — see F-072 acceptance. */
    private val lowRamTier = DeviceTier(
        maxSessions = 4,
        defaultMaxTokens = 2048,
        maxMaxTokens = 2048,
        deviceRamMb = 6L * 1024,
    )

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

        memoryBudget = mockk(relaxed = true) {
            every { deviceTier } returns lowRamTier
            every { currentSnapshot() } returns MemorySnapshot(
                availableMb = 3000L,
                totalMb = 6L * 1024,
                lowMemory = false,
                pressure = MemoryPressure.NORMAL,
                recommendedMaxTokens = 2048,
            )
        }

        sharedMemoryPool = mockk(relaxed = true) {
            every { cleanup(any()) } returns Unit
            every { cleanupAll() } returns Unit
        }
        service = mockk(relaxed = true) {
            every { enterForeground() } returns Unit
            every { exitForeground() } returns Unit
        }
        context = mockk(relaxed = true)

        sessionManager = SessionManager(context, engineManager, memoryBudget)
        outputStreamQueue.clear()
        orchestrator = InferenceOrchestrator(
            service, sessionManager, sharedMemoryPool,
            writerFactory = { _ ->
                val out = outputStreamQueue.poll() ?: ByteArrayOutputStream()
                TokenStreamWriter.forTesting(out, writeTimeoutMs = 200L)
            },
        )
    }

    @After
    fun tearDown() {
        orchestrator.shutdown()
        unmockkAll()
    }

    // ---- Helpers --------------------------------------------------------

    /**
     * Build an OpenAPI-shaped tool definition string. The token estimator
     * counts the **raw `toolsJson`** length, so each definition's char
     * count maps deterministically to its reservedToken contribution at
     * `chars/3`.
     */
    private fun toolDef(name: String, padBytes: Int = 0): String {
        val padding = "x".repeat(padBytes)
        return """{"name":"$name","description":"$padding","parameters":{"type":"object"}}"""
    }

    private fun fiveToolDefsJsonAround600Tokens(): String {
        // Five tool defs whose total char count yields ~600 reserved
        // tokens via `chars/3`. Aim for ~1800 chars total → ~600 tokens.
        // Each def base ≈ 80 chars + 280-char padding = ~360 chars × 5
        // = 1800 chars. The TOOL_SAFETY_PREAMBLE on top pushes overhead
        // a bit further but stays comfortably under 2048.
        val builder = StringBuilder("[")
        for (i in 1..5) {
            if (i > 1) builder.append(',')
            builder.append(toolDef("tool$i", padBytes = 280))
        }
        builder.append(']')
        return builder.toString()
    }

    private fun textMessage(text: String?): Message = mockk {
        every { contents } returns mockk {
            every { contents } returns if (text.isNullOrEmpty()) {
                emptyList()
            } else {
                listOf(
                    mockk<com.google.ai.edge.litertlm.Content.Text> inner@{
                        every { this@inner.text } returns text
                    },
                )
            }
        }
        every { toolCalls } returns emptyList()
    }

    private fun singleTextFlow(text: String): Flow<Message> = flow {
        emit(textMessage(text))
    }

    /** A user-input string of approximately [tokenCount] estimated tokens. */
    private fun textOfApproxTokens(tokenCount: Int): String =
        "x".repeat(tokenCount * CHARS_PER_TOKEN_ESTIMATE)

    // ---- Tests ----------------------------------------------------------

    @Test
    fun `infer with overflowing input throws ContextOverflowException synchronously and never calls sendMessageAsync`() {
        // Acceptance #1: ≤6 GB tier, 1500-char system prompt + 5 tool defs.
        // Combined with TOOL_SAFETY_PREAMBLE (~447 chars) the overhead
        // works out to ≈1200 reserved tokens, leaving ~800 free for user
        // input. A 1700-token user input overflows by a comfortable
        // margin so the gate fires deterministically.
        val systemPrompt = "s".repeat(1500)
        val toolsJson = fiveToolDefsJsonAround600Tokens()

        val sid = sessionManager.createSession(
            SessionConfig(
                maxTokens = 2048,
                systemPrompt = systemPrompt,
                toolsJson = toolsJson,
            ),
        )

        val handle = sessionManager.getSession(sid)
        assertNotNull("Session should exist", handle)
        val reserved = handle!!.reservedTokens
        assertTrue(
            "Reserved tokens should be in the 1000-1500 range for this fixture, got $reserved",
            reserved in 1000..1500,
        )
        assertTrue(
            "Session should still have headroom for small inputs, reserved=$reserved",
            reserved < 2048,
        )

        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val meta = RequestMeta(
            requestId = "req-overflow",
            sessionId = sid,
            textContent = textOfApproxTokens(1700),
        )

        val ex = assertThrows(ContextOverflowException::class.java) {
            orchestrator.infer(
                scopedKey = "100:req-overflow",
                meta = meta,
                image = null,
                audio = null,
                pipeWriteEnd = pfd,
                onComplete = null,
            )
        }
        assertEquals(reserved, ex.reservedTokens)
        assertEquals(2048, ex.effectiveMaxTokens)
        assertTrue(
            "estimated input should be ≥ 1700 tokens, got ${ex.estimatedInputTokens}",
            ex.estimatedInputTokens >= 1700,
        )
        assertTrue("remainingTokens >= 0", ex.remainingTokens >= 0)
        assertTrue(
            "wireMessage carries `remaining=`, got '${ex.wireMessage}'",
            ex.wireMessage.contains("remaining="),
        )

        // Native generation must NEVER have been invoked.
        verify(exactly = 0) { mockConversation.sendMessageAsync(any<Contents>()) }
    }

    @Test
    fun `infer with input that fits proceeds normally and sendMessageAsync is invoked`() {
        // Acceptance #2: same fixture, but a 100-token user input fits
        // comfortably under (2048 - reservedTokens). The gate must NOT
        // false-positive on inputs that fit.
        val systemPrompt = "s".repeat(1500)
        val toolsJson = fiveToolDefsJsonAround600Tokens()

        every { mockConversation.sendMessageAsync(any<Contents>()) } returns
            singleTextFlow("ok")

        val sid = sessionManager.createSession(
            SessionConfig(
                maxTokens = 2048,
                systemPrompt = systemPrompt,
                toolsJson = toolsJson,
            ),
        )

        val output = ByteArrayOutputStream()
        outputStreamQueue.add(output)
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val meta = RequestMeta(
            requestId = "req-fits",
            sessionId = sid,
            textContent = textOfApproxTokens(100),
        )

        // Should not throw — the gate lets this request through.
        orchestrator.infer(
            scopedKey = "100:req-fits",
            meta = meta,
            image = null,
            audio = null,
            pipeWriteEnd = pfd,
            onComplete = null,
        )

        // Wait for the launched inference to invoke sendMessageAsync.
        verify(timeout = 5_000L, exactly = 1) {
            mockConversation.sendMessageAsync(any<Contents>())
        }
    }

    @Test
    fun `createSession with system prompt alone exceeding tier ceiling throws ContextOverflowException`() {
        // Acceptance #3: a 7000-character system prompt → ~2334 estimated
        // overhead tokens (chars/3) which exceeds the 2048 tier cap on
        // its own. createSession must refuse and the engine must never
        // be asked to create a Conversation.
        val systemPrompt = "s".repeat(7000)

        val ex = assertThrows(ContextOverflowException::class.java) {
            sessionManager.createSession(
                SessionConfig(
                    maxTokens = 2048,
                    systemPrompt = systemPrompt,
                ),
            )
        }
        assertTrue(
            "reservedTokens should be > 2048, got ${ex.reservedTokens}",
            ex.reservedTokens > 2048,
        )
        assertEquals(0, ex.estimatedInputTokens)
        assertEquals(2048, ex.effectiveMaxTokens)
        assertEquals(0, ex.remainingTokens)
        assertTrue(
            "wireMessage carries `input_exceeds_context`, got '${ex.wireMessage}'",
            ex.wireMessage.contains("input_exceeds_context"),
        )

        // No session entry created, no native conversation opened.
        assertEquals(0, sessionManager.sessionCount)
        verify(exactly = 0) { mockEngine.createConversation(any()) }
    }

    @Test
    fun `infer is gated even when only an image part is supplied`() {
        // Defense-in-depth: a malicious caller cannot bypass the gate by
        // setting `textContent = null` and shipping a multimodal payload.
        // 6000-char system prompt → ~2000 reserved tokens (chars/3),
        // leaving only ~48 tokens free. An image alone costs 256 tokens
        // so even an image-only request must overflow.
        val systemPrompt = "s".repeat(6000)
        val sid = sessionManager.createSession(
            SessionConfig(
                maxTokens = 2048,
                systemPrompt = systemPrompt,
            ),
        )
        val handle = sessionManager.getSession(sid)
        assertNotNull(handle)
        val reserved = handle!!.reservedTokens
        assertTrue(
            "Fixture should leave less than 256 tokens of headroom, " +
                "reserved=$reserved (max=2048)",
            (2048 - reserved) < IMAGE_TOKENS_ESTIMATE,
        )

        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val image = mockk<com.adsamcik.mindlayer.ImageTransfer>(relaxed = true)
        val meta = RequestMeta(
            requestId = "req-image-only",
            sessionId = sid,
            textContent = null,
        )

        assertThrows(ContextOverflowException::class.java) {
            orchestrator.infer(
                scopedKey = "100:req-image-only",
                meta = meta,
                image = image,
                audio = null,
                pipeWriteEnd = pfd,
                onComplete = null,
            )
        }
        verify(exactly = 0) { mockConversation.sendMessageAsync(any<Contents>()) }
    }

    @Test
    fun `audio with null durationMs uses conservative fallback so the gate cannot be bypassed`() {
        // Audio bypass guard: omitting `durationMs` would trivially evade
        // the check if the estimator treated null as 0. The estimator
        // instead falls back to AUDIO_FALLBACK_DURATION_MS (30 s = 750
        // tokens). Pin a session whose headroom is below 750 tokens and
        // assert the gate refuses.
        val systemPrompt = "s".repeat(5000) // ~1667 reserved tokens, ~381 free
        val sid = sessionManager.createSession(
            SessionConfig(
                maxTokens = 2048,
                systemPrompt = systemPrompt,
            ),
        )
        val handle = sessionManager.getSession(sid)
        assertNotNull(handle)
        val freeTokens = 2048 - handle!!.reservedTokens
        assertTrue(
            "Fixture should leave fewer than the audio fallback budget, free=$freeTokens",
            freeTokens < AUDIO_FALLBACK_DURATION_MS / 1000 *
                AUDIO_TOKENS_PER_SECOND_ESTIMATE,
        )

        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val audio = mockk<com.adsamcik.mindlayer.AudioTransfer>(relaxed = true) {
            every { durationMs } returns null
        }
        val meta = RequestMeta(
            requestId = "req-audio-no-duration",
            sessionId = sid,
            textContent = null,
        )

        assertThrows(ContextOverflowException::class.java) {
            orchestrator.infer(
                scopedKey = "100:req-audio-no-duration",
                meta = meta,
                image = null,
                audio = audio,
                pipeWriteEnd = pfd,
                onComplete = null,
            )
        }
        verify(exactly = 0) { mockConversation.sendMessageAsync(any<Contents>()) }
    }

    @Test
    fun `peekTokenBudget returns null for unknown sessions so orchestrator skips the gate`() {
        // When the session is unknown, the orchestrator must fall through
        // to runInference which writes SESSION_NOT_FOUND_OR_NOT_OWNED to
        // the pipe. The gate itself must not throw on missing sessions.
        assertNull(sessionManager.peekTokenBudget("nonexistent"))

        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val meta = RequestMeta(
            requestId = "req-no-session",
            sessionId = "nonexistent",
            textContent = "anything",
        )
        // Does not throw ContextOverflowException — runInference handles
        // the unknown-session path on the launched coroutine.
        orchestrator.infer(
            scopedKey = "100:req-no-session",
            meta = meta,
            image = null,
            audio = null,
            pipeWriteEnd = pfd,
            onComplete = null,
        )
        // sendMessageAsync still must not be called because the session
        // was never registered.
        verify(exactly = 0) { mockConversation.sendMessageAsync(any<Contents>()) }
    }
}
