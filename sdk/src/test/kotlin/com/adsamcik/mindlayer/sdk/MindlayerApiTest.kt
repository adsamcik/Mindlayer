package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.AudioTransfer
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.ImageTransfer
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.ServiceStatus
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.SessionInfo
import com.adsamcik.mindlayer.EngineInfo
import com.adsamcik.mindlayer.ToolResult
import com.adsamcik.mindlayer.sdk.db.MindlayerDatabase
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Tests for the [Mindlayer] public SDK API.
 *
 * Strategy: mock [IMindlayerService] (AIDL binder) and [ConnectionManager],
 * construct [Mindlayer] via reflection (private constructor), and verify that
 * the public methods pass the correct data through to the AIDL layer.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerApiTest {

    private lateinit var db: MindlayerDatabase
    private lateinit var store: HistoryStore
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

        store = HistoryStore(context, HistoryPolicy.FULL_CONTENT)

        mockService = mockk(relaxed = true) {
            every { createSession(any()) } returns "session-abc"
            every { status } returns ServiceStatus(
                isEngineLoaded = true,
                activeSessionCount = 0,
                activeInferenceCount = 0,
                backend = "GPU",
                thermalBand = "COOL",
                isForeground = false,
                uptimeMs = 0L,
            )
        }

        mockConnection = mockk(relaxed = true) {
            every { getService() } returns mockService
            every { requireService() } returns mockService
            every { state } returns MutableStateFlow(ConnectionState.CONNECTED)
            coEvery { awaitConnected(any()) } returns mockService
            coEvery { awaitConnected() } returns mockService
        }

        // Use null historyStore for API tests — we're testing AIDL wiring, not persistence
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

    // ═════════════════════════════════════════════════════════════════════
    //  Session management
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `createSession_callsAidlWithCorrectConfig`() = runTest {
        val configSlot = slot<SessionConfig>()
        every { mockService.createSession(capture(configSlot)) } returns "session-xyz"

        val result = mindlayer.createSessionInternal {
            systemPrompt("Be concise.")
            maxTokens(2048)
            backend("CPU")
            topK(30)
            topP(0.8f)
            temperature(0.5f)
        }

        assertEquals("session-xyz", result)
        val captured = configSlot.captured
        assertEquals("Be concise.", captured.systemPrompt)
        assertEquals(2048, captured.maxTokens)
        assertEquals("CPU", captured.backend)
        assertEquals(30, captured.samplerTopK)
        assertEquals(0.8f, captured.samplerTopP)
        assertEquals(0.5f, captured.samplerTemperature)
    }

    @Test
    fun `createSession_withDsl_buildsCorrectConfig`() = runTest {
        val configSlot = slot<SessionConfig>()
        every { mockService.createSession(capture(configSlot)) } returns "session-dsl"

        val toolsDef = """[{"name":"search","description":"web search"}]"""
        mindlayer.createSessionInternal {
            systemPrompt("You are an assistant")
            maxTokens(8192)
            backend("GPU")
            topK(50)
            topP(0.9f)
            temperature(1.0f)
            tools(toolsDef)
            extraContext("""{"key":"value"}""")
        }

        val cfg = configSlot.captured
        assertEquals("You are an assistant", cfg.systemPrompt)
        assertEquals(8192, cfg.maxTokens)
        assertEquals("GPU", cfg.backend)
        assertEquals(50, cfg.samplerTopK)
        assertEquals(0.9f, cfg.samplerTopP)
        assertEquals(1.0f, cfg.samplerTemperature)
        assertEquals(toolsDef, cfg.toolsJson)
        assertEquals("""{"key":"value"}""", cfg.extraContextJson)
    }

    @Test
    fun `createSession_defaultConfig_usesDefaults`() = runTest {
        val configSlot = slot<SessionConfig>()
        every { mockService.createSession(capture(configSlot)) } returns "session-def"

        mindlayer.createSessionInternal { }

        val cfg = configSlot.captured
        assertEquals(4096, cfg.maxTokens)
        assertEquals("GPU", cfg.backend)
        assertEquals(40, cfg.samplerTopK)
        assertEquals(0.95f, cfg.samplerTopP)
        assertEquals(0.7f, cfg.samplerTemperature)
        assertEquals(null, cfg.systemPrompt)
        assertEquals(null, cfg.toolsJson)
    }

    @Test
    fun `destroySession_callsAidl`() = runTest {
        mindlayer.destroySession("sess-42")
        verify(exactly = 1) { mockService.destroySession("sess-42") }
    }

    @Test
    fun `listSessions_returnsAidlResult`() = runTest {
        val info1 = SessionInfo(
            sessionId = "s1", backend = "GPU", maxTokens = 4096,
            currentTokenCount = 100, turnCount = 5,
            createdAtMs = 1000, lastAccessedAtMs = 2000, isStreaming = false,
        )
        val info2 = SessionInfo(
            sessionId = "s2", backend = "CPU", maxTokens = 2048,
            currentTokenCount = 50, turnCount = 2,
            createdAtMs = 3000, lastAccessedAtMs = 4000, isStreaming = true,
        )
        every { mockService.listSessions() } returns listOf(info1, info2)

        val result = mindlayer.listSessions()
        assertEquals(2, result.size)
        assertEquals("s1", result[0].sessionId)
        assertEquals("s2", result[1].sessionId)
    }

    @Test
    fun `getSessionInfo_returnsAidlResult`() = runTest {
        val info = SessionInfo(
            sessionId = "s-info", backend = "GPU", maxTokens = 4096,
            currentTokenCount = 200, turnCount = 10,
            createdAtMs = 5000, lastAccessedAtMs = 6000, isStreaming = false,
        )
        every { mockService.getSessionInfo("s-info") } returns info

        val result = mindlayer.getSessionInfo("s-info")
        assertEquals("s-info", result.sessionId)
        assertEquals(200, result.currentTokenCount)
        assertEquals(10, result.turnCount)
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Chat text-only
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `chat_createsReliablePipe`() = runTest {
        val pfdSlot = slot<ParcelFileDescriptor>()
        every {
            mockService.infer(any(), any(), any(), capture(pfdSlot))
        } answers {
            // Close the write end the service receives (simulating dup + close)
            pfdSlot.captured.close()
        }

        // Calling chat should create a pipe and pass write end to infer()
        val handle = mindlayer.streamInference("sess-1", "Hello")

        // Collect — will hit EOF immediately since mock closes write end
        val events = handle.events.toList()

        // Verify infer was called with a PFD
        verify(exactly = 1) { mockService.infer(any(), any(), any(), any()) }
        assertTrue(pfdSlot.isCaptured)
    }

    @Test
    fun `chat_passesCorrectRequestMeta`() = runTest {
        val metaSlot = slot<RequestMeta>()
        every {
            mockService.infer(capture(metaSlot), any(), any(), any())
        } answers {
            // Close the write end to allow flow completion
            val pfd = arg<ParcelFileDescriptor>(3)
            pfd.close()
        }

        val handle = mindlayer.streamInference("sess-meta", "Tell me a joke")
        handle.events.toList()

        val meta = metaSlot.captured
        assertEquals("sess-meta", meta.sessionId)
        assertEquals("Tell me a joke", meta.textContent)
        assertNotNull(meta.requestId)
        assertTrue(meta.requestId.isNotEmpty())
    }

    @Test
    fun `chat_awaitsConnectionBeforeReturningHandle`() = runTest {
        val handle = mindlayer.streamInference("sess-await", "Hello")

        assertNotNull(handle)
        coVerify(atLeast = 1) { mockConnection.awaitConnected() }
        verify(exactly = 0) { mockService.infer(any(), any(), any(), any()) }
    }

    @Test
    fun `chat_passesNullImageAndAudio`() = runTest {
        val imageSlot = slot<ImageTransfer>()
        val audioSlot = slot<AudioTransfer>()
        every {
            mockService.infer(any(), any(), any(), any())
        } answers {
            arg<ParcelFileDescriptor>(3).close()
        }

        mindlayer.streamInference("sess-1", "No media").events.toList()

        // infer called with null for image and audio
        verify(exactly = 1) {
            mockService.infer(any(), isNull(), isNull(), any())
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Chat with image
    // ═════════════════════════════════════════════════════════════════════

    @Ignore("Requires real Android FD internals for SharedMemory bitmap transfer")
    @Test
    fun `chatWithImage_createsImageTransfer`() = runTest {
        val imageSlot = slot<ImageTransfer>()
        every {
            mockService.infer(any(), capture(imageSlot), any(), any())
        } answers {
            arg<ParcelFileDescriptor>(3).close()
        }

        val bitmap = Bitmap.createBitmap(100, 80, Bitmap.Config.ARGB_8888)
        try {
            mindlayer.streamInference("sess-img", "Describe this", imageInputs = listOf(ImageInput.Bitmap(bitmap))).events.toList()

            assertTrue(imageSlot.isCaptured)
            val img = imageSlot.captured
            assertNotNull(img.requestId)
            assertNotNull(img.source)
        } finally {
            bitmap.recycle()
        }
    }

    @Ignore("Requires real Android FD internals for SharedMemory bitmap transfer")
    @Test
    fun `chatWithImage_passesTextAndImage`() = runTest {
        val metaSlot = slot<RequestMeta>()
        val imageSlot = slot<ImageTransfer>()
        every {
            mockService.infer(capture(metaSlot), capture(imageSlot), any(), any())
        } answers {
            arg<ParcelFileDescriptor>(3).close()
        }

        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        try {
            mindlayer.streamInference("sess-img2", "What is this?", imageInputs = listOf(ImageInput.Bitmap(bitmap))).events.toList()

            assertEquals("What is this?", metaSlot.captured.textContent)
            assertEquals("sess-img2", metaSlot.captured.sessionId)
            assertTrue(imageSlot.isCaptured)
            // Audio should be null
            verify { mockService.infer(any(), any(), isNull(), any()) }
        } finally {
            bitmap.recycle()
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Chat with audio
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `chatWithAudio_createsAudioTransfer`() = runTest {
        val audioSlot = slot<AudioTransfer>()
        every {
            mockService.infer(any(), any(), capture(audioSlot), any())
        } answers {
            arg<ParcelFileDescriptor>(3).close()
        }

        val tempFile = File.createTempFile("test_audio", ".wav")
        try {
            tempFile.writeBytes(ByteArray(100) { 0x42 })
            mindlayer.streamInference("sess-aud", "Transcribe this", audioFile = tempFile).events.toList()

            assertTrue(audioSlot.isCaptured)
            val audio = audioSlot.captured
            assertNotNull(audio.requestId)
            assertEquals("audio/wav", audio.mimeType)
            assertNotNull(audio.source)
        } finally {
            tempFile.delete()
        }
    }

    @Test
    fun `chatWithAudio_passesTextAndAudio`() = runTest {
        val metaSlot = slot<RequestMeta>()
        val audioSlot = slot<AudioTransfer>()
        every {
            mockService.infer(capture(metaSlot), any(), capture(audioSlot), any())
        } answers {
            arg<ParcelFileDescriptor>(3).close()
        }

        val tempFile = File.createTempFile("test_audio2", ".mp3")
        try {
            tempFile.writeBytes(ByteArray(50))
            mindlayer.streamInference("sess-aud2", "What was said?", audioFile = tempFile).events.toList()

            assertEquals("What was said?", metaSlot.captured.textContent)
            assertEquals("sess-aud2", metaSlot.captured.sessionId)
            assertTrue(audioSlot.isCaptured)
            assertEquals("audio/mpeg", audioSlot.captured.mimeType)
            // Image should be null
            verify { mockService.infer(any(), isNull(), any(), any()) }
        } finally {
            tempFile.delete()
        }
    }

    // ═════════════════════════════════════════════════════════════════════
    @Test
    fun `chatWithMedia closes media descriptors when binder call fails`() = runTest {
        val mediaPipe = ParcelFileDescriptor.createPipe()
        every { mockService.inferMulti(any(), any(), any()) } throws RemoteException("Service crashed")
        val part = com.adsamcik.mindlayer.MediaPart(
            requestId = "placeholder",
            kind = com.adsamcik.mindlayer.MediaPart.KIND_AUDIO,
            mimeType = "audio/wav",
            source = mediaPipe[0],
            isSharedMemory = false,
            payloadBytes = 1L,
        )

        try {
            try {
                mindlayer.streamInference("sess-media", "transcribe", mediaParts = listOf(part))
                fail("Expected MindlayerException")
            } catch (_: MindlayerException) {
                // Synchronous binder failure from inferMulti now surfaces as a
                // typed SERVICE_UNAVAILABLE; descriptors must still be closed.
            }

            assertFalse("media source must be closed after binder failure", mediaPipe[0].fileDescriptor.valid())
        } finally {
            try { mediaPipe[0].close() } catch (_: Exception) {}
            try { mediaPipe[1].close() } catch (_: Exception) {}
        }
    }

    //  Error handling
    // ═════════════════════════════════════════════════════════════════════

    @Test(expected = IllegalStateException::class)
    fun `chat_serviceNotConnected_throws`() = runTest {
        val disconnectedConn = mockk<ConnectionManager>(relaxed = true) {
            every { getService() } returns null
            every { requireService() } throws IllegalStateException(
                "MindlayerService is not connected (state=DISCONNECTED)"
            )
            every { state } returns MutableStateFlow(ConnectionState.DISCONNECTED)
            coEvery { awaitConnected() } throws IllegalStateException(
                "MindlayerService is not connected (state=DISCONNECTED)"
            )
        }

        val disconnectedMl = buildMindlayer(disconnectedConn, null)
        disconnectedMl.streamInference("sess-x", "Hello").events.toList()
    }

    @Test
    fun `chat_aidlThrows_propagatesError`() = runTest {
        every {
            mockService.infer(any(), any(), any(), any())
        } throws RemoteException("Service crashed")

        try {
            mindlayer.streamInference("sess-err", "Boom").events.toList()
            fail("expected MindlayerException")
        } catch (e: MindlayerException) {
            // Binder transport failures surface as a typed SERVICE_UNAVAILABLE
            // rather than leaking a raw RemoteException to callers.
            assertEquals(MindlayerErrorCode.SERVICE_UNAVAILABLE, e.code)
            assertTrue(e.cause is RemoteException)
        }
    }

    @Test
    fun `cancelInference_callsAidl`() = runTest {
        mindlayer.cancelInference("req-cancel-1")
        verify(exactly = 1) { mockService.cancelInference("req-cancel-1") }
    }

    @Test
    fun `submitToolResult_callsAidl`() = runTest {
        val resultSlot = slot<ToolResult>()
        every { mockService.submitToolResult(any(), capture(resultSlot)) } returns Unit

        mindlayer.submitToolResult("req-tool", "call-1", "search", """{"results":[]}""")

        verify(exactly = 1) { mockService.submitToolResult(eq("req-tool"), any()) }
        val captured = resultSlot.captured
        assertEquals("req-tool", captured.requestId)
        assertEquals("call-1", captured.callId)
        assertEquals("search", captured.toolName)
        assertEquals("""{"results":[]}""", captured.resultJson)
    }

    @Test
    fun `submitToolResult_withCallId_forwardsCallIdToAidl`() = runTest {
        val resultSlot = slot<ToolResult>()
        every { mockService.submitToolResult(any(), capture(resultSlot)) } returns Unit

        mindlayer.submitToolResult(
            requestId = "req-tool-2",
            callId = "call-abc123",
            toolName = "weather",
            resultJson = """{"temp":20}""",
        )

        verify(exactly = 1) { mockService.submitToolResult(eq("req-tool-2"), any()) }
        val captured = resultSlot.captured
        assertEquals("req-tool-2", captured.requestId)
        assertEquals("call-abc123", captured.callId)
        assertEquals("weather", captured.toolName)
        assertEquals("""{"temp":20}""", captured.resultJson)
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Service status
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `getStatus_returnsServiceStatus`() = runTest {
        val status = ServiceStatus(
            isEngineLoaded = true,
            activeSessionCount = 2,
            activeInferenceCount = 1,
            backend = "GPU",
            thermalBand = "NOMINAL",
            isForeground = true,
            uptimeMs = 60_000,
        )
        every { mockService.status } returns status

        val result = mindlayer.getStatus()
        assertEquals(true, result.isEngineLoaded)
        assertEquals(2, result.activeSessionCount)
        assertEquals("NOMINAL", result.thermalBand)
    }

    @Test
    fun `getEngineInfo_returnsEngineInfo`() = runTest {
        val info = EngineInfo(
            modelId = "llama",
            modelSizeBytes = 4_000_000_000,
            backend = "GPU",
            maxTokens = 8192,
            initTimeSeconds = 2.5f,
            lastPrefillToksPerSec = 120f,
            lastDecodeToksPerSec = 35f,
        )
        every { mockService.engineInfo } returns info

        val result = mindlayer.getEngineInfo()
        assertEquals("llama", result.modelId)
        assertEquals(8192, result.maxTokens)
        assertEquals(35f, result.lastDecodeToksPerSec)
    }

    @Test
    fun `EngineInfo preserves explicit modelId`() {
        val info = EngineInfo(
            modelId = "gemma-4-E2B-it",
            modelSizeBytes = 2_400_000_000,
            backend = "GPU",
            maxTokens = 4096,
            initTimeSeconds = 1.0f,
            lastPrefillToksPerSec = 0f,
            lastDecodeToksPerSec = 0f,
        )

        assertEquals("gemma-4-E2B-it", info.modelId)
    }

    // ═════════════════════════════════════════════════════════════════════
    //  Session wrapper (MindlayerSession)
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `mindlayerSession_delegatesToMindlayer`() = runTest {
        every {
            mockService.infer(any(), any(), any(), any())
        } answers {
            arg<ParcelFileDescriptor>(3).close()
        }

        val session = mindlayer.session("sess-wrapper")
        assertEquals("sess-wrapper", session.sessionId)

        // chat should delegate with correct sessionId
        val metaSlot = slot<RequestMeta>()
        every {
            mockService.infer(capture(metaSlot), any(), any(), any())
        } answers {
            arg<ParcelFileDescriptor>(3).close()
        }

        session.chat("Delegated message").events.toList()

        assertEquals("sess-wrapper", metaSlot.captured.sessionId)
        assertEquals("Delegated message", metaSlot.captured.textContent)
    }

    @Test
    fun `mindlayerSession_delete_callsDestroySession`() = runTest {
        val session = mindlayer.session("sess-destroy")
        session.delete()
        verify(exactly = 1) { mockService.destroySession("sess-destroy") }
    }

    @Test
    fun `connectionState_exposesConnectionManagerState`() {
        val stateFlow = MutableStateFlow(ConnectionState.CONNECTING)
        val conn = mockk<ConnectionManager>(relaxed = true) {
            every { state } returns stateFlow
        }
        val ml = buildMindlayer(conn, null)

        assertEquals(ConnectionState.CONNECTING, ml.connectionState.value)
        stateFlow.value = ConnectionState.CONNECTED
        assertEquals(ConnectionState.CONNECTED, ml.connectionState.value)
    }

    // ═════════════════════════════════════════════════════════════════════
    //  InferenceHandle
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `chat_returns_InferenceHandle_with_requestId`() = runTest {
        every {
            mockService.infer(any(), any(), any(), any())
        } answers {
            arg<ParcelFileDescriptor>(3).close()
        }

        val handle = mindlayer.streamInference("sess-handle", "Hello")
        assertNotNull(handle.requestId)
        assertTrue(handle.requestId.isNotEmpty())
        assertFalse((handle as InferenceHandleImpl).isCancelled)

        // Verify we can still collect events through the handle
        handle.events.toList()
    }

    @Test
    fun `InferenceHandle_exposes_requestId_immediately`() {
        val handle = InferenceHandleImpl("test-req-id", flowOf())
        assertEquals("test-req-id", handle.requestId)
        assertFalse(handle.isCancelled)
    }

    @Test
    fun `InferenceHandle_sync_cancel_is_idempotent`() = runTest {
        var cancelCount = 0
        val handle = InferenceHandleImpl("req", flowOf())
        handle.setSyncCancelCallback { cancelCount++ }
        handle.cancelSync()
        handle.cancelSync()
        handle.cancelSync()
        assertEquals(1, cancelCount)
        assertTrue(handle.isCancelled)
    }

    @Test
    fun `InferenceHandle_sync_cancel_calls_cancelInference_on_service`() = runTest {
        every {
            mockService.infer(any(), any(), any(), any())
        } answers {
            arg<ParcelFileDescriptor>(3).close()
        }

        val handle = mindlayer.streamInference("sess-cancel", "Cancel me") as InferenceHandleImpl
        handle.cancelSync()

        assertTrue(handle.isCancelled)
        coVerify(exactly = 1) { mockService.cancelInference(handle.requestId) }
    }

    // ═════════════════════════════════════════════════════════════════════
    //  M20 — persistHistory opt-in (privacy-by-default)
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `connect default persistHistory false gives null historyStore (historyCount is 0)`() = runTest {
        // buildMindlayer(conn, null) simulates connect(ctx, persistHistory=false)
        val ml = buildMindlayer(mockConnection, null)
        assertEquals(0, ml.historyCount())
        assertEquals(emptyList<ConversationSummary>(), ml.listHistory())
    }

    @Test
    fun `connect persistHistory true gives live historyStore`() = runTest {
        // buildMindlayer(conn, store) simulates connect(ctx, persistHistory=true)
        val ml = buildMindlayer(mockConnection, store)
        ml.createSessionInternal {}
        assertEquals(1, ml.historyCount())
    }

    @Test
    fun `eraseAllHistory clears all conversations`() = runTest {
        val ml = buildMindlayer(mockConnection, store)
        ml.createSessionInternal {}
        assertEquals(1, ml.historyCount())

        ml.eraseAllHistory()
        assertEquals(0, ml.historyCount())
    }

    @Test
    fun `eraseAllHistory is no-op when historyStore is null`() = runTest {
        // Should not throw when persistHistory=false
        val ml = buildMindlayer(mockConnection, null)
        ml.eraseAllHistory() // must not throw
        assertEquals(0, ml.historyCount())
    }
}
