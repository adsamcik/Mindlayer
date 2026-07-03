package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.ServiceStatus
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.sdk.db.MindlayerDatabase
import io.mockk.coEvery
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
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Verifies that canonical `infer { ephemeralSession{}; text("...") }` routes
 * through the real streaming infrastructure (createSession → infer AIDL →
 * destroySession on flow completion) rather than the old eager one-shot path.
 *
 * Actual multi-token streaming is verified by the app-side
 * `TokenStreamProtocolTest` which uses JVM pipes; here we verify the SDK
 * lifecycle plumbing at the integration level under Robolectric.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerInferStreamingTest {

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
            every { createSession(any()) } returns "ephemeral-session-1"
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

    // ═══════════════════════════════════════════════════════════════════
    //  Test: ephemeral infer{} creates session, calls infer AIDL,
    //  and destroys the session after the stream completes.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `infer ephemeral creates session and destroys after stream`() = runTest {
        val pfdSlot = slot<ParcelFileDescriptor>()
        val metaSlot = slot<RequestMeta>()

        every {
            mockService.infer(capture(metaSlot), any(), any(), capture(pfdSlot))
        } answers {
            // Close write-end to simulate immediate EOF (empty stream)
            pfdSlot.captured.close()
        }

        val handle = mindlayer.infer {
            ephemeralSession {}
            text("Say hello")
        }

        // Collect the events — will be empty/EOF due to mock
        handle.events.toList()

        // Verify session was created via createSession AIDL
        verify(exactly = 1) { mockService.createSession(any()) }

        // Verify infer AIDL was called with correct sessionId
        assertTrue(metaSlot.isCaptured)
        assertEquals("ephemeral-session-1", metaSlot.captured.sessionId)
        assertEquals("Say hello", metaSlot.captured.textContent)

        // Verify ephemeral session was destroyed after stream completion
        verify(exactly = 1) { mockService.destroySession("ephemeral-session-1") }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Test: named session infer{} does NOT create/destroy sessions
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `infer named session does not create or destroy session`() = runTest {
        val pfdSlot = slot<ParcelFileDescriptor>()
        val metaSlot = slot<RequestMeta>()

        every {
            mockService.infer(capture(metaSlot), any(), any(), capture(pfdSlot))
        } answers {
            pfdSlot.captured.close()
        }

        val handle = mindlayer.infer {
            session("existing-session")
            text("Continue chat")
        }

        handle.events.toList()

        // Verify infer was called on the named session
        assertEquals("existing-session", metaSlot.captured.sessionId)

        // Named session: createSession and destroySession should NOT be called
        verify(exactly = 0) { mockService.createSession(any()) }
        verify(exactly = 0) { mockService.destroySession(any()) }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Test: infer{} with outputTools does NOT throw NOT_SUPPORTED
    //  (regression: the old code threw; the new code routes through
    //  streaming with tools configured on the ephemeral session)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `infer ephemeral with outputTools does not throw`() = runTest {
        val pfdSlot = slot<ParcelFileDescriptor>()

        every {
            mockService.infer(any(), any(), any(), capture(pfdSlot))
        } answers {
            pfdSlot.captured.close()
        }

        // This previously threw MindlayerException(NOT_SUPPORTED)
        val handle = mindlayer.infer {
            ephemeralSession {}
            text("What is the weather?")
            outputTools(
                listOf(
                    ToolSpec(
                        name = "get_weather",
                        description = "Get weather for a city",
                        parametersSchema = JsonSchema.parse(
                            """{"type":"object","properties":{"city":{"type":"string"}}}"""
                        ),
                    ),
                ),
            )
        }

        handle.events.toList()

        // Verify createSession was called (tools flow requires session creation)
        verify(exactly = 1) { mockService.createSession(any()) }
        // Verify destroy after completion
        verify(exactly = 1) { mockService.destroySession("ephemeral-session-1") }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Test: the handle returned by infer{} uses the streaming path
    //  (verified by checking that `infer` AIDL is called, not the
    //  old eager `generate` pattern that called chatOnce)
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `infer ephemeral uses streaming AIDL infer not eager chatOnce`() = runTest {
        val pfdSlot = slot<ParcelFileDescriptor>()

        every {
            mockService.infer(any(), any(), any(), capture(pfdSlot))
        } answers {
            pfdSlot.captured.close()
        }

        val handle = mindlayer.infer {
            ephemeralSession {
                systemPrompt = "Be brief"
            }
            text("Hello")
        }

        handle.events.toList()

        // The streaming path calls infer() AIDL exactly once
        // (the old eager path called createSession→infer→destroySession
        // but with TWO infer calls if chatOnce was the intermediate)
        verify(exactly = 1) { mockService.infer(any(), any(), any(), any()) }
        // Session was created with a config
        verify(exactly = 1) { mockService.createSession(any()) }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Test: ephemeral session is destroyed even on error
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `infer ephemeral destroys session on error`() = runTest {
        every {
            mockService.infer(any(), any(), any(), any())
        } throws SecurityException("RATE_LIMITED|rate limited")

        val handle = try {
            mindlayer.infer {
                ephemeralSession {}
                text("Trigger error")
            }
        } catch (_: Exception) {
            null
        }

        if (handle != null) {
            // If we got a handle, collect to trigger the cleanup
            try { handle.events.toList() } catch (_: Exception) {}
        }

        // Session was created
        verify(exactly = 1) { mockService.createSession(any()) }
        // Session must still be destroyed for cleanup
        verify(exactly = 1) { mockService.destroySession("ephemeral-session-1") }
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Test: outputJson(schema) actually reaches the service as the
    //  `structured_output` envelope (regression: the schema used to be
    //  silently dropped, so enum/type validation never ran server-side).
    // ═══════════════════════════════════════════════════════════════════

    private val enumSchema = JsonSchema.parse(
        """
        {"type":"object","properties":{"status":{"type":"string","enum":["active","inactive"]}},"required":["status"]}
        """.trimIndent(),
    )

    @Test
    fun `infer ephemeral with outputJson emits structured_output envelope`() = runTest {
        val configSlot = slot<SessionConfig>()
        every { mockService.createSession(capture(configSlot)) } returns "ephemeral-session-1"

        val pfdSlot = slot<ParcelFileDescriptor>()
        every {
            mockService.infer(any(), any(), any(), capture(pfdSlot))
        } answers {
            pfdSlot.captured.close()
        }

        val handle = mindlayer.infer {
            ephemeralSession {}
            text("Classify this")
            outputJson(enumSchema)
        }
        handle.events.toList()

        assertTrue("createSession must receive a config", configSlot.isCaptured)
        val extra = configSlot.captured.extraContextJson
        assertNotNull("outputJson must populate extraContextJson", extra)

        val env = Json.parseToJsonElement(extra!!).jsonObject
        val so = env["structured_output"]!!.jsonObject
        // Strategy + retries come from JsonOutputBuilder defaults.
        assertEquals("prompt_and_validate", so["strategy"]!!.jsonPrimitive.content)
        assertEquals(3, so["max_retries"]!!.jsonPrimitive.int)
        // The enum schema must survive verbatim so server-side validation runs.
        val statusEnum = so["schema"]!!.jsonObject["properties"]!!
            .jsonObject["status"]!!.jsonObject["enum"]!!.jsonArray
        assertEquals("active", statusEnum[0].jsonPrimitive.content)
        assertEquals("inactive", statusEnum[1].jsonPrimitive.content)
    }

    @Test
    fun `extractJson emits structured_output envelope with the schema`() = runTest {
        val configSlot = slot<SessionConfig>()
        every { mockService.createSession(capture(configSlot)) } returns "ephemeral-session-1"

        val pfdSlot = slot<ParcelFileDescriptor>()
        every {
            mockService.infer(any(), any(), any(), capture(pfdSlot))
        } answers {
            pfdSlot.captured.close()
        }

        // extractJson awaits JSON at the end; the empty EOF stream makes
        // awaitJson throw, but the session is created (with the schema) first,
        // which is exactly what we assert on.
        runCatching {
            mindlayer.extractJson(prompt = "Classify this", schema = enumSchema)
        }

        assertTrue("createSession must receive a config", configSlot.isCaptured)
        val env = Json.parseToJsonElement(configSlot.captured.extraContextJson!!).jsonObject
        val so = env["structured_output"]!!.jsonObject
        assertNotNull("extractJson must carry the schema", so["schema"])
        assertEquals("prompt_and_validate", so["strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `infer outputJson merges with caller extraContextJson without clobbering`() = runTest {
        val configSlot = slot<SessionConfig>()
        every { mockService.createSession(capture(configSlot)) } returns "ephemeral-session-1"

        val pfdSlot = slot<ParcelFileDescriptor>()
        every {
            mockService.infer(any(), any(), any(), capture(pfdSlot))
        } answers {
            pfdSlot.captured.close()
        }

        val handle = mindlayer.infer {
            ephemeralSession {
                extraContextJson = """{"grounding":"foo"}"""
            }
            text("Classify this")
            outputJson(enumSchema)
        }
        handle.events.toList()

        val env = Json.parseToJsonElement(configSlot.captured.extraContextJson!!).jsonObject
        assertEquals(
            "caller extraContextJson keys must survive the structured_output merge",
            "foo",
            env["grounding"]!!.jsonPrimitive.content,
        )
        assertNotNull("structured_output must be merged in", env["structured_output"])
    }

    @Test
    fun `infer outputJson tool_routing strategy propagates`() = runTest {
        val configSlot = slot<SessionConfig>()
        every { mockService.createSession(capture(configSlot)) } returns "ephemeral-session-1"

        val pfdSlot = slot<ParcelFileDescriptor>()
        every {
            mockService.infer(any(), any(), any(), capture(pfdSlot))
        } answers {
            pfdSlot.captured.close()
        }

        val handle = mindlayer.infer {
            ephemeralSession {}
            text("Classify this")
            outputJson(enumSchema, JsonOutputStrategy.ToolRouting)
        }
        handle.events.toList()

        val env = Json.parseToJsonElement(configSlot.captured.extraContextJson!!).jsonObject
        assertEquals(
            "tool_routing",
            env["structured_output"]!!.jsonObject["strategy"]!!.jsonPrimitive.content,
        )
    }

    // ═══════════════════════════════════════════════════════════════════
    //  Test: the public SessionScope.jsonOutput { } DSL reaches the
    //  service as the structured_output envelope, from both entry points.
    // ═══════════════════════════════════════════════════════════════════

    @Test
    fun `openSession with jsonOutput DSL emits structured_output envelope`() = runTest {
        val configSlot = slot<SessionConfig>()
        every { mockService.createSession(capture(configSlot)) } returns "ephemeral-session-1"

        mindlayer.openSession {
            systemPrompt = "You classify tickets."
            jsonOutput {
                schema(enumSchema.json)
                strategy(JsonOutputStrategy.PromptAndValidate)
            }
        }

        assertTrue("openSession must create a session", configSlot.isCaptured)
        val so = Json.parseToJsonElement(configSlot.captured.extraContextJson!!)
            .jsonObject["structured_output"]!!.jsonObject
        assertEquals("prompt_and_validate", so["strategy"]!!.jsonPrimitive.content)
        assertEquals(3, so["max_retries"]!!.jsonPrimitive.int)
        val statusEnum = so["schema"]!!.jsonObject["properties"]!!
            .jsonObject["status"]!!.jsonObject["enum"]!!.jsonArray
        assertEquals("active", statusEnum[0].jsonPrimitive.content)
        assertEquals("inactive", statusEnum[1].jsonPrimitive.content)
    }

    @Test
    fun `infer ephemeralSession jsonOutput DSL emits structured_output envelope`() = runTest {
        val configSlot = slot<SessionConfig>()
        every { mockService.createSession(capture(configSlot)) } returns "ephemeral-session-1"

        val pfdSlot = slot<ParcelFileDescriptor>()
        every {
            mockService.infer(any(), any(), any(), capture(pfdSlot))
        } answers {
            pfdSlot.captured.close()
        }

        val handle = mindlayer.infer {
            ephemeralSession {
                jsonOutput { schema(enumSchema.json) }
            }
            text("Classify this")
        }
        handle.events.toList()

        val so = Json.parseToJsonElement(configSlot.captured.extraContextJson!!)
            .jsonObject["structured_output"]!!.jsonObject
        assertNotNull("ephemeralSession jsonOutput must carry the schema", so["schema"])
        assertEquals("prompt_and_validate", so["strategy"]!!.jsonPrimitive.content)
    }

    @Test
    fun `openSession jsonOutput DSL merges with manual extraContextJson`() = runTest {
        val configSlot = slot<SessionConfig>()
        every { mockService.createSession(capture(configSlot)) } returns "ephemeral-session-1"

        mindlayer.openSession {
            extraContextJson = """{"thinking":{"enable":true}}"""
            jsonOutput { schema(enumSchema.json) }
        }

        val env = Json.parseToJsonElement(configSlot.captured.extraContextJson!!).jsonObject
        assertEquals(
            "manual extraContextJson keys must survive the jsonOutput merge",
            true,
            env["thinking"]!!.jsonObject["enable"]!!.jsonPrimitive.boolean,
        )
        assertNotNull("structured_output must be merged in", env["structured_output"])
    }
}
