package com.adsamcik.mindlayer.service

import android.content.pm.PackageManager
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import com.adsamcik.mindlayer.DeferredHandle
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.service.engine.DeferredStore
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.SessionManager
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.security.AllowlistEntry
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CallerIdentity
import com.adsamcik.mindlayer.service.security.RateLimiter
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.CapturingSlot
import io.mockk.Runs
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceBinderDeferredRevocationTest {
    private lateinit var binder: ServiceBinder
    private lateinit var deferredStore: DeferredStore
    private lateinit var orchestrator: InferenceOrchestrator
    private lateinit var allowlistStore: AllowlistStore
    private val authorized = AtomicBoolean(true)
    private val callingUid = AtomicInteger(UID)
    private val blockNextApprovalCapture = AtomicBoolean(false)
    private lateinit var approvalCaptureEntered: CountDownLatch
    private lateinit var releaseApprovalCapture: CountDownLatch
    private lateinit var deathRecipient: CapturingSlot<IBinder.DeathRecipient>

    @Before
    fun setUp() {
        mockkStatic(Binder::class)
        mockkStatic(Process::class)
        every { Process.myUid() } returns SELF_UID
        every { Binder.getCallingUid() } answers { callingUid.get() }

        authorized.set(true)
        callingUid.set(UID)
        blockNextApprovalCapture.set(false)
        approvalCaptureEntered = CountDownLatch(1)
        releaseApprovalCapture = CountDownLatch(1)
        val packageManager = mockk<PackageManager>(relaxed = true) {
            every { getPackageUid("pkg", 0) } returns UID
        }
        val service = mockk<MindlayerMlService>(relaxed = true) {
            every { sessionManager } returns mockk<SessionManager>(relaxed = true)
            every { packageName } returns "com.adsamcik.mindlayer"
            every { getPackageManager() } returns packageManager
        }
        allowlistStore = mockk<AllowlistStore>(relaxed = true) {
            every { isDenied(any(), any()) } returns false
            every { isAllowed("pkg", "sig") } answers { authorized.get() }
            every { list() } answers {
                if (authorized.get()) listOf(AllowlistEntry("pkg", "sig", 1L)) else emptyList()
            }
            every { revoke("pkg", UID) } answers {
                authorized.set(false)
                AllowlistEntry("pkg", "sig", 1L)
            }
            every { approvalFor("pkg", "sig") } answers {
                val wasAuthorized = authorized.get()
                if (blockNextApprovalCapture.compareAndSet(true, false)) {
                    approvalCaptureEntered.countDown()
                    check(releaseApprovalCapture.await(5, TimeUnit.SECONDS))
                }
                if (wasAuthorized) {
                    AllowlistEntry("pkg", "sig", grantedAtMs = 1L)
                } else {
                    null
                }
            }
        }
        val rateLimiter = mockk<RateLimiter>(relaxed = true) {
            every { tryAcquire(any(), any()) } returns true
            every { tryAcquireRejected(any()) } returns true
            every { tryAcquireRejection(any()) } returns true
            every { beginInference(any()) } returns true
        }
        deferredStore = mockk(relaxed = true) {
            coEvery { create(any(), any(), any(), any()) } returns
                DeferredHandle(requestId = "deferred", expiresAtMs = Long.MAX_VALUE)
            coEvery { purgeForUid(any()) } returns 1
        }
        orchestrator = mockk(relaxed = true) {
            every { getSessionOwner(SESSION_ID) } returns UID
            every { infer(any(), any(), any(), any(), any(), any()) } answers {
                authorized.set(false)
                arg<ParcelFileDescriptor>(4).close()
            }
        }

        binder = ServiceBinder(
            service = service,
            engineManager = mockk<EngineManager>(relaxed = true),
            orchestrator = orchestrator,
            diagnosticExporter = mockk<DiagnosticExporter>(relaxed = true),
            thermalMonitor = mockk<ThermalMonitor>(relaxed = true) {
                every { currentPolicy } returns MutableStateFlow(mockk(relaxed = true))
            },
            memoryBudget = mockk<MemoryBudget>(relaxed = true),
            callerVerifier = { _, _ -> CallerIdentity("pkg", "sig", "Pkg") },
            allowlistStore = allowlistStore,
            rateLimiter = rateLimiter,
            deferredStore = deferredStore,
        )
        deathRecipient = CapturingSlot()
        val token = mockk<IBinder>(relaxed = true) {
            every { linkToDeath(capture(deathRecipient), 0) } just Runs
        }
        binder.registerClient(token)
    }

    @After
    fun tearDown() = unmockkAll()

    @Test
    fun `chat deferred handoff is cancelled when consent is revoked during submission`() {
        val error = assertThrows(SecurityException::class.java) {
            binder.inferDeferred(
                RequestMeta(requestId = "caller-id", sessionId = SESSION_ID, textContent = "hello"),
                emptyList(),
            )
        }

        assertEquals(
            MindlayerErrorCode.CONSENT_REQUIRED,
            MindlayerErrorCode.codeFromWireMessage(error.message),
        )
        verify { orchestrator.cancelInference(match { it.startsWith("$UID:") }) }
        coVerify { deferredStore.purgeForUid(UID) }
    }

    @Test
    fun `chat deferred authorized before revoke cannot create after revoke`() {
        blockNextApprovalCapture.set(true)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val submission = executor.submit<Result<DeferredHandle>> {
                runCatching {
                    binder.inferDeferred(
                        RequestMeta(
                            requestId = "caller-id",
                            sessionId = SESSION_ID,
                            textContent = "hello",
                        ),
                        emptyList(),
                    )
                }
            }

            check(approvalCaptureEntered.await(5, TimeUnit.SECONDS))

            callingUid.set(SELF_UID)
            binder.revokeApp("pkg")
            releaseApprovalCapture.countDown()

            val error = submission.get(5, TimeUnit.SECONDS).exceptionOrNull() as SecurityException
            assertEquals(
                MindlayerErrorCode.CONSENT_REQUIRED,
                MindlayerErrorCode.codeFromWireMessage(error.message),
            )
            coVerify(exactly = 0) { deferredStore.create(any(), any(), any(), any()) }
            verify(exactly = 0) {
                orchestrator.infer(any(), any(), any(), any(), any(), any())
            }
        } finally {
            releaseApprovalCapture.countDown()
            executor.shutdownNow()
        }
    }

    @Test
    fun `chat deferred captured before client death cannot create afterward`() {
        blockNextApprovalCapture.set(true)
        val executor = Executors.newSingleThreadExecutor()
        try {
            val submission = executor.submit<Result<DeferredHandle>> {
                runCatching {
                    binder.inferDeferred(
                        RequestMeta(
                            requestId = "caller-id",
                            sessionId = SESSION_ID,
                            textContent = "hello",
                        ),
                        emptyList(),
                    )
                }
            }
            check(approvalCaptureEntered.await(5, TimeUnit.SECONDS))

            deathRecipient.captured.binderDied()
            releaseApprovalCapture.countDown()

            val error = submission.get(5, TimeUnit.SECONDS).exceptionOrNull()
            assertTrue(error is SecurityException)
            coVerify(exactly = 0) { deferredStore.create(any(), any(), any(), any()) }
            verify(exactly = 0) {
                orchestrator.infer(any(), any(), any(), any(), any(), any())
            }
        } finally {
            releaseApprovalCapture.countDown()
            executor.shutdownNow()
        }
    }

    private companion object {
        const val SELF_UID = 1_000
        const val UID = 24_680
        const val SESSION_ID = "session"
    }
}
