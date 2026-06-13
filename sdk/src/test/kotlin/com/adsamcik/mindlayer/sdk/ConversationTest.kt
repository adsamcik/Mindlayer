package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.sdk.db.MindlayerDatabase
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.Assert.assertTrue
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours

/**
 * Tests for the [Conversation] class and the simple [Mindlayer.chat] /
 * [Mindlayer.conversation] API surface.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConversationTest {

    private lateinit var db: MindlayerDatabase
    private lateinit var mockService: IMindlayerService
    private lateinit var mockConnection: ConnectionManager
    private lateinit var mindlayer: MindlayerImpl

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0

        val context = ApplicationProvider.getApplicationContext<Context>()

        MindlayerDatabase.clearInstance()
        db = Room.inMemoryDatabaseBuilder(context, MindlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        MindlayerDatabase.setInstance(db)

        mockService = mockk(relaxed = true) {
            every { createSession(any()) } returns "session-conv"
        }

        mockConnection = mockk(relaxed = true) {
            every { getService() } returns mockService
            every { requireService() } returns mockService
            every { state } returns MutableStateFlow(ConnectionState.CONNECTED)
            coEvery { awaitConnected(any()) } returns mockService
            coEvery { awaitConnected() } returns mockService
        }

        mindlayer = buildMindlayer(mockConnection, null)
    }

    @After
    fun tearDown() {
        db.close()
        MindlayerDatabase.clearInstance()
        unmockkAll()
    }

    // -- Helpers --------------------------------------------------------------

    private fun buildMindlayer(conn: ConnectionManager, historyStore: HistoryStore?): MindlayerImpl {
        val ctor = MindlayerImpl::class.java.getDeclaredConstructor(
            ConnectionManager::class.java,
            HistoryStore::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(conn, historyStore)
    }

    /** Make infer() immediately close the write-end so the stream ends. */
    private fun stubInferToClose() {
        every {
            mockService.infer(any(), any(), any(), any())
        } answers {
            arg<ParcelFileDescriptor>(3).close()
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Conversation
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `conversation_creates_session_lazily_on_first_chat`() = runTest {
        stubInferToClose()

        val conv = mindlayer.conversation {
            systemPrompt("You are a test bot.")
        }

        // Session has not been created yet
        verify(exactly = 0) { mockService.createSession(any()) }

        // First chat triggers session creation
        try { conv.chat("Hello") } catch (_: Exception) { /* stream empty — OK */ }

        verify(exactly = 1) { mockService.createSession(any()) }
    }

    @Test
    fun `conversation_passes_config_to_session`() = runTest {
        stubInferToClose()

        val configSlot = slot<SessionConfig>()
        every { mockService.createSession(capture(configSlot)) } returns "session-cfg"

        val conv = mindlayer.conversation {
            systemPrompt("Be concise.")
            maxTokens(2048)
            temperature(0.5f)
        }

        try { conv.chat("Hi") } catch (_: Exception) { }

        val cfg = configSlot.captured
        assertEquals("Be concise.", cfg.systemPrompt)
        assertEquals(2048, cfg.maxTokens)
        assertEquals(0.5f, cfg.samplerTemperature)
    }

    @Test
    fun `conversation_reuses_session_across_chats`() = runTest {
        stubInferToClose()

        val conv = mindlayer.conversation()

        try { conv.chat("First") } catch (_: Exception) { }
        try { conv.chat("Second") } catch (_: Exception) { }
        try { conv.chat("Third") } catch (_: Exception) { }

        // Session should only be created once
        verify(exactly = 1) { mockService.createSession(any()) }
    }

    @Test
    fun `conversation_close_destroys_session`() = runTest {
        stubInferToClose()

        val conv = mindlayer.conversation()
        try { conv.chat("Create session first") } catch (_: Exception) { }

        conv.close()

        verify(exactly = 1) { mockService.destroySession("session-conv") }
    }

    @Test
    fun `conversation_close_is_idempotent`() = runTest {
        stubInferToClose()

        val conv = mindlayer.conversation()
        try { conv.chat("Create session first") } catch (_: Exception) { }

        conv.close()
        conv.close()
        conv.close()

        // destroySession should only be called once
        verify(exactly = 1) { mockService.destroySession("session-conv") }
    }

    @Test
    fun `conversation_close_without_chat_does_not_destroy`() {
        val conv = mindlayer.conversation()
        conv.close()

        // No session was created, so no destroy call
        verify(exactly = 0) { mockService.destroySession(any()) }
    }

    @Test
    fun `conversation_chat_after_close_throws`() = runTest {
        stubInferToClose()

        val conv = mindlayer.conversation()
        conv.close()

        assertThrows(IllegalStateException::class.java) {
            runTest { conv.chat("Should fail") }
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Protocol violation
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `conversation chat without Done throws protocol violation`() = runTest {
        stubInferToClose()
        val conv = mindlayer.conversation()

        val ex = try {
            conv.chat("empty stream")
            throw AssertionError("expected MindlayerException")
        } catch (e: MindlayerException) {
            e
        }
        assertEquals(MindlayerErrorCode.PROTOCOL_VIOLATION, ex.code)
    }

    // ═════════════════════════════════════════════════════════════════════
    //  ConversationConfig DSL
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `conversationBuilder_defaults`() {
        val config = ConversationBuilder().apply {}.build()

        assertEquals(null, config.systemPrompt)
        assertEquals(4096, config.maxTokens)
        assertEquals(0.7f, config.temperature)
        assertEquals(40, config.topK)
        assertEquals(0.95f, config.topP)
        assertEquals(14.days, config.expiration)
    }

    @Test
    fun `conversationBuilder_validates_maxTokens`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationBuilder().apply { maxTokens(50) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationBuilder().apply { maxTokens(10000) }
        }
    }

    @Test
    fun `conversationBuilder_validates_temperature`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationBuilder().apply { temperature(-0.1f) }
        }
    }

    @Test
    fun `conversationBuilder_validates_topK`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationBuilder().apply { topK(0) }
        }
    }

    @Test
    fun `conversationBuilder_validates_topP`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationBuilder().apply { topP(0f) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationBuilder().apply { topP(1.5f) }
        }
    }

    @Test
    fun `conversation_factory_returns_conversation_instance`() {
        val conv = mindlayer.conversation {
            systemPrompt("Test")
        }
        // Just verify it returns without error and is the right type
        assertNotNull(conv)
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Expiration
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `conversationBuilder_custom_expiration`() {
        val config = ConversationBuilder().apply {
            expiration(7.days)
        }.build()

        assertEquals(7.days, config.expiration)
    }

    @Test
    fun `conversationBuilder_expirationDays`() {
        val config = ConversationBuilder().apply {
            expirationDays(7)
        }.build()

        assertEquals(7.days, config.expiration)
    }

    @Test
    fun `conversationBuilder_expiration_hours`() {
        val config = ConversationBuilder().apply {
            expiration(6.hours)
        }.build()

        assertEquals(6.hours, config.expiration)
    }

    @Test
    fun `conversationBuilder_validates_expiration_positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationBuilder().apply { expiration((-1).days) }
        }
    }

    @Test
    fun `conversationBuilder_validates_expirationDays_positive`() {
        assertThrows(IllegalArgumentException::class.java) {
            ConversationBuilder().apply { expirationDays(0) }
        }
        assertThrows(IllegalArgumentException::class.java) {
            ConversationBuilder().apply { expirationDays(-5) }
        }
    }

    @Test
    fun `conversation_passes_expiration_to_session`() = runTest {
        stubInferToClose()

        val configSlot = slot<SessionConfig>()
        every { mockService.createSession(capture(configSlot)) } returns "session-exp"

        val conv = mindlayer.conversation {
            expirationDays(7)
        }

        try { conv.chat("Hi") } catch (_: Exception) { }

        val cfg = configSlot.captured
        assertEquals(7L * 24 * 60 * 60 * 1000, cfg.expirationMs)
    }

    // ═════════════════════════════════════════════════════════════════════
    //  L14 — close() cancels in-flight handles (thread-safety fix)
    // ═════════════════════════════════════════════════════════════════════

    /**
     * Verifies that [Conversation.close] cancels every in-flight
     * [InferenceHandle] before calling [destroySession], ensuring the service
     * is not left generating tokens for a dead session.
     */
    @Test
    fun `close cancels in-flight handle before destroying session`() = runTest {
        val callOrder = java.util.concurrent.CopyOnWriteArrayList<String>()

        // Track order: cancel before destroy?
        every { mockService.cancelInference(any()) } answers { callOrder.add("cancel") }
        every { mockService.destroySession(any()) } answers { callOrder.add("destroy") }

        val conv = mindlayer.conversation()
        // chatStream creates the session and returns a cold-flow handle (not collected)
        val handle = conv.chatStream("a long streaming message")

        // Close the conversation — should cancel handle then destroy session.
        // close() is synchronous, so both IPC calls have been dispatched by the
        // time it returns. No polling/sleeping required.
        conv.close()

        assertTrue("handle must be marked cancelled after close()", (handle as InferenceHandleImpl).isCancelled)
        assertEquals(
            "cancel must happen before destroySession",
            listOf("cancel", "destroy"),
            callOrder,
        )
    }

    @Test
    fun `close is idempotent when called multiple times`() = runTest {
        stubInferToClose()
        val conv = mindlayer.conversation()
        try { conv.chat("setup") } catch (_: Exception) { }

        conv.close()
        conv.close()
        conv.close()

        verify(exactly = 1) { mockService.destroySession(any()) }
    }
}
