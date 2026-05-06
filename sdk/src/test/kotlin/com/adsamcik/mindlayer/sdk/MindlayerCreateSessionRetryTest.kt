package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.sdk.db.MindlayerDatabase
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.coEvery
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-018: SDK-side one-shot retry-with-backoff for the engine-initializing
 * path.
 *
 * As of v02-error-codes the service throws Binder-safe runtime exceptions with
 * a prefixed [MindlayerErrorCode] while LiteRT-LM is doing its (~5–10 s)
 * cold-start init. The SDK must not surface this to user code on first launch
 * — instead retry with exponential backoff up to 10 s total. Other typed errors
 * propagate immediately wrapped as [MindlayerException].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerCreateSessionRetryTest {

    private lateinit var db: MindlayerDatabase
    private lateinit var mockService: IMindlayerService
    private lateinit var mockConnection: ConnectionManager
    private lateinit var mindlayer: Mindlayer
    private var fakeNowMs: Long = 0L

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
        resetDbSingleton()
        db = Room.inMemoryDatabaseBuilder(context, MindlayerDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        setDbSingleton(db)

        mockService = mockk(relaxed = true)

        mockConnection = mockk(relaxed = true) {
            every { getService() } returns mockService
            every { requireService() } returns mockService
            every { state } returns MutableStateFlow(ConnectionState.CONNECTED)
            coEvery { awaitConnected(any()) } returns mockService
            coEvery { awaitConnected() } returns mockService
        }

        mindlayer = buildMindlayer(mockConnection, null)
        fakeNowMs = 0L
        mindlayer.createSessionInitRetryClockMs = { fakeNowMs }
        mindlayer.createSessionInitRetryBackoffMs = listOf(1L, 2L, 3L)
        mindlayer.createSessionInitRetryTimeoutMs = 100L
    }

    @After
    fun tearDown() {
        db.close()
        resetDbSingleton()
        unmockkAll()
    }

    // ---- Tests --------------------------------------------------------------

    @Test
    fun `retries on engine_initializing then succeeds`() = runTest {
        var attempts = 0
        every { mockService.createSession(any()) } answers {
            attempts++
            if (attempts <= 1) {
                throw codedException(
                    MindlayerErrorCode.ENGINE_INITIALIZING,
                    "engine_initializing",
                )
            } else {
                "session-ready"
            }
        }

        val id = mindlayer.createSession { backend("CPU") }

        assertEquals("session-ready", id)
        assertEquals("Should retry once after engine_initializing", 2, attempts)
        verify(exactly = 2) { mockService.createSession(any()) }
    }

    @Test
    fun `retries after initial backoff schedule then succeeds within window`() = runTest {
        var attempts = 0
        every { mockService.createSession(any()) } answers {
            attempts++
            fakeNowMs += 1L
            if (attempts <= 5) {
                throw codedException(
                    MindlayerErrorCode.ENGINE_INITIALIZING,
                    "engine_initializing",
                )
            } else {
                "session-after-3"
            }
        }

        val id = mindlayer.createSession { backend("CPU") }
        assertEquals("session-after-3", id)
        assertEquals(6, attempts)
    }

    @Test
    fun `non-engine_initializing typed error propagates as MindlayerException`() = runTest {
        var attempts = 0
        every { mockService.createSession(any()) } answers {
            attempts++
            throw codedException(
                MindlayerErrorCode.INVALID_SESSION_CONFIG,
                "Invalid SessionConfig: bad",
            )
        }

        var thrown: Throwable? = null
        try {
            mindlayer.createSession { }
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue("Expected MindlayerException, got $thrown", thrown is MindlayerException)
        val mle = thrown as MindlayerException
        assertEquals(MindlayerErrorCode.INVALID_SESSION_CONFIG, mle.code)
        assertEquals("INVALID_SESSION_CONFIG", mle.codeName)
        assertEquals(MindlayerErrorCode.Category.VALIDATION, mle.category)
        assertEquals("Should not retry on non-engine_initializing error", 1, attempts)
    }

    @Test
    fun `auth-gate SecurityException still propagates as SecurityException`() = runTest {
        // Rate-limit / allowlist failures stay as SecurityException after
        // v02-error-codes — they're auth-gate signals, not typed business errors.
        var attempts = 0
        every { mockService.createSession(any()) } answers {
            attempts++
            throw SecurityException("Rate limit exceeded")
        }

        var thrown: Throwable? = null
        try {
            mindlayer.createSession { }
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue(
            "Expected SecurityException for auth gate, got $thrown",
            thrown is SecurityException,
        )
        assertEquals("Should not retry on SecurityException", 1, attempts)
    }

    @Test
    fun `gives up after retry window expires`() = runTest {
        mindlayer.createSessionInitRetryTimeoutMs = 10L
        every { mockService.createSession(any()) } answers {
            fakeNowMs += 5L
            throw codedException(
                MindlayerErrorCode.ENGINE_INITIALIZING,
                "engine_initializing",
            )
        }
        var thrown: Throwable? = null
        try {
            mindlayer.createSession { backend("CPU") }
        } catch (e: Throwable) {
            thrown = e
        }
        assertTrue(
            "Expected MindlayerException after retries, got $thrown",
            thrown is MindlayerException,
        )
        val mle = thrown as MindlayerException
        assertEquals(MindlayerErrorCode.ENGINE_INITIALIZING, mle.code)
        verify(atLeast = 1) { mockService.createSession(any()) }
    }

    // ---- Helpers ------------------------------------------------------------

    private fun resetDbSingleton() {
        val field = MindlayerDatabase::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, null)
    }

    private fun setDbSingleton(database: MindlayerDatabase) {
        val field = MindlayerDatabase::class.java.getDeclaredField("instance")
        field.isAccessible = true
        field.set(null, database)
    }

    private fun buildMindlayer(conn: ConnectionManager, historyStore: HistoryStore?): Mindlayer {
        val ctor = Mindlayer::class.java.getDeclaredConstructor(
            ConnectionManager::class.java,
            HistoryStore::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(conn, historyStore)
    }

    private fun codedException(code: Int, message: String): RuntimeException =
        SecurityException(MindlayerErrorCode.wireMessage(code, message))
}
