package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.util.Log
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.sdk.db.MindlayerDatabase
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-071: SDK-side coverage for the LOW_MEMORY wire code.
 *
 * Acceptance criterion 2 from the task spec:
 *
 * > drive the AIDL surface (mocked) returning the wire-prefixed
 * > SecurityException for low_memory. Assert: SDK emits a typed error
 * > with code "low_memory" and the available / required MB numbers.
 *
 * Because [Mindlayer.createSession] is a synchronous AIDL call (not a
 * pipe stream), the typed error surfaces as
 * [MindlayerException] via [MindlayerException.fromAidlSecurityException]
 * — `code = LOW_MEMORY`, `codeName = "LOW_MEMORY"`,
 * `category = RESOURCE`. The avail/required numbers travel in the
 * message payload (parseable substring). Critically, the existing
 * createSession retry schedule is gated on `ENGINE_INITIALIZING` only,
 * so `LOW_MEMORY` falls through immediately without any retries.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MindlayerLowMemoryTest {

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
    }

    @After
    fun tearDown() {
        db.close()
        resetDbSingleton()
        unmockkAll()
    }

    @Test
    fun `LOW_MEMORY wire code surfaces as MindlayerException with avail and required numbers`() = runTest {
        var attempts = 0
        every { mockService.createSession(any()) } answers {
            attempts++
            throw codedException(
                MindlayerErrorCode.LOW_MEMORY,
                "Insufficient memory: availMb=1024 requiredMb=2560",
            )
        }

        var thrown: Throwable? = null
        try {
            mindlayer.createSession { backend("CPU") }
        } catch (e: Throwable) {
            thrown = e
        }

        assertTrue("Expected MindlayerException, got $thrown", thrown is MindlayerException)
        val mle = thrown as MindlayerException
        assertEquals(MindlayerErrorCode.LOW_MEMORY, mle.code)
        assertEquals("LOW_MEMORY", mle.codeName)
        assertEquals(MindlayerErrorCode.Category.RESOURCE, mle.category)
        assertTrue(
            "message should preserve availMb=… numeric token; was '${mle.message}'",
            mle.message!!.contains("availMb=1024"),
        )
        assertTrue(
            "message should preserve requiredMb=… numeric token; was '${mle.message}'",
            mle.message!!.contains("requiredMb=2560"),
        )

        // Critical: LOW_MEMORY must NOT be retried — only ENGINE_INITIALIZING
        // is retryable per createSessionWithInitRetry's contract.
        assertEquals("LOW_MEMORY must not trigger retry storm", 1, attempts)
        verify(exactly = 1) { mockService.createSession(any()) }
    }

    @Test
    fun `LOW_MEMORY mapping is robust to alternative number values`() = runTest {
        every { mockService.createSession(any()) } answers {
            throw codedException(
                MindlayerErrorCode.LOW_MEMORY,
                "Insufficient memory: availMb=512 requiredMb=4096",
            )
        }

        var thrown: Throwable? = null
        try {
            mindlayer.createSession { }
        } catch (e: Throwable) {
            thrown = e
        }

        if (thrown !is MindlayerException) {
            fail("Expected MindlayerException, got $thrown")
            return@runTest
        }
        val mle = thrown
        assertEquals(MindlayerErrorCode.LOW_MEMORY, mle.code)
        assertTrue(mle.message!!.contains("availMb=512"))
        assertTrue(mle.message!!.contains("requiredMb=4096"))
    }

    // ---- Helpers (mirrored from MindlayerCreateSessionRetryTest) ------------

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

    private fun buildMindlayer(conn: ConnectionManager, historyStore: HistoryStore?): MindlayerImpl {
        val ctor = MindlayerImpl::class.java.getDeclaredConstructor(
            ConnectionManager::class.java,
            HistoryStore::class.java,
        )
        ctor.isAccessible = true
        return ctor.newInstance(conn, historyStore)
    }

    private fun codedException(code: Int, message: String): RuntimeException =
        SecurityException(MindlayerErrorCode.wireMessage(code, message))
}
