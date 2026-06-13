package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.ServiceCapabilities
import com.adsamcik.mindlayer.ToolResult
import com.adsamcik.mindlayer.sdk.db.MindlayerDatabase
import com.adsamcik.mindlayer.shared.StreamEvent
import com.adsamcik.mindlayer.shared.StreamEventType
import com.adsamcik.mindlayer.shared.StreamHeader
import com.adsamcik.mindlayer.shared.StreamProtocol
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.slot
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicInteger

/**
 * Verifies the automatic tool-call loop: when `infer { outputTools(...);
 * onToolCall(handler) }` is used, each streamed [InferenceEvent.ToolCall] is
 * answered by invoking the handler and submitting the result over AIDL, and the
 * service's continuation tokens are surfaced — all on the same request stream.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerInferToolLoopTest {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    private lateinit var db: MindlayerDatabase
    private lateinit var mockService: IMindlayerService
    private lateinit var mockConnection: ConnectionManager
    private lateinit var mindlayer: MindlayerImpl

    private val weatherTool = ToolSpec(
        name = "get_weather",
        description = "Get weather for a city",
        parametersSchema = JsonSchema.parse(
            """{"type":"object","properties":{"city":{"type":"string"}}}""",
        ),
    )

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
            // v0 baseline lacks FEATURE_DETAILED_CANCEL, so submitToolResultDetailed
            // routes through the legacy submitToolResult AIDL we capture below.
            every { capabilities } returns ServiceCapabilities.v0Baseline()
        }

        mockConnection = mockk(relaxed = true) {
            every { getService() } returns mockService
            every { requireService() } returns mockService
            every { state } returns MutableStateFlow(ConnectionState.CONNECTED)
            io.mockk.coEvery { awaitConnected(any()) } returns mockService
            io.mockk.coEvery { awaitConnected() } returns mockService
        }

        mindlayer = buildMindlayer(mockConnection)
    }

    @After
    fun tearDown() {
        db.close()
        MindlayerDatabase.clearInstance()
        unmockkAll()
    }

    private fun buildMindlayer(conn: ConnectionManager): MindlayerImpl {
        val ctor = MindlayerImpl::class.java.getDeclaredConstructor(
            ConnectionManager::class.java,
            HistoryStore::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(conn, null)
    }

    /** Stub `infer` to stream: Started(header) → ToolCall → continuation deltas → Done. */
    private fun stubInferEmits(vararg events: StreamEvent) {
        every { mockService.infer(any(), any(), any(), any()) } answers {
            val pfd = arg<ParcelFileDescriptor>(3)
            val frames = buildList {
                add(StreamHeader(protocol = StreamProtocol.V3, requestId = "req"))
                addAll(events)
            }
            writeFramesTo(pfd, frames)
        }
    }

    @Test
    fun `onToolCall handler is invoked and result submitted, continuation streamed`() = runTest {
        stubInferEmits(
            toolCallEvent(seq = 1, name = "get_weather", args = """{"city":"Prague"}""", callId = "call-1"),
            textDeltaEvent(seq = 2, text = "It is sunny in Prague."),
            doneEvent(seq = 3),
        )
        val resultSlot = slot<ToolResult>()
        every { mockService.submitToolResult(any(), capture(resultSlot)) } returns Unit

        var seenTool: ToolCall? = null
        val handle = mindlayer.infer {
            session("s1")
            text("What is the weather in Prague?")
            outputTools(listOf(weatherTool))
            onToolCall { call ->
                seenTool = call
                """{"tempC":21,"sky":"clear"}"""
            }
        }
        val text = (handle as InferenceHandle.Text).awaitText()

        // Handler saw the streamed tool call verbatim.
        assertEquals("get_weather", seenTool?.name)
        assertEquals("call-1", seenTool?.callId)
        assertEquals("""{"city":"Prague"}""", seenTool?.argsJson)

        // Handler result was submitted back over AIDL for the same call.
        verify(exactly = 1) { mockService.submitToolResult(any(), any()) }
        assertEquals("call-1", resultSlot.captured.callId)
        assertEquals("get_weather", resultSlot.captured.toolName)
        assertEquals("""{"tempC":21,"sky":"clear"}""", resultSlot.captured.resultJson)

        // Post-tool continuation tokens are surfaced.
        assertEquals("It is sunny in Prague.", text)
    }

    @Test
    fun `each tool call in a multi-call turn is answered`() = runTest {
        stubInferEmits(
            toolCallEvent(seq = 1, name = "get_weather", args = """{"city":"Prague"}""", callId = "c1"),
            toolCallEvent(seq = 2, name = "get_weather", args = """{"city":"Brno"}""", callId = "c2"),
            textDeltaEvent(seq = 3, text = "done"),
            doneEvent(seq = 4),
        )
        every { mockService.submitToolResult(any(), any()) } returns Unit
        val calls = AtomicInteger(0)

        val handle = mindlayer.infer {
            session("s1")
            text("weather in Prague and Brno?")
            outputTools(listOf(weatherTool))
            onToolCall { calls.incrementAndGet(); "{}" }
        }
        (handle as InferenceHandle.Text).awaitText()

        assertEquals(2, calls.get())
        verify(exactly = 2) { mockService.submitToolResult(any(), any()) }
    }

    @Test
    fun `handler failure terminates the stream with a tool-handler error`() = runTest {
        stubInferEmits(
            toolCallEvent(seq = 1, name = "get_weather", args = "{}", callId = "call-x"),
            textDeltaEvent(seq = 2, text = "should-not-reach"),
            doneEvent(seq = 3),
        )

        val handle = mindlayer.infer {
            session("s1")
            text("weather?")
            outputTools(listOf(weatherTool))
            onToolCall { error("tool blew up") }
        }

        val ex = runCatching { (handle as InferenceHandle.Text).awaitText() }.exceptionOrNull()
        assertTrue("expected a MindlayerException, got $ex", ex is MindlayerException)
        assertEquals("TOOL_HANDLER_FAILED", (ex as MindlayerException).codeName)
        // The inference was asked to stop.
        verify(exactly = 1) { mockService.cancelInference(any()) }
        // No result was submitted for the failed call.
        verify(exactly = 0) { mockService.submitToolResult(any(), any()) }
    }

    @Test
    fun `without onToolCall the tool call surfaces but is not auto-answered`() = runTest {
        stubInferEmits(
            toolCallEvent(seq = 1, name = "get_weather", args = "{}", callId = "call-noauto"),
            doneEvent(seq = 2),
        )

        val handle = mindlayer.infer {
            session("s1")
            text("weather?")
            outputTools(listOf(weatherTool))
        }
        val events = handle.events.toList()

        assertTrue(events.any { it is InferenceEvent.ToolCall && it.callId == "call-noauto" })
        verify(exactly = 0) { mockService.submitToolResult(any(), any()) }
    }

    // ── frame helpers (mirror the wire format TokenStreamReader consumes) ────

    private fun toolCallEvent(seq: Long, name: String, args: String, callId: String): StreamEvent =
        StreamEvent(
            seq = seq,
            type = StreamEventType.TOOL_CALL,
            tsMs = 0L,
            payload = buildJsonObject {
                put("name", name)
                put("args", args)
                put("callId", callId)
            },
        )

    private fun textDeltaEvent(seq: Long, text: String): StreamEvent =
        StreamEvent(
            seq = seq,
            type = StreamEventType.TOKEN_DELTA,
            tsMs = 0L,
            payload = buildJsonObject { put("text", text) },
        )

    private fun doneEvent(seq: Long): StreamEvent =
        StreamEvent(
            seq = seq,
            type = StreamEventType.DONE,
            tsMs = 0L,
            payload = buildJsonObject { put("finish_reason", "success") },
        )

    private fun writeFramesTo(pfd: ParcelFileDescriptor, frames: List<Any>) {
        ParcelFileDescriptor.AutoCloseOutputStream(pfd).use { out ->
            frames.forEach { frame ->
                val text = when (frame) {
                    is StreamHeader -> json.encodeToString(StreamHeader.serializer(), frame)
                    is StreamEvent -> json.encodeToString(StreamEvent.serializer(), frame)
                    else -> error("Unsupported frame: ${frame::class.java.simpleName}")
                }
                val bytes = text.encodeToByteArray()
                val prefix = ByteBuffer.allocate(4)
                    .order(ByteOrder.LITTLE_ENDIAN)
                    .putInt(bytes.size)
                    .array()
                out.write(prefix)
                out.write(bytes)
                out.flush()
            }
        }
    }
}
