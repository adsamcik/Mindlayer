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
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/**
 * Encoding-invariant regression for the orchestrator's tool-call argument
 * pipeline. The earlier revision targeted a private helper
 * `InferenceOrchestrator.encodeToolArguments(Any?)` (added in 25cee27,
 * "Continue hardening security controls") via reflection. That helper was
 * inlined and replaced by [InferenceOrchestrator]'s `acceptToolCalls`
 * F-036 path in cf5631c ("security: phase 2 — DoS, LLM safety, …"); the
 * encoding now happens via `gson.toJson(tc.arguments)` directly inside
 * that method, guarded by [InferenceOrchestrator.MAX_TOOL_ARGS_LEN].
 *
 * To keep the test honest — i.e. testing the encoding INVARIANT rather
 * than a brittle reflective method name — this class drives a real
 * inference end-to-end and asserts on the JSON `args` field of the
 * `tool_call` frame that the orchestrator emits to the SDK over the
 * pipe. That is the wire-level contract every co-signed client depends
 * on, so the test fails closed if the encoding is ever changed in a way
 * that breaks compatibility (escaping regressions, lost unicode,
 * primitives accidentally quoted, structure flattened, etc.).
 *
 * The litertlm `ToolCall.arguments` is typed `Map<String, Any>` (see
 * `litertlm-android-0.10.0` `ToolCall.kt`), so the production encoder
 * only ever sees a Map. The five cases below cover the encoding shapes
 * actually reachable from the model:
 *
 *  1. empty map → `{}` (matches the old "null/blank → {}" intent — the
 *     "empty result" invariant)
 *  2. simple `<String, String>` pair (matches the old "json string"
 *     intent — basic key/value encoding)
 *  3. nested map + list values (matches the old "json objects/maps"
 *     intent — structured types)
 *  4. special characters and non-BMP unicode round-trip cleanly
 *     (replaces the now-unreachable opaque-fallback case with a real
 *     escaping invariant)
 *  5. booleans and numbers emitted as JSON literals, not quoted strings
 *     (matches the old "primitives don't throw" intent — primitive type
 *     fidelity)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InferenceOrchestratorEncodingTest {

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
    private val requestCounter = AtomicInteger(0)

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
            every { engineManager } returns this@InferenceOrchestratorEncodingTest.engineManager
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

    // ---- Tests --------------------------------------------------------------

    @Test
    fun `encodes empty arguments map as empty json object`() {
        assertEquals("{}", encodedArgsFor(emptyMap()))
    }

    @Test
    fun `encodes simple string key-value map as json object`() {
        val encoded = encodedArgsFor(mapOf("city" to "Prague"))
        val obj = JSONObject(encoded)
        assertEquals(
            "Expected single key in $encoded",
            1,
            obj.length(),
        )
        assertEquals("Prague", obj.getString("city"))
    }

    @Test
    fun `encodes nested map and list arguments preserving structure`() {
        val encoded = encodedArgsFor(
            mapOf(
                "user" to mapOf("name" to "Alice", "age" to 30),
                "tags" to listOf("a", "b", "c"),
            )
        )
        val obj = JSONObject(encoded)
        val user = obj.getJSONObject("user")
        assertEquals("Alice", user.getString("name"))
        assertEquals(30, user.getInt("age"))
        val tags = obj.getJSONArray("tags")
        assertEquals(3, tags.length())
        assertEquals("a", tags.getString(0))
        assertEquals("b", tags.getString(1))
        assertEquals("c", tags.getString(2))
    }

    @Test
    fun `encodes special characters and unicode without corruption`() {
        // Mix of: required JSON escapes (quote, backslash, newline, tab,
        // control char), BMP unicode, and a non-BMP emoji surrogate pair.
        val tricky = "quote=\" backslash=\\ newline=\n tab=\t ctl=\u0001 jp=日本語 flag=\uD83C\uDDE8\uD83C\uDDFF"
        val encoded = encodedArgsFor(mapOf("q" to tricky))
        // Round-trip via JSONObject must restore the exact input string;
        // any escaping bug shows up as a mismatch here.
        assertEquals(tricky, JSONObject(encoded).getString("q"))
        // The raw wire form must NOT contain a literal newline or tab
        // (those have to be escaped to \n / \t for the frame to be a
        // valid JSON string).
        assertTrue(
            "Raw encoded payload must not contain literal newline; encoded=$encoded",
            !encoded.contains('\n'),
        )
        assertTrue(
            "Raw encoded payload must not contain literal tab; encoded=$encoded",
            !encoded.contains('\t'),
        )
    }

    @Test
    fun `encodes booleans and numbers as json literals not quoted strings`() {
        val encoded = encodedArgsFor(
            mapOf(
                "enabled" to true,
                "disabled" to false,
                "count" to 42,
                "ratio" to 1.5,
            )
        )
        val obj = JSONObject(encoded)
        // get*() throws if the underlying type was a String, so these
        // calls double as type-fidelity assertions.
        assertEquals(true, obj.getBoolean("enabled"))
        assertEquals(false, obj.getBoolean("disabled"))
        assertEquals(42, obj.getInt("count"))
        assertEquals(1.5, obj.getDouble("ratio"), 0.0)
        // Belt-and-braces: the on-the-wire form must not quote primitives.
        assertTrue(
            "Boolean must be a JSON literal, not a quoted string; encoded=$encoded",
            !encoded.contains("\"true\"") && !encoded.contains("\"false\""),
        )
        assertTrue(
            "Number must be a JSON literal, not a quoted string; encoded=$encoded",
            !encoded.contains("\"42\"") && !encoded.contains("\"1.5\""),
        )
    }

    // ---- Test fixtures ------------------------------------------------------

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

    /**
     * Drive a full inference that emits one model-issued tool call with
     * [args] and return the JSON-encoded `args` string the orchestrator
     * writes to the pipe. The session's allowlist contains "weather"
     * so the call is forwarded (not dropped by F-036), and the model
     * mock's only output is that single tool call — letting the test
     * cancel the inference as soon as the frame is captured rather
     * than waiting on `ToolCallBridge.awaitResults` (30 s default).
     */
    private fun encodedArgsFor(args: Map<String, Any>): String {
        every { mockConversation.sendMessageAsync(any<Contents>()) } returns flow {
            emit(chunk(calls = listOf("weather" to args)))
        }
        val sid = sessionManager.createSession(
            SessionConfig(
                maxTokens = 2048,
                toolsJson = """[{"name":"weather","description":"d"}]""",
            )
        )
        val requestId = "req-enc-${requestCounter.incrementAndGet()}"
        val scopedKey = "100:$requestId"
        val pfd = mockk<ParcelFileDescriptor>(relaxed = true)
        val out = ByteArrayOutputStream()
        outputStreamQueue.add(out)
        orchestrator.infer(
            scopedKey,
            RequestMeta(requestId = requestId, sessionId = sid, textContent = "hi"),
            image = null, audio = null, pipeWriteEnd = pfd,
        )

        // Spin until the orchestrator emits the tool_call frame, then
        // cancel so awaitResults unblocks immediately.
        val deadline = System.currentTimeMillis() + 5_000L
        var captured: String? = null
        while (System.currentTimeMillis() < deadline && captured == null) {
            val events = TestPipeHelper.parseFrames(
                TestPipeHelper.readFrames(ByteArrayInputStream(out.toByteArray()))
            )
            val tc = events.firstOrNull { it.kind == "tool_call" }
            if (tc?.toolArgs != null) {
                captured = tc.toolArgs
                break
            }
            Thread.sleep(25)
        }
        orchestrator.cancelInference(scopedKey)
        // Drain the inference so the next test starts clean.
        val drainDeadline = System.currentTimeMillis() + 3_000L
        while (System.currentTimeMillis() < drainDeadline &&
            sessionManager.activeRequestIdForSession(sid) != null
        ) {
            Thread.sleep(25)
        }
        sessionManager.destroySession(sid)
        assertNotNull(
            "Orchestrator never emitted a tool_call frame within the timeout — " +
                "encoding pipeline is broken or test mock is wrong",
            captured,
        )
        return captured!!
    }
}
