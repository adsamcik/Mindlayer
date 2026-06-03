package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.adsamcik.mindlayer.SessionConfig
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for v1.1 Gemma 4 thinking-mode opt-in plumbing in
 * [SessionManager]. Covers:
 *
 *  - [SessionManager.parseThinkingOptIn] over every documented JSON shape
 *    (nested `{ "enable": true }`, the bare-boolean shorthand, missing
 *    keys, malformed JSON, fail-open semantics).
 *  - The full session-create path: when the opt-in is on, the LiteRT-LM
 *    [ConversationConfig] passed to [Engine.createConversation] must
 *    carry a single `thought` channel with the documented Gemma start /
 *    end delimiters AND a system instruction that begins with the
 *    `<|think|>` marker.
 *  - When the opt-in is off, the configuration is byte-for-byte the
 *    same as today (no channels, no marker injected).
 */
class SessionManagerThinkingModeTest {

    private lateinit var context: Context
    private lateinit var engineManager: EngineManager
    private lateinit var memoryBudget: MemoryBudget
    private lateinit var sessionManager: SessionManager
    private lateinit var mockEngine: Engine

    @Before
    fun setUp() {
        mockkStatic(SystemClock::class)
        mockkStatic(Log::class)

        every { SystemClock.elapsedRealtime() } returns 100_000L
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        mockEngine = mockk(relaxed = true) {
            every { createConversation(any()) } returns mockk<Conversation>(relaxed = true)
        }
        engineManager = mockk(relaxed = true) {
            every { isInitialized } returns true
            every { requireEngine() } returns mockEngine
            every { currentBackend } returns "GPU"
        }

        val tier = DeviceTier(
            maxSessions = 6,
            defaultMaxTokens = 16384,
            maxMaxTokens = 32768,
            deviceRamMb = 16 * 1024L,
        )
        memoryBudget = mockk(relaxed = true) {
            every { deviceTier } returns tier
            every { currentSnapshot() } returns MemorySnapshot(
                availableMb = 4000L,
                totalMb = 16 * 1024L,
                lowMemory = false,
                pressure = MemoryPressure.NORMAL,
                recommendedMaxTokens = 32768,
            )
        }

        context = mockk(relaxed = true)
        sessionManager = SessionManager(context, engineManager, memoryBudget)
    }

    @After
    fun tearDown() {
        sessionManager.shutdown()
        unmockkAll()
    }

    // ---- parseThinkingOptIn ------------------------------------------------

    @Test
    fun `parseThinkingOptIn accepts nested enable=true`() {
        assertTrue(SessionConfigValidator.parseThinkingOptIn("""{"thinking":{"enable":true}}"""))
    }

    @Test
    fun `parseThinkingOptIn returns false for nested enable=false`() {
        assertFalse(SessionConfigValidator.parseThinkingOptIn("""{"thinking":{"enable":false}}"""))
    }

    @Test
    fun `parseThinkingOptIn accepts bare-boolean shorthand`() {
        assertTrue(SessionConfigValidator.parseThinkingOptIn("""{"thinking":true}"""))
    }

    @Test
    fun `parseThinkingOptIn returns false when thinking key missing`() {
        assertFalse(SessionConfigValidator.parseThinkingOptIn("""{"token_batch":true}"""))
    }

    @Test
    fun `parseThinkingOptIn returns false on null and blank input`() {
        assertFalse(SessionConfigValidator.parseThinkingOptIn(null))
        assertFalse(SessionConfigValidator.parseThinkingOptIn(""))
        assertFalse(SessionConfigValidator.parseThinkingOptIn("   "))
    }

    @Test
    fun `parseThinkingOptIn returns false on malformed JSON (fail-open)`() {
        assertFalse(SessionConfigValidator.parseThinkingOptIn("not json"))
        assertFalse(SessionConfigValidator.parseThinkingOptIn("""{"thinking":{"enable":}}"""))
    }

    @Test
    fun `parseThinkingOptIn coexists with token_batch opt-in`() {
        // Both flags should be parseable from one envelope (orthogonal).
        val both = """{"thinking":{"enable":true},"token_batch":true}"""
        assertTrue(SessionConfigValidator.parseThinkingOptIn(both))
    }

    // ---- createSession plumbing -------------------------------------------

    @Test
    fun `createSession with thinking opt-in configures thought channel and enable_thinking extraContext`() {
        val sessionId = sessionManager.createSession(
            SessionConfig(
                extraContextJson = """{"thinking":{"enable":true}}""",
                systemPrompt = "You are a careful assistant.",
            ),
        )

        // Hot-swap: createSession no longer eagerly opens a native
        // Conversation. The factory recipe is stored on the handle as
        // `baseConversationConfig` and run on first lease — inspect it
        // directly instead of capturing on engine.createConversation.
        val cfg = sessionManager.getSession(sessionId)!!.baseConversationConfig
        val channels = cfg.channels!!
        assertEquals(1, channels.size)
        val channel = channels.first()
        assertEquals(SessionManager.THINKING_CHANNEL_NAME, channel.channelName)
        assertEquals(SessionManager.THINKING_CHANNEL_START, channel.start)
        assertEquals(SessionManager.THINKING_CHANNEL_END, channel.end)

        // Critical: thinking is enabled via the chat-template variable,
        // not by prepending a literal `<|think|>` string. A literal prepend
        // tokenises as plain chars; only the template engine can insert
        // the special token id. Verified end-to-end by the on-device probe
        // (ThinkingModeInstrumentedTest) — prior to this PR the prepend
        // path produced zero ThoughtDelta events.
        val ctx = cfg.extraContext!!
        assertEquals(true, ctx[SessionManager.THINKING_TEMPLATE_KEY])

        val systemText = cfg.systemInstruction
            ?.contents
            ?.filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
            ?.joinToString("") { it.text }
        assertEquals(
            "system instruction must equal the user-supplied prompt verbatim — " +
                "no thinking marker is prepended (the template engine handles that)",
            "You are a careful assistant.",
            systemText,
        )
    }

    @Test
    fun `createSession without thinking opt-in leaves channels empty and extraContext clean`() {
        val sessionId = sessionManager.createSession(
            SessionConfig(systemPrompt = "You are a careful assistant."),
        )

        val cfg = sessionManager.getSession(sessionId)!!.baseConversationConfig
        assertTrue("no channels should be configured by default", cfg.channels?.isEmpty() ?: true)
        assertTrue(
            "extraContext must be empty when thinking is off",
            cfg.extraContext?.isEmpty() ?: true,
        )
        val systemText = cfg.systemInstruction
            ?.contents
            ?.filterIsInstance<com.google.ai.edge.litertlm.Content.Text>()
            ?.joinToString("") { it.text }
        assertEquals("You are a careful assistant.", systemText)
    }

    @Test
    fun `createSession with thinking opt-in and no client systemPrompt leaves system instruction null`() {
        val sessionId = sessionManager.createSession(
            SessionConfig(extraContextJson = """{"thinking":{"enable":true}}"""),
        )

        val cfg = sessionManager.getSession(sessionId)!!.baseConversationConfig
        assertEquals(1, cfg.channels!!.size)
        assertEquals(true, cfg.extraContext!![SessionManager.THINKING_TEMPLATE_KEY])
        // When there is no client systemPrompt, the systemInstruction is
        // null — the template engine still inserts the <|think|> token
        // because `enable_thinking=true` in extraContext is the source of
        // truth, independent of any system-instruction text.
        assertNull(
            "no client systemPrompt should leave systemInstruction null; the template " +
                "engine still inserts <|think|> from extraContext.enable_thinking",
            cfg.systemInstruction,
        )
    }
}
