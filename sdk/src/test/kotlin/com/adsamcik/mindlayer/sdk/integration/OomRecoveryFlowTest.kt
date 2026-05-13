package com.adsamcik.mindlayer.sdk.integration

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.HistoryTurn
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.sdk.ConnectionManager
import com.adsamcik.mindlayer.sdk.ConnectionState
import com.adsamcik.mindlayer.sdk.HistoryPolicy
import com.adsamcik.mindlayer.sdk.HistoryStore
import com.adsamcik.mindlayer.sdk.Mindlayer
import com.adsamcik.mindlayer.sdk.SessionRecovery
import com.adsamcik.mindlayer.sdk.db.ConversationEntity
import com.adsamcik.mindlayer.sdk.db.MindlayerDatabase
import com.adsamcik.mindlayer.sdk.db.TurnEntity
import com.adsamcik.mindlayer.sdk.db.TurnRole
import com.adsamcik.mindlayer.sdk.db.TurnState
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
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

/**
 * Integration tests for the full OOM recovery chain:
 * Room (real) → HistoryStore (real) → SessionRecovery (real) → IMindlayerService (mocked).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OomRecoveryFlowTest {

    private lateinit var db: MindlayerDatabase
    private lateinit var store: HistoryStore
    private lateinit var mockService: IMindlayerService
    private lateinit var mockConnection: ConnectionManager
    private lateinit var mindlayer: Mindlayer
    private lateinit var recovery: SessionRecovery

    private val sessionId = "test-session-001"

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        val context = ApplicationProvider.getApplicationContext<Context>()

        MindlayerDatabase.clearInstance()
        db = Room.inMemoryDatabaseBuilder(context, MindlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        MindlayerDatabase.setInstance(db)

        store = HistoryStore(context, HistoryPolicy.FULL_CONTENT)

        // Mock AIDL service
        mockService = mockk(relaxed = true) {
            every { createSession(any()) } returns sessionId
        }

        // Mock ConnectionManager → returns our mock service
        mockConnection = mockk(relaxed = true) {
            every { getService() } returns mockService
            every { requireService() } returns mockService
            every { state } returns MutableStateFlow(ConnectionState.CONNECTED)
            coEvery { awaitConnected() } returns mockService
        }

        // Build Mindlayer via reflection (private constructor)
        mindlayer = buildMindlayer(mockConnection, store)
        recovery = SessionRecovery(mindlayer, store)
    }

    @After
    fun tearDown() {
        db.close()
        MindlayerDatabase.clearInstance()
        unmockkAll()
    }

    // -- Helpers -------------------------------------------------------------

    private fun buildMindlayer(conn: ConnectionManager, historyStore: HistoryStore): Mindlayer {
        val ctor = Mindlayer::class.java.getDeclaredConstructor(
            ConnectionManager::class.java,
            HistoryStore::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(conn, historyStore)
    }

    private suspend fun persistConversation(
        id: String = sessionId,
        systemPrompt: String? = "You are helpful.",
        toolsJson: String? = null,
        extraContextJson: String? = null,
    ) {
        store.persistConversation(
            id,
            SessionConfig(
                sessionId = id,
                systemPrompt = systemPrompt,
                maxTokens = 4096,
                backend = "GPU",
                toolsJson = toolsJson,
                extraContextJson = extraContextJson,
            ),
        )
    }

    /**
     * Persist a completed user+assistant turn pair.
     */
    private suspend fun persistTurnPair(
        seq: Int,
        userText: String,
        assistantText: String,
    ) {
        val now = System.currentTimeMillis()
        db.turnDao().upsert(
            TurnEntity(
                turnId = "u-$seq",
                conversationId = sessionId,
                seq = seq * 2,
                role = TurnRole.USER,
                state = TurnState.COMPLETED,
                textContent = userText,
                tokenEstimate = userText.length / 4,
                startedAtMs = now,
                completedAtMs = now,
            ),
        )
        db.turnDao().upsert(
            TurnEntity(
                turnId = "a-$seq",
                conversationId = sessionId,
                seq = seq * 2 + 1,
                role = TurnRole.ASSISTANT,
                state = TurnState.COMPLETED,
                textContent = assistantText,
                tokenEstimate = assistantText.length / 4,
                startedAtMs = now,
                completedAtMs = now,
            ),
        )
    }

    // ========================================================================
    //  Tests
    // ========================================================================

    @Test
    fun `recovery_fullFlow`() = runTest {
        persistConversation()

        // Persist 5 user+assistant turn pairs
        for (i in 0 until 5) {
            persistTurnPair(i, "User message $i", "Assistant reply $i")
        }

        val result = recovery.recoverSession(sessionId)

        assertNotNull(result)
        assertEquals(sessionId, result!!.originalSessionId)
        assertEquals(sessionId, result.newSessionId)
        assertEquals(10, result.replayedTurnCount) // 5 pairs = 10 turns

        // Verify createSession was called with initialHistory
        val configSlot = slot<SessionConfig>()
        verify(exactly = 1) { mockService.createSession(capture(configSlot)) }
        val history = configSlot.captured.initialHistory
        assertNotNull(history)
        assertEquals(10, history!!.size)

        // Verify roles are correct: user turns use "user", assistant turns use "model"
        for (i in 0 until 10) {
            val expectedRole = if (i % 2 == 0) "user" else "model"
            assertEquals("Turn $i role", expectedRole, history[i].role)
        }

        // destroySession should be called best-effort before createSession
        verify(exactly = 1) { mockService.destroySession(sessionId) }
    }

    @Test
    fun `recovery_pendingUserTurn_returned`() = runTest {
        persistConversation()

        // One completed pair + one PENDING user turn
        persistTurnPair(0, "First message", "First reply")

        val now = System.currentTimeMillis()
        db.turnDao().upsert(
            TurnEntity(
                turnId = "u-pending",
                conversationId = sessionId,
                seq = 2,
                role = TurnRole.USER,
                state = TurnState.PENDING,
                textContent = "Lost in transit",
                tokenEstimate = 4,
                startedAtMs = now,
            ),
        )

        val result = recovery.recoverSession(sessionId)

        assertNotNull(result)
        assertEquals("Lost in transit", result!!.pendingUserText)
        assertEquals("u-pending", result.pendingUserTurnId)

        result.markPendingUserResolved(recovery)
        assertNull(db.turnDao().firstPendingUserTurn(sessionId))
    }

    @Test
    fun `recovery_interruptedAssistantTurns_cleaned`() = runTest {
        persistConversation()

        // Completed user turn
        val now = System.currentTimeMillis()
        db.turnDao().upsert(
            TurnEntity(
                turnId = "u-0",
                conversationId = sessionId,
                seq = 0,
                role = TurnRole.USER,
                state = TurnState.COMPLETED,
                textContent = "Hello",
                tokenEstimate = 2,
                startedAtMs = now,
                completedAtMs = now,
            ),
        )

        // STREAMING assistant turn (interrupted by OOM)
        db.turnDao().upsert(
            TurnEntity(
                turnId = "a-streaming",
                conversationId = sessionId,
                seq = 1,
                role = TurnRole.ASSISTANT,
                state = TurnState.STREAMING,
                textContent = "Partial resp...",
                tokenEstimate = 4,
                startedAtMs = now,
            ),
        )

        val result = recovery.recoverSession(sessionId)

        assertNotNull(result)
        // The streaming assistant turn should be cleaned
        assertEquals(1, result!!.cleanedTurnCount)
        // Only the completed user turn is replayed
        assertEquals(1, result.replayedTurnCount)

        val configSlot = slot<SessionConfig>()
        verify(exactly = 1) { mockService.createSession(capture(configSlot)) }
        val history = configSlot.captured.initialHistory!!
        assertEquals(1, history.size)
        assertEquals(HistoryTurn("user", "Hello"), history[0])
    }

    @Test
    fun `recovery_tokenBudget_limitsReplay`() = runTest {
        persistConversation(systemPrompt = null) // no system prompt to simplify budget

        // Create 20 turns totaling ~10K tokens (each ~500 tokens ≈ 2000 chars)
        val now = System.currentTimeMillis()
        for (i in 0 until 20) {
            val text = "X".repeat(2000) // ~500 tokens at 4 chars/token
            db.turnDao().upsert(
                TurnEntity(
                    turnId = "t-$i",
                    conversationId = sessionId,
                    seq = i,
                    role = if (i % 2 == 0) TurnRole.USER else TurnRole.ASSISTANT,
                    state = TurnState.COMPLETED,
                    textContent = text,
                    tokenEstimate = 500,
                    startedAtMs = now,
                    completedAtMs = now,
                ),
            )
        }

        // Recover with 6000 token budget → can fit 12 turns (12 × 500 = 6000)
        val result = recovery.recoverSession(sessionId, maxReplayTokens = 6000)

        assertNotNull(result)
        assertTrue(
            "Should replay ≤ 12 turns (budget 6000 / 500 per turn), got ${result!!.replayedTurnCount}",
            result.replayedTurnCount <= 12,
        )
        assertTrue(
            "Should replay at least some turns, got ${result.replayedTurnCount}",
            result.replayedTurnCount > 0,
        )

        // Verify initialHistory size matches replayed count
        val configSlot = slot<SessionConfig>()
        verify(exactly = 1) { mockService.createSession(capture(configSlot)) }
        val history = configSlot.captured.initialHistory
        assertNotNull(history)
        assertEquals(result.replayedTurnCount, history!!.size)
    }

    @Test
    fun `recovery_emptyHistory_returnsNull`() = runTest {
        // No conversation persisted at all
        val result = recovery.recoverSession("nonexistent-session")
        assertNull(result)
    }

    @Test
    fun `recovery_systemPrompt_preserved`() = runTest {
        val prompt = "You are a quantum physics tutor. Explain concepts simply."
        persistConversation(systemPrompt = prompt)
        persistTurnPair(0, "What is entanglement?", "It's a quantum phenomenon...")

        recovery.recoverSession(sessionId)

        // Capture the SessionConfig passed to createSession
        val configSlot = slot<SessionConfig>()
        verify { mockService.createSession(capture(configSlot)) }

        assertEquals(prompt, configSlot.captured.systemPrompt)
    }

    @Test
    fun `recovery_toolConfig_preserved`() = runTest {
        val tools = """[{"type":"function","function":{"name":"get_weather"}}]"""
        persistConversation(toolsJson = tools)
        persistTurnPair(0, "What's the weather?", "Let me check...")

        recovery.recoverSession(sessionId)

        val configSlot = slot<SessionConfig>()
        verify { mockService.createSession(capture(configSlot)) }

        assertEquals(tools, configSlot.captured.toolsJson)
    }

    // === Edge-case tests ===

    @Test
    fun `recovery_destroySession_bestEffort`() = runTest {
        persistConversation()
        for (i in 0 until 5) {
            persistTurnPair(i, "User message $i", "Assistant reply $i")
        }

        // Make destroySession throw — recovery should continue
        every { mockService.destroySession(any()) } throws RuntimeException("Session not found")

        val result = recovery.recoverSession(sessionId)

        assertNotNull("recoverSession should succeed despite destroySession failure", result)
        assertEquals(sessionId, result!!.newSessionId)
        verify(exactly = 1) { mockService.createSession(any()) }
    }

    @Test
    fun `recovery_emptyTurns_succeeds`() = runTest {
        persistConversation(systemPrompt = "You are a helpful assistant.")
        // No turns persisted — conversation exists but has 0 completed turns

        val result = recovery.recoverSession(sessionId)

        assertNotNull(result)
        assertEquals(0, result!!.replayedTurnCount)
        assertEquals(sessionId, result.newSessionId)

        // Session was created but no turns replayed — initialHistory should be null
        verify(exactly = 1) { mockService.createSession(any()) }

        // System prompt preserved in the new session config
        val configSlot = slot<SessionConfig>()
        verify { mockService.createSession(capture(configSlot)) }
        assertEquals("You are a helpful assistant.", configSlot.captured.systemPrompt)
        assertNull(configSlot.captured.initialHistory)
    }

    @Test
    fun `recovery_duplicateRecovery_idempotent`() = runTest {
        persistConversation()
        persistTurnPair(0, "Hello", "Hi there")

        val result1 = recovery.recoverSession(sessionId)
        val result2 = recovery.recoverSession(sessionId)

        assertNotNull(result1)
        assertNotNull(result2)
        assertEquals(result1!!.originalSessionId, result2!!.originalSessionId)

        // Both calls should create a session (with initialHistory) and succeed
        verify(exactly = 2) { mockService.createSession(any()) }
    }
}
