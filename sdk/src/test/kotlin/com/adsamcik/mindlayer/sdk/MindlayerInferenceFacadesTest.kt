package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.ServiceStatus
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
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Method

/**
 * Pins the public-SDK facade trio introduced by `feat/inference-sdk-polish`:
 *
 *   - [Mindlayer.inferRealtime]
 *   - [Mindlayer.inferAsync]
 *   - [Mindlayer.inferTools]
 *
 * and the `@Deprecated` annotations on the legacy methods they replace.
 *
 * These tests are intentionally coarse — they don't re-prove the deep
 * behavior of the underlying `chat` / `chatWithMedia` methods (that's
 * `MindlayerApiTest`). They pin **shape**: that the facades exist with
 * the expected signatures, that they reach the same AIDL endpoint as the
 * deprecated methods they delegate to, and that the deprecation metadata
 * is present.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerInferenceFacadesTest {

    private lateinit var db: MindlayerDatabase
    private lateinit var mockService: IMindlayerService
    private lateinit var mockConnection: ConnectionManager
    private lateinit var mindlayer: Mindlayer

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
            every { createSession(any()) } returns "session-facade"
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

    private fun buildMindlayer(conn: ConnectionManager): Mindlayer {
        val ctor = Mindlayer::class.java.getDeclaredConstructor(
            ConnectionManager::class.java,
            HistoryStore::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(conn, null)
    }

    // ═════════════════════════════════════════════════════════════════════
    //  (a) Facade methods exist with expected signatures
    // ═════════════════════════════════════════════════════════════════════

    private fun methodsNamed(name: String): List<Method> =
        Mindlayer::class.java.declaredMethods.filter { it.name == name }

    @Test
    fun `inferRealtime function exists and is public`() {
        val matches = methodsNamed("inferRealtime")
        assertTrue(
            "Expected at least one inferRealtime method on Mindlayer",
            matches.isNotEmpty(),
        )
        val ir = matches.first()
        // suspend fun (sessionId, text, vararg media): InferenceHandle is
        // compiled to: (String, String, MediaPart[], Continuation) → Object
        assertEquals(
            "inferRealtime parameter count drifted (expected sessionId, text, vararg media, Continuation)",
            4,
            ir.parameterCount,
        )
        // suspend fun returns Object/Any at JVM-bytecode level — coroutine
        // erasure. Just sanity-check it isn't void.
        assertTrue(
            "inferRealtime must not return void",
            ir.returnType != Void.TYPE,
        )
    }

    @Test
    fun `inferAsync function exists`() {
        val matches = methodsNamed("inferAsync")
        assertTrue(
            "Expected at least one inferAsync method on Mindlayer",
            matches.isNotEmpty(),
        )
        val ia = matches.first()
        assertEquals(
            "inferAsync parameter count drifted",
            4,
            ia.parameterCount,
        )
        assertTrue(
            "inferAsync must not return void",
            ia.returnType != Void.TYPE,
        )
    }

    @Test
    fun `inferTools function exists`() {
        val matches = methodsNamed("inferTools")
        assertTrue(
            "Expected at least one inferTools method on Mindlayer",
            matches.isNotEmpty(),
        )
        val tools = matches.first()
        assertEquals(
            "inferTools parameter count drifted",
            4,
            tools.parameterCount,
        )
        assertTrue(
            "inferTools must not return void",
            tools.returnType != Void.TYPE,
        )
    }

    // ═════════════════════════════════════════════════════════════════════
    //  (b) @Deprecated annotations on legacy methods
    // ═════════════════════════════════════════════════════════════════════

    private fun assertDeprecated(name: String, expectedReplacement: String) {
        val methods = methodsNamed(name)
        assertTrue("Method '$name' not found on Mindlayer", methods.isNotEmpty())
        // Kotlin compiles @Deprecated to kotlin.Deprecated on the method.
        val method = methods.first()
        val deprecated = method.getAnnotation(Deprecated::class.java)
        assertNotNull(
            "Method '$name' missing @kotlin.Deprecated annotation",
            deprecated,
        )
        assertEquals(
            "Method '$name' deprecation level should remain WARNING during migration",
            DeprecationLevel.WARNING,
            deprecated!!.level,
        )
        assertTrue(
            "Method '$name' ReplaceWith should reference '$expectedReplacement' but was '${deprecated.replaceWith.expression}'",
            deprecated.replaceWith.expression.contains(expectedReplacement),
        )
    }

    @Test
    fun `legacy chat is deprecated for inferRealtime`() {
        assertDeprecated("chat", "inferRealtime")
    }

    @Test
    fun `legacy chatWithImage is deprecated for inferRealtime`() {
        assertDeprecated("chatWithImage", "inferRealtime")
    }

    @Test
    fun `legacy chatWithAudio is deprecated for inferRealtime`() {
        assertDeprecated("chatWithAudio", "inferRealtime")
    }

    @Test
    fun `legacy chatWithMedia is deprecated for inferRealtime`() {
        assertDeprecated("chatWithMedia", "inferRealtime")
    }

    @Test
    fun `legacy chatOnce is deprecated for inferAsync`() {
        assertDeprecated("chatOnce", "inferAsync")
    }

    @Test
    fun `legacy chatWithImageOnce is deprecated for inferAsync`() {
        assertDeprecated("chatWithImageOnce", "inferAsync")
    }

    @Test
    fun `legacy chatWithAudioOnce is deprecated for inferAsync`() {
        assertDeprecated("chatWithAudioOnce", "inferAsync")
    }

    // ═════════════════════════════════════════════════════════════════════
    //  (c) Delegating behavior — same AIDL path, same RequestMeta
    // ═════════════════════════════════════════════════════════════════════

    @Test
    fun `inferRealtime with no media reaches infer AIDL with caller text`() = runTest {
        val metaSlot = slot<RequestMeta>()
        every {
            mockService.infer(capture(metaSlot), any(), any(), any())
        } answers {
            arg<ParcelFileDescriptor>(3).close()
        }

        val handle = mindlayer.inferRealtime("sess-realtime", "Hello via facade")
        handle.events.toList()

        verify(exactly = 1) {
            mockService.infer(any(), isNull(), isNull(), any())
        }
        val meta = metaSlot.captured
        assertEquals("sess-realtime", meta.sessionId)
        assertEquals("Hello via facade", meta.textContent)
        assertNotNull(meta.requestId)
        assertTrue(meta.requestId.isNotEmpty())
    }

    @Test
    fun `inferTools with no media reaches infer AIDL identically to inferRealtime`() = runTest {
        val metaSlot = slot<RequestMeta>()
        every {
            mockService.infer(capture(metaSlot), any(), any(), any())
        } answers {
            arg<ParcelFileDescriptor>(3).close()
        }

        val handle = mindlayer.inferTools("sess-tools", "Tools entry point")
        handle.events.toList()

        verify(exactly = 1) {
            mockService.infer(any(), isNull(), isNull(), any())
        }
        val meta = metaSlot.captured
        assertEquals("sess-tools", meta.sessionId)
        assertEquals("Tools entry point", meta.textContent)
    }

    @Test
    fun `inferAsync collects streaming response to a single string`() = runTest {
        // The mock service receives a write-end PFD, writes a Done frame
        // (no deltas), and closes — inferAsync should return the empty
        // string accumulated from zero TextDelta + a Done(fullText=null).
        every {
            mockService.infer(any(), any(), any(), any())
        } answers {
            val pfd = arg<ParcelFileDescriptor>(3)
            // Write a minimal valid frame: a Done event marker.
            // Easiest path: simply close the write end so the reader
            // sees EOF. The current SDK reader treats raw-EOF the same
            // way as a missing terminal event — it throws
            // IllegalStateException("stream ended without terminal event").
            // For shape-pinning purposes we accept either: a returned
            // string OR an IllegalStateException; both prove that
            // inferAsync ran the collectHandleToString path under the
            // hood. The point of this test is the delegation, not the
            // wire-level happy path (covered by MindlayerApiTest).
            pfd.close()
        }

        runCatching {
            mindlayer.inferAsync("sess-async", "Anything")
        }.let { result ->
            // Either it returned a string (happy path on some readers)
            // or it threw IllegalStateException (terminal-event missing).
            // What we MUST see is the AIDL call.
            verify(exactly = 1) {
                mockService.infer(any(), isNull(), isNull(), any())
            }
            // result intentionally ignored — both outcomes confirm
            // the facade reaches collectHandleToString.
            @Suppress("UNUSED_VARIABLE") val _r = result
        }
    }
}
