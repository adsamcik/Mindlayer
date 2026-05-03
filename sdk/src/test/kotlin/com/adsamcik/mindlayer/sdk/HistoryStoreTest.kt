package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.sdk.db.MindlayerDatabase
import com.adsamcik.mindlayer.sdk.db.TurnRole
import com.adsamcik.mindlayer.sdk.db.TurnState
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class HistoryStoreTest {

    private lateinit var db: MindlayerDatabase
    private lateinit var store: HistoryStore

    private val defaultConfig = SessionConfig(
        sessionId = "sess-1",
        systemPrompt = "Be helpful.",
        maxTokens = 4096,
        backend = "GPU",
        samplerTopK = 40,
        samplerTopP = 0.95f,
        samplerTemperature = 0.7f,
        toolsJson = null,
        extraContextJson = null,
    )

    @Before
    fun setup() {
        // Mock android.util.Log used by HistoryStore
        mockkStatic(Log::class)
        every { Log.i(any(), any()) } returns 0
        every { Log.d(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        val context = ApplicationProvider.getApplicationContext<Context>()

        // Build in-memory database and inject via MindlayerDatabase singleton hack:
        // We construct the HistoryStore with the same context, but to avoid the
        // singleton returning a persistent DB, we reset the singleton field.
        resetSingleton()
        db = Room.inMemoryDatabaseBuilder(context, MindlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        setSingleton(db)

        store = HistoryStore(context, HistoryPolicy.FULL_CONTENT)
    }

    @After
    fun teardown() {
        db.close()
        resetSingleton()
        unmockkStatic(Log::class)
    }

    /** Clear the private `instance` field in MindlayerDatabase.Companion. */
    private fun resetSingleton() {
        val field = MindlayerDatabase::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    /** Inject our in-memory DB into the singleton so HistoryStore picks it up. */
    private fun setSingleton(database: MindlayerDatabase) {
        val field = MindlayerDatabase::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, database)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  persistConversation
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `persistConversation saves conversation to Room`() = runTest {
        store.persistConversation("sess-1", defaultConfig)

        val loaded = db.conversationDao().get("sess-1")
        assertNotNull(loaded)
        assertEquals("Be helpful.", loaded!!.systemPrompt)
        assertEquals("GPU", loaded.backend)
        assertEquals(4096, loaded.maxTokens)
    }

    @Test
    fun `default history policy stores metadata only`() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val metadataOnlyStore = HistoryStore(context)
        val config = defaultConfig.copy(
            systemPrompt = "private system prompt",
            toolsJson = """[{"name":"privateTool"}]""",
            extraContextJson = """{"secret":"context"}""",
        )

        metadataOnlyStore.persistConversation("metadata-only", config)
        val userTurnId = metadataOnlyStore.persistUserTurn("metadata-only", "private user text")
        metadataOnlyStore.markUserTurnCompleted(userTurnId)
        val assistantTurnId = metadataOnlyStore.beginAssistantTurn("metadata-only")
        metadataOnlyStore.markTurnCompleted(assistantTurnId, "private assistant text")

        val conversation = db.conversationDao().get("metadata-only")!!
        assertNull(conversation.systemPrompt)
        assertNull(conversation.toolsJson)
        assertNull(conversation.extraContextJson)

        val turns = db.turnDao().completedForConversation("metadata-only")
        assertEquals(2, turns.size)
        assertTrue(turns.all { it.textContent == null })
        assertTrue(turns.all { it.tokenEstimate > 0 })

        val summary = metadataOnlyStore.listConversations(limit = 10)
            .first { it.conversationId == "metadata-only" }
        assertNull(summary.systemPrompt)
        assertTrue(summary.preview.all { it.text == null })

        assertNull(metadataOnlyStore.getReplayHistory("metadata-only", 1000))
    }

    @Test
    fun `persistConversation stores sampler config as JSON`() = runTest {
        val config = defaultConfig.copy(
            samplerTopK = 50,
            samplerTopP = 0.9f,
            samplerTemperature = 1.2f,
        )
        store.persistConversation("sess-1", config)

        val loaded = db.conversationDao().get("sess-1")!!
        assertNotNull(loaded.samplerConfigJson)
        assertTrue(loaded.samplerConfigJson!!.contains("50"))
    }

    @Test
    fun `persistConversation with null systemPrompt`() = runTest {
        val config = defaultConfig.copy(systemPrompt = null)
        store.persistConversation("sess-1", config)

        val loaded = db.conversationDao().get("sess-1")!!
        assertNull(loaded.systemPrompt)
    }

    @Test
    fun `persistConversation stores tools and extra context`() = runTest {
        val config = defaultConfig.copy(
            toolsJson = """[{"name":"search"}]""",
            extraContextJson = """{"key":"val"}""",
        )
        store.persistConversation("sess-1", config)

        val loaded = db.conversationDao().get("sess-1")!!
        assertEquals("""[{"name":"search"}]""", loaded.toolsJson)
        assertEquals("""{"key":"val"}""", loaded.extraContextJson)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  persistUserTurn
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `persistUserTurn creates PENDING user turn`() = runTest {
        store.persistConversation("sess-1", defaultConfig)
        val turnId = store.persistUserTurn("sess-1", "Hello!")

        val turn = db.turnDao().get(turnId)
        assertNotNull(turn)
        assertEquals(TurnRole.USER, turn!!.role)
        assertEquals(TurnState.PENDING, turn.state)
        assertEquals("Hello!", turn.textContent)
        assertEquals(0, turn.seq)
    }

    @Test
    fun `persistUserTurn auto-increments seq`() = runTest {
        store.persistConversation("sess-1", defaultConfig)
        val id1 = store.persistUserTurn("sess-1", "First")
        val id2 = store.persistUserTurn("sess-1", "Second")

        assertEquals(0, db.turnDao().get(id1)!!.seq)
        assertEquals(1, db.turnDao().get(id2)!!.seq)
    }

    @Test
    fun `persistUserTurn estimates tokens`() = runTest {
        store.persistConversation("sess-1", defaultConfig)
        // "abcdefghijklmnop" = 16 chars → 16/4 = 4 tokens
        val turnId = store.persistUserTurn("sess-1", "abcdefghijklmnop")

        val turn = db.turnDao().get(turnId)!!
        assertEquals(4, turn.tokenEstimate)
    }

    @Test
    fun `persistUserTurn with empty text estimates at least 1 token`() = runTest {
        store.persistConversation("sess-1", defaultConfig)
        val turnId = store.persistUserTurn("sess-1", "ab")

        // "ab" = 2 chars → 2/4 = 0, coerced to 1
        val turn = db.turnDao().get(turnId)!!
        assertEquals(1, turn.tokenEstimate)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  beginAssistantTurn
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `beginAssistantTurn creates STREAMING turn`() = runTest {
        store.persistConversation("sess-1", defaultConfig)
        val turnId = store.beginAssistantTurn("sess-1")

        val turn = db.turnDao().get(turnId)!!
        assertEquals(TurnRole.ASSISTANT, turn.role)
        assertEquals(TurnState.STREAMING, turn.state)
        assertNull(turn.textContent)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  markTurnCompleted / markUserTurnCompleted
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `markTurnCompleted updates state and text`() = runTest {
        store.persistConversation("sess-1", defaultConfig)
        val turnId = store.beginAssistantTurn("sess-1")

        store.markTurnCompleted(turnId, "Full answer text")

        val turn = db.turnDao().get(turnId)!!
        assertEquals(TurnState.COMPLETED, turn.state)
        assertEquals("Full answer text", turn.textContent)
        assertTrue(turn.tokenEstimate > 0)
        assertNotNull(turn.completedAtMs)
    }

    @Test
    fun `markTurnCompleted with null text`() = runTest {
        store.persistConversation("sess-1", defaultConfig)
        val turnId = store.beginAssistantTurn("sess-1")

        store.markTurnCompleted(turnId, null)

        val turn = db.turnDao().get(turnId)!!
        assertEquals(TurnState.COMPLETED, turn.state)
        assertNull(turn.textContent)
        assertEquals(0, turn.tokenEstimate)
    }

    @Test
    fun `markUserTurnCompleted sets state to COMPLETED`() = runTest {
        store.persistConversation("sess-1", defaultConfig)
        val turnId = store.persistUserTurn("sess-1", "Hello")

        store.markUserTurnCompleted(turnId)

        val turn = db.turnDao().get(turnId)!!
        assertEquals(TurnState.COMPLETED, turn.state)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  markTurnInterrupted
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `markTurnInterrupted updates state to INTERRUPTED`() = runTest {
        store.persistConversation("sess-1", defaultConfig)
        val turnId = store.beginAssistantTurn("sess-1")

        store.markTurnInterrupted(turnId)

        val turn = db.turnDao().get(turnId)!!
        assertEquals(TurnState.INTERRUPTED, turn.state)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  cleanupInterruptedTurns
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `cleanupInterruptedTurns removes streaming and interrupted assistant turns`() = runTest {
        store.persistConversation("sess-1", defaultConfig)

        // User turn — completed
        val u1 = store.persistUserTurn("sess-1", "Msg 1")
        store.markUserTurnCompleted(u1)

        // Assistant turn — streaming (not completed)
        val a1 = store.beginAssistantTurn("sess-1")

        // Another user turn — completed
        val u2 = store.persistUserTurn("sess-1", "Msg 2")
        store.markUserTurnCompleted(u2)

        // Assistant turn — interrupted
        val a2 = store.beginAssistantTurn("sess-1")
        store.markTurnInterrupted(a2)

        val deleted = store.cleanupInterruptedTurns("sess-1")
        assertEquals(2, deleted)

        // Assistant turns gone
        assertNull(db.turnDao().get(a1))
        assertNull(db.turnDao().get(a2))

        // User turns still present
        assertNotNull(db.turnDao().get(u1))
        assertNotNull(db.turnDao().get(u2))
    }

    @Test
    fun `cleanupInterruptedTurns returns 0 when nothing to clean`() = runTest {
        store.persistConversation("sess-1", defaultConfig)

        val u1 = store.persistUserTurn("sess-1", "Hello")
        store.markUserTurnCompleted(u1)

        assertEquals(0, store.cleanupInterruptedTurns("sess-1"))
    }

    // ═══════════════════════════════════════════════════════════════════
    //  getReplayHistory
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `getReplayHistory returns null for unknown session`() = runTest {
        assertNull(store.getReplayHistory("nope", 1000))
    }

    @Test
    fun `getReplayHistory returns system prompt and completed turns`() = runTest {
        store.persistConversation("sess-1", defaultConfig)

        val u1 = store.persistUserTurn("sess-1", "Hello")
        store.markUserTurnCompleted(u1)

        val a1 = store.beginAssistantTurn("sess-1")
        store.markTurnCompleted(a1, "Hi there!")

        val replay = store.getReplayHistory("sess-1", 10000)
        assertNotNull(replay)
        assertEquals("sess-1", replay!!.conversationId)
        assertEquals("Be helpful.", replay.systemPrompt)
        assertEquals(2, replay.turns.size)
        assertEquals(TurnRole.USER, replay.turns[0].role)
        assertEquals(TurnRole.ASSISTANT, replay.turns[1].role)
    }

    @Test
    fun `getReplayHistory respects token budget`() = runTest {
        store.persistConversation("sess-1", defaultConfig.copy(systemPrompt = null))

        // Create several completed turns with known token counts
        // Each "aaaa" = 4 chars → 1 token
        val ids = mutableListOf<String>()
        for (i in 0 until 5) {
            val uid = store.persistUserTurn("sess-1", "a".repeat(40))  // 40/4 = 10 tokens each
            store.markUserTurnCompleted(uid)
            ids.add(uid)
        }

        // Budget of 25 tokens (null system prompt = 0 system tokens)
        // Should fit 2 most-recent turns (10+10=20 ≤ 25), not 3 (10+10+10=30 > 25)
        val replay = store.getReplayHistory("sess-1", 25)!!
        assertEquals(2, replay.turns.size)

        // Turns should be in chronological order (the two most recent)
        assertEquals(ids[3], replay.turns[0].turnId)
        assertEquals(ids[4], replay.turns[1].turnId)
    }

    @Test
    fun `getReplayHistory reserves tokens for system prompt`() = runTest {
        // "Be helpful." = 11 chars → 11/4 = 2 tokens for system prompt
        store.persistConversation("sess-1", defaultConfig)

        val u1 = store.persistUserTurn("sess-1", "a".repeat(40))  // 10 tokens
        store.markUserTurnCompleted(u1)

        // Budget: 5 tokens total. System prompt takes ~2, leaving 3 for turns.
        // The user turn needs 10 tokens → doesn't fit.
        val replay = store.getReplayHistory("sess-1", 5)!!
        assertTrue(replay.turns.isEmpty())
    }

    @Test
    fun `getReplayHistory excludes interrupted turns`() = runTest {
        store.persistConversation("sess-1", defaultConfig.copy(systemPrompt = null))

        val u1 = store.persistUserTurn("sess-1", "Hello")
        store.markUserTurnCompleted(u1)

        val a1 = store.beginAssistantTurn("sess-1")
        store.markTurnInterrupted(a1)

        val u2 = store.persistUserTurn("sess-1", "Try again")
        store.markUserTurnCompleted(u2)

        val a2 = store.beginAssistantTurn("sess-1")
        store.markTurnCompleted(a2, "OK!")

        val replay = store.getReplayHistory("sess-1", 10000)!!

        // Only completed turns should appear
        val states = replay.turns.map { it.state }
        assertTrue(states.all { it == TurnState.COMPLETED })
        // The interrupted assistant turn should not be present
        assertTrue(replay.turns.none { it.turnId == a1 })
    }

    @Test
    fun `getReplayHistory includes pending user turn for recovery`() = runTest {
        store.persistConversation("sess-1", defaultConfig)

        // Completed exchange
        val u1 = store.persistUserTurn("sess-1", "Hello")
        store.markUserTurnCompleted(u1)

        // Pending (unacknowledged) user turn
        val u2 = store.persistUserTurn("sess-1", "Another message")

        val replay = store.getReplayHistory("sess-1", 10000)!!
        assertNotNull(replay.pendingUserTurn)
        assertEquals(u2, replay.pendingUserTurn!!.turnId)
        assertEquals("Another message", replay.pendingUserTurn.textContent)
    }

    @Test
    fun `getReplayHistory returns null pendingUserTurn when none pending`() = runTest {
        store.persistConversation("sess-1", defaultConfig)

        val u1 = store.persistUserTurn("sess-1", "Hello")
        store.markUserTurnCompleted(u1)

        val replay = store.getReplayHistory("sess-1", 10000)!!
        assertNull(replay.pendingUserTurn)
    }

    @Test
    fun `getReplayHistory rebuilds SessionConfig correctly`() = runTest {
        val config = SessionConfig(
            sessionId = "sess-1",
            systemPrompt = "Test prompt",
            maxTokens = 2048,
            backend = "CPU",
            samplerTopK = 50,
            samplerTopP = 0.9f,
            samplerTemperature = 1.5f,
            toolsJson = """[{"tool":"echo"}]""",
            extraContextJson = """{"ctx":"val"}""",
        )
        store.persistConversation("sess-1", config)

        val replay = store.getReplayHistory("sess-1", 10000)!!
        val rebuilt = replay.config

        assertEquals("sess-1", rebuilt.sessionId)
        assertEquals("Test prompt", rebuilt.systemPrompt)
        assertEquals(2048, rebuilt.maxTokens)
        assertEquals("CPU", rebuilt.backend)
        assertEquals(50, rebuilt.samplerTopK)
        assertEquals(0.9f, rebuilt.samplerTopP)
        assertEquals(1.5f, rebuilt.samplerTemperature)
        assertEquals("""[{"tool":"echo"}]""", rebuilt.toolsJson)
        assertEquals("""{"ctx":"val"}""", rebuilt.extraContextJson)
    }

    @Test
    fun `getReplayHistory returns empty turns for new conversation`() = runTest {
        store.persistConversation("sess-1", defaultConfig)

        val replay = store.getReplayHistory("sess-1", 10000)!!
        assertTrue(replay.turns.isEmpty())
        assertNull(replay.pendingUserTurn)
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Recovery flow (integration)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `full recovery flow - persist interrupt cleanup replay`() = runTest {
        store.persistConversation("sess-1", defaultConfig)

        // Simulated conversation
        val u1 = store.persistUserTurn("sess-1", "What is 2+2?")
        store.markUserTurnCompleted(u1)

        val a1 = store.beginAssistantTurn("sess-1")
        store.markTurnCompleted(a1, "It's 4.")

        val u2 = store.persistUserTurn("sess-1", "And 3+3?")
        store.markUserTurnCompleted(u2)

        // Service dies mid-stream
        val a2 = store.beginAssistantTurn("sess-1")
        // a2 is left as STREAMING

        // New user turn was pending when crash happened
        val u3 = store.persistUserTurn("sess-1", "Hello?")
        // u3 is left as PENDING

        // --- Recovery starts ---
        val cleaned = store.cleanupInterruptedTurns("sess-1")
        assertEquals(1, cleaned) // a2 removed

        val replay = store.getReplayHistory("sess-1", 10000)!!

        // Should have 3 completed turns: u1, a1, u2
        assertEquals(3, replay.turns.size)
        assertEquals("What is 2+2?", replay.turns[0].textContent)
        assertEquals("It's 4.", replay.turns[1].textContent)
        assertEquals("And 3+3?", replay.turns[2].textContent)

        // Pending user turn available for re-send
        assertNotNull(replay.pendingUserTurn)
        assertEquals("Hello?", replay.pendingUserTurn!!.textContent)
    }

    @Test
    fun `recovery with token budget truncates old history`() = runTest {
        store.persistConversation("sess-1", defaultConfig.copy(systemPrompt = null))

        // Build 10 completed exchanges, each user turn is 20 chars → 5 tokens
        val turnIds = mutableListOf<String>()
        for (i in 0 until 10) {
            val uid = store.persistUserTurn("sess-1", "a".repeat(20))
            store.markUserTurnCompleted(uid)
            turnIds.add(uid)
        }

        // Budget for only 3 turns: 3 * 5 = 15 tokens
        val replay = store.getReplayHistory("sess-1", 15)!!
        assertEquals(3, replay.turns.size)

        // Should be the 3 most recent turns in chronological order
        assertEquals(turnIds[7], replay.turns[0].turnId)
        assertEquals(turnIds[8], replay.turns[1].turnId)
        assertEquals(turnIds[9], replay.turns[2].turnId)
    }
}
