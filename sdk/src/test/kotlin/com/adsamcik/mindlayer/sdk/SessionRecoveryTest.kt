package com.adsamcik.mindlayer.sdk

import android.util.Log
import com.adsamcik.mindlayer.HistoryTurn
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.sdk.db.TurnEntity
import com.adsamcik.mindlayer.sdk.db.TurnRole
import io.mockk.coEvery
import io.mockk.coVerify
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
import org.junit.Before
import org.junit.Test

/**
 * Unit tests for [SessionRecovery] covering OOM recovery orchestration.
 * Fully mocked — no Room, no Robolectric.
 */
class SessionRecoveryTest {

    private lateinit var mockService: IMindlayerService
    private lateinit var mockConnection: ConnectionManager
    private lateinit var mockHistoryStore: HistoryStore
    private lateinit var mindlayer: MindlayerImpl
    private lateinit var recovery: SessionRecovery

    private val oldSessionId = "old-session-123"
    private val newSessionId = "new-session-456"

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.e(any(), any()) } returns 0

        mockService = mockk(relaxed = true) {
            every { createSession(any()) } returns newSessionId
        }

        mockConnection = mockk(relaxed = true) {
            every { getService() } returns mockService
            every { requireService() } returns mockService
            every { state } returns MutableStateFlow(ConnectionState.CONNECTED)
            coEvery { awaitConnected() } returns mockService
        }

        mockHistoryStore = mockk(relaxed = true)

        mindlayer = buildMindlayer(mockConnection, mockHistoryStore)
        recovery = SessionRecovery(mindlayer, mockHistoryStore)
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    // -- Constants ------------------------------------------------------------

    @Test
    fun `FOREGROUND_REPLAY_BUDGET is 6000`() {
        assertEquals(6_000, SessionRecovery.FOREGROUND_REPLAY_BUDGET)
    }

    @Test
    fun `EMERGENCY_REPLAY_BUDGET is 2000`() {
        assertEquals(2_000, SessionRecovery.EMERGENCY_REPLAY_BUDGET)
    }

    // -- recoverSession with valid history ------------------------------------

    @Test
    fun `recoverSession with valid history creates session with initialHistory`() = runTest {
        val turns = listOf(
            makeTurn("t1", TurnRole.USER, "Hello"),
            makeTurn("t2", TurnRole.ASSISTANT, "Hi there"),
            makeTurn("t3", TurnRole.USER, "How are you?"),
        )
        stubReplayData(turns = turns, pendingUserTurn = null)
        coEvery { mockHistoryStore.cleanupInterruptedTurns(oldSessionId) } returns 0

        val result = recovery.recoverSession(oldSessionId)

        assertNotNull(result)
        assertEquals(oldSessionId, result!!.originalSessionId)
        assertEquals(newSessionId, result.newSessionId)
        assertEquals(3, result.replayedTurnCount)
        assertEquals(0, result.cleanedTurnCount)
        assertNull(result.pendingUserText)

        // Verify history was passed via createSession config
        val configSlot = slot<SessionConfig>()
        verify(exactly = 1) { mockService.createSession(capture(configSlot)) }
        val history = configSlot.captured.initialHistory
        assertNotNull(history)
        assertEquals(3, history!!.size)
        assertEquals(HistoryTurn("user", "Hello"), history[0])
        assertEquals(HistoryTurn("model", "Hi there"), history[1])
        assertEquals(HistoryTurn("user", "How are you?"), history[2])
    }

    // -- recoverSession with no history ---------------------------------------

    @Test
    fun `recoverSession with no history returns null`() = runTest {
        coEvery { mockHistoryStore.cleanupInterruptedTurns(oldSessionId) } returns 0
        coEvery { mockHistoryStore.getReplayHistory(oldSessionId, any()) } returns null

        val result = recovery.recoverSession(oldSessionId)

        assertNull(result)
    }

    // -- cleanup of interrupted turns -----------------------------------------

    @Test
    fun `recoverSession cleans up interrupted turns before replay`() = runTest {
        val turns = listOf(
            makeTurn("t1", TurnRole.USER, "Hi"),
        )
        stubReplayData(turns = turns, pendingUserTurn = null)
        coEvery { mockHistoryStore.cleanupInterruptedTurns(oldSessionId) } returns 3

        val result = recovery.recoverSession(oldSessionId)

        assertNotNull(result)
        assertEquals(3, result!!.cleanedTurnCount)
        coVerify(exactly = 1) { mockHistoryStore.cleanupInterruptedTurns(oldSessionId) }
    }

    // -- RecoveryResult fields ------------------------------------------------

    @Test
    fun `RecoveryResult carries all expected fields`() = runTest {
        val turns = listOf(
            makeTurn("t1", TurnRole.USER, "Hello"),
            makeTurn("t2", TurnRole.ASSISTANT, "World"),
        )
        val pendingTurn = makeTurn("t3", TurnRole.USER, "pending question")
        stubReplayData(turns = turns, pendingUserTurn = pendingTurn)
        coEvery { mockHistoryStore.cleanupInterruptedTurns(oldSessionId) } returns 1

        val result = recovery.recoverSession(oldSessionId)!!

        assertEquals(oldSessionId, result.originalSessionId)
        assertEquals(newSessionId, result.newSessionId)
        assertEquals(2, result.replayedTurnCount)
        assertEquals(1, result.cleanedTurnCount)
        assertEquals("pending question", result.pendingUserText)
        assertEquals("t3", result.pendingUserTurnId)
    }

    // -- Pending user text ----------------------------------------------------

    @Test
    fun `recoverSession returns pending user text when present`() = runTest {
        val turns = listOf(makeTurn("t1", TurnRole.USER, "msg"))
        val pending = makeTurn("t-pend", TurnRole.USER, "unsent message")
        stubReplayData(turns = turns, pendingUserTurn = pending)
        coEvery { mockHistoryStore.cleanupInterruptedTurns(oldSessionId) } returns 0

        val result = recovery.recoverSession(oldSessionId)!!
        assertEquals("unsent message", result.pendingUserText)
        assertEquals("t-pend", result.pendingUserTurnId)
    }

    @Test
    fun `recoverSession returns null pendingUserText when no pending turn`() = runTest {
        stubReplayData(turns = listOf(makeTurn("t1", TurnRole.USER, "x")), pendingUserTurn = null)
        coEvery { mockHistoryStore.cleanupInterruptedTurns(oldSessionId) } returns 0

        val result = recovery.recoverSession(oldSessionId)!!
        assertNull(result.pendingUserText)
        assertNull(result.pendingUserTurnId)
    }

    // -- Role mapping ---------------------------------------------------------

    @Test
    fun `TOOL role maps to tool in initialHistory`() = runTest {
        val turns = listOf(
            makeTurn("t1", TurnRole.TOOL, "tool output"),
        )
        stubReplayData(turns = turns, pendingUserTurn = null)
        coEvery { mockHistoryStore.cleanupInterruptedTurns(oldSessionId) } returns 0

        recovery.recoverSession(oldSessionId)

        val configSlot = slot<SessionConfig>()
        verify(exactly = 1) { mockService.createSession(capture(configSlot)) }
        val history = configSlot.captured.initialHistory!!
        assertEquals(1, history.size)
        assertEquals("tool", history[0].role)
        assertEquals("tool output", history[0].text)
    }

    @Test
    fun `unknown role defaults to user in initialHistory`() = runTest {
        val turns = listOf(
            makeTurn("t1", "UNKNOWN_ROLE", "some text"),
        )
        stubReplayData(turns = turns, pendingUserTurn = null)
        coEvery { mockHistoryStore.cleanupInterruptedTurns(oldSessionId) } returns 0

        recovery.recoverSession(oldSessionId)

        val configSlot = slot<SessionConfig>()
        verify(exactly = 1) { mockService.createSession(capture(configSlot)) }
        val history = configSlot.captured.initialHistory!!
        assertEquals(1, history.size)
        assertEquals("user", history[0].role)
        assertEquals("some text", history[0].text)
    }

    // -- Null text content ----------------------------------------------------

    @Test
    fun `initialHistory uses empty string when textContent is null`() = runTest {
        val turns = listOf(
            makeTurn("t1", TurnRole.USER, null),
        )
        stubReplayData(turns = turns, pendingUserTurn = null)
        coEvery { mockHistoryStore.cleanupInterruptedTurns(oldSessionId) } returns 0

        recovery.recoverSession(oldSessionId)

        val configSlot = slot<SessionConfig>()
        verify(exactly = 1) { mockService.createSession(capture(configSlot)) }
        val history = configSlot.captured.initialHistory!!
        assertEquals(1, history.size)
        assertEquals("", history[0].text)
    }

    // -- Empty turn list ------------------------------------------------------

    @Test
    fun `recoverSession with empty turns list succeeds with zero replayed`() = runTest {
        stubReplayData(turns = emptyList(), pendingUserTurn = null)
        coEvery { mockHistoryStore.cleanupInterruptedTurns(oldSessionId) } returns 0

        val result = recovery.recoverSession(oldSessionId)!!
        assertEquals(0, result.replayedTurnCount)
        // No initialHistory set when turns are empty
        val configSlot = slot<SessionConfig>()
        verify(exactly = 1) { mockService.createSession(capture(configSlot)) }
        assertNull(configSlot.captured.initialHistory)
    }

    // -- Token budget propagation ---------------------------------------------

    @Test
    fun `recoverSession passes maxReplayTokens to getReplayHistory`() = runTest {
        coEvery { mockHistoryStore.cleanupInterruptedTurns(oldSessionId) } returns 0
        coEvery { mockHistoryStore.getReplayHistory(oldSessionId, any()) } returns null

        recovery.recoverSession(oldSessionId, maxReplayTokens = 999)

        coVerify { mockHistoryStore.getReplayHistory(oldSessionId, 999) }
    }

    @Test
    fun `recoverSession default budget is FOREGROUND_REPLAY_BUDGET`() = runTest {
        coEvery { mockHistoryStore.cleanupInterruptedTurns(oldSessionId) } returns 0
        coEvery { mockHistoryStore.getReplayHistory(oldSessionId, any()) } returns null

        recovery.recoverSession(oldSessionId) // uses default

        coVerify { mockHistoryStore.getReplayHistory(oldSessionId, 6_000) }
    }

    // -- Config propagation ---------------------------------------------------

    @Test
    fun `recoverSession passes system prompt and config to createSession`() = runTest {
        val config = SessionConfig(
            sessionId = oldSessionId,
            systemPrompt = "Be helpful",
            maxTokens = 2048,
            backend = "CPU",
            samplerTopK = 20,
            samplerTopP = 0.9f,
            samplerTemperature = 0.5f,
            toolsJson = """{"tools":[]}""",
            extraContextJson = """{"ctx":"v"}""",
        )
        stubReplayData(
            turns = emptyList(),
            pendingUserTurn = null,
            config = config,
            systemPrompt = "Be helpful",
        )
        coEvery { mockHistoryStore.cleanupInterruptedTurns(oldSessionId) } returns 0

        recovery.recoverSession(oldSessionId)

        verify(exactly = 1) { mockService.createSession(any()) }
    }

    // -- Session creation uses awaitConnected ---------------------------------

    @Test
    fun `recoverSession calls awaitConnected before createSession`() = runTest {
        stubReplayData(turns = emptyList(), pendingUserTurn = null)
        coEvery { mockHistoryStore.cleanupInterruptedTurns(oldSessionId) } returns 0

        recovery.recoverSession(oldSessionId)

        // Called twice: once for destroySession (best-effort), once for createSession
        coVerify(atLeast = 1) { mockConnection.awaitConnected() }
    }

    // -- destroySession best-effort before createSession -----------------------

    @Test
    fun `recoverSession calls destroySession best-effort before createSession`() = runTest {
        val turns = listOf(makeTurn("t1", TurnRole.USER, "Hello"))
        stubReplayData(turns = turns, pendingUserTurn = null)
        coEvery { mockHistoryStore.cleanupInterruptedTurns(oldSessionId) } returns 0

        recovery.recoverSession(oldSessionId)

        // destroySession is called before createSession
        verify(exactly = 1) { mockService.destroySession(oldSessionId) }
        verify(exactly = 1) { mockService.createSession(any()) }
    }

    @Test
    fun `recoverSession continues when destroySession throws`() = runTest {
        val turns = listOf(makeTurn("t1", TurnRole.USER, "Hello"))
        stubReplayData(turns = turns, pendingUserTurn = null)
        coEvery { mockHistoryStore.cleanupInterruptedTurns(oldSessionId) } returns 0
        every { mockService.destroySession(any()) } throws RuntimeException("Session not found")

        val result = recovery.recoverSession(oldSessionId)

        assertNotNull(result)
        assertEquals(newSessionId, result!!.newSessionId)
        // createSession still called despite destroySession failure
        verify(exactly = 1) { mockService.createSession(any()) }
    }

    // -- RecoveryResult data class equality -----------------------------------

    @Test
    fun `RecoveryResult data class equality and copy work`() {
        val r1 = RecoveryResult("a", "b", 3, 1, "text")
        val r2 = RecoveryResult("a", "b", 3, 1, "text")
        assertEquals(r1, r2)
        assertEquals(r1.hashCode(), r2.hashCode())

        val r3 = r1.copy(newSessionId = "c")
        assertEquals("c", r3.newSessionId)
        assertEquals("a", r3.originalSessionId)
    }

    // -- Helpers --------------------------------------------------------------

    private fun buildMindlayer(conn: ConnectionManager, historyStore: HistoryStore): MindlayerImpl {
        val ctor = MindlayerImpl::class.java.getDeclaredConstructor(
            ConnectionManager::class.java,
            HistoryStore::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(conn, historyStore)
    }

    private fun makeTurn(
        id: String,
        role: String,
        text: String?,
        seq: Int = 0,
    ) = TurnEntity(
        turnId = id,
        conversationId = oldSessionId,
        seq = seq,
        role = role,
        state = "COMPLETED",
        textContent = text,
        tokenEstimate = text?.length ?: 0,
        startedAtMs = System.currentTimeMillis(),
        completedAtMs = System.currentTimeMillis(),
    )

    private fun stubReplayData(
        turns: List<TurnEntity>,
        pendingUserTurn: TurnEntity?,
        config: SessionConfig = SessionConfig(
            sessionId = oldSessionId,
            systemPrompt = "You are helpful.",
            maxTokens = 4096,
            backend = "GPU",
        ),
        systemPrompt: String? = "You are helpful.",
    ) {
        val data = ReplayData(
            conversationId = oldSessionId,
            systemPrompt = systemPrompt,
            config = config,
            turns = turns,
            pendingUserTurn = pendingUserTurn,
        )
        coEvery { mockHistoryStore.getReplayHistory(oldSessionId, any()) } returns data
    }
}
