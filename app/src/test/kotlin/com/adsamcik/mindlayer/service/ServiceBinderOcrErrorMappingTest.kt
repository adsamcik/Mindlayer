package com.adsamcik.mindlayer.service

import android.os.Binder
import android.os.ParcelFileDescriptor
import com.adsamcik.mindlayer.MediaPart
import com.adsamcik.mindlayer.OcrImageOptions
import com.adsamcik.mindlayer.service.engine.EmbeddingCoordinator
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MediaPartYPlaneExtractor
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.OcrSessionManager
import com.adsamcik.mindlayer.service.engine.PaddleOcrEngine
import com.adsamcik.mindlayer.service.engine.SessionManager
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPoolExhaustedException
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CallerIdentity
import com.adsamcik.mindlayer.service.security.IpcInputValidator
import com.adsamcik.mindlayer.service.security.RateLimiter
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.FileNotFoundException
import java.io.IOException

/**
 * Regression coverage for the `ocrImage` wire-error disambiguation
 * landed alongside the SharedMemoryPool cache-trim fix.
 *
 * Before the disambiguation work, every non-SecurityException /
 * non-SharedMemoryPoolExhaustedException thrown from
 * [MediaPartYPlaneExtractor.extractY] was wrapped as
 * `MLERR:3001:ocrImage decode failed` (INVALID_REQUEST), even when the
 * root cause was an `ENOENT` stage-write failure or a native OOM. The
 * SDK retry policy treats INVALID_REQUEST as "caller is wrong, don't
 * retry" and TRANSIENT_RESOURCE_EXHAUSTED / LOW_MEMORY as "back off
 * and try again" — getting this mapping wrong silently breaks recovery.
 *
 * This test pins the four exception classes that can reach the
 * catch ladder in `ServiceBinder.ocrImage` and asserts each one maps
 * to the correct typed wire code:
 *
 *   | Thrown by extractor                            | Wire code                          |
 *   |------------------------------------------------|------------------------------------|
 *   | [SecurityException] (wireError → decode null)  | INVALID_REQUEST                    |
 *   | [SharedMemoryPoolExhaustedException]           | TRANSIENT_RESOURCE_EXHAUSTED       |
 *   | [OutOfMemoryError]                             | LOW_MEMORY                         |
 *   | [FileNotFoundException] / [IOException]        | TRANSIENT_RESOURCE_EXHAUSTED       |
 *   | any other [Throwable]                          | INTERNAL                           |
 *
 * Pairs with [com.adsamcik.mindlayer.service.engine.OcrBinderTransportRejectionTest]
 * (which covers the extractor's own validation throw sites) and
 * [com.adsamcik.mindlayer.service.ipc.SharedMemoryPoolTest]
 * (which covers the cache-trim survival fix that motivated this work).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ServiceBinderOcrErrorMappingTest {

    private lateinit var ocrManager: OcrSessionManager
    private lateinit var pool: SharedMemoryPool
    private lateinit var engine: PaddleOcrEngine

    @Before fun setUp() {
        mockkStatic(Binder::class)
        every { Binder.getCallingUid() } returns 42
        mockkObject(MediaPartYPlaneExtractor)

        ocrManager = mockk(relaxed = true)
        every { ocrManager.isProductionReady } returns true
        every { ocrManager.isEngineReady() } returns true

        pool = mockk(relaxed = true)
        engine = mockk(relaxed = true)
    }

    @After fun tearDown() = unmockkAll()

    @Test fun `SecurityException from extractor maps to INVALID_REQUEST`() {
        // The extractor's own wireError(...) helper throws SecurityException
        // pre-wrapped with INVALID_REQUEST. The binder must pass that
        // through untouched — re-wrapping would either drop the typed code
        // or duplicate the wire prefix.
        val typed = MindlayerErrorCode.wireMessage(
            MindlayerErrorCode.INVALID_REQUEST,
            "Failed to decode staged image",
        )
        every {
            MediaPartYPlaneExtractor.extractY(any(), any(), any())
        } throws SecurityException(typed)

        val ex = invokeOcrImageAndCatch()
        val code = MindlayerErrorCode.codeFromWireMessage(ex.message)
        assertEquals(
            "SecurityException from extractor must round-trip as INVALID_REQUEST",
            MindlayerErrorCode.INVALID_REQUEST,
            code,
        )
    }

    @Test fun `SharedMemoryPoolExhaustedException maps to TRANSIENT_RESOURCE_EXHAUSTED`() {
        every {
            MediaPartYPlaneExtractor.extractY(any(), any(), any())
        } throws SharedMemoryPoolExhaustedException(
            reason = "pfd_count",
            currentCount = 8,
            currentBytes = 1024L,
            retryAfterMs = 25L,
        )

        val ex = invokeOcrImageAndCatch()
        val code = MindlayerErrorCode.codeFromWireMessage(ex.message)
        assertEquals(
            "SharedMemoryPoolExhaustedException must map to TRANSIENT_RESOURCE_EXHAUSTED",
            MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED,
            code,
        )
        assertTrue(
            "wire payload must include shm_pool_exhausted reason marker for SDK debugging",
            ex.message?.contains("shm_pool_exhausted") == true,
        )
    }

    @Test fun `OutOfMemoryError maps to LOW_MEMORY`() {
        // RGBA→Y conversion of a large bitmap can OOM even after the
        // validator passes. LOW_MEMORY is the right SDK hint — INVALID_REQUEST
        // would mislead consumers into blaming their caller code.
        every {
            MediaPartYPlaneExtractor.extractY(any(), any(), any())
        } throws OutOfMemoryError("simulated bitmap allocation")

        val ex = invokeOcrImageAndCatch()
        val code = MindlayerErrorCode.codeFromWireMessage(ex.message)
        assertEquals(
            "OutOfMemoryError must map to LOW_MEMORY so the SDK backs off",
            MindlayerErrorCode.LOW_MEMORY,
            code,
        )
    }

    @Test fun `FileNotFoundException from cache trim maps to TRANSIENT_RESOURCE_EXHAUSTED`() {
        // This is the original symptom that motivated this disambiguation:
        // SharedMemoryPool.createStagingFile() threw FileNotFoundException
        // when Android's cache trimmer wiped media_staging/ between stages.
        // The fix at SharedMemoryPool.kt:1241 calls mkdirs() defensively, but
        // the binder mapping must still be correct in case some other
        // FS-level race re-introduces the FNF.
        every {
            MediaPartYPlaneExtractor.extractY(any(), any(), any())
        } throws FileNotFoundException(
            "/data/user/0/.../cache/media_staging/img_uuid.png: open failed: ENOENT",
        )

        val ex = invokeOcrImageAndCatch()
        val code = MindlayerErrorCode.codeFromWireMessage(ex.message)
        assertEquals(
            "Stage-write FileNotFoundException must map to TRANSIENT_RESOURCE_EXHAUSTED",
            MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED,
            code,
        )
        assertTrue(
            "wire payload must say 'stage failed' so dashboards can group these distinct from decode failures",
            ex.message?.contains("ocrImage stage failed") == true,
        )
    }

    @Test fun `IOException from broken pipe maps to TRANSIENT_RESOURCE_EXHAUSTED`() {
        every {
            MediaPartYPlaneExtractor.extractY(any(), any(), any())
        } throws IOException("write failed: EPIPE (Broken pipe)")

        val ex = invokeOcrImageAndCatch()
        val code = MindlayerErrorCode.codeFromWireMessage(ex.message)
        assertEquals(
            "Stage-write IOException must map to TRANSIENT_RESOURCE_EXHAUSTED",
            MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED,
            code,
        )
    }

    @Test fun `unknown RuntimeException maps to INTERNAL not INVALID_REQUEST`() {
        // The pre-disambiguation catch-all wrapped this as INVALID_REQUEST,
        // wrongly telling the SDK the caller did something invalid. INTERNAL
        // is the safe default for genuinely unexpected throwables — INVALID_REQUEST
        // should only fire when we know the caller is at fault.
        every {
            MediaPartYPlaneExtractor.extractY(any(), any(), any())
        } throws IllegalStateException("simulated internal invariant violation")

        val ex = invokeOcrImageAndCatch()
        val code = MindlayerErrorCode.codeFromWireMessage(ex.message)
        assertEquals(
            "Unknown throwable must map to INTERNAL, NOT INVALID_REQUEST",
            MindlayerErrorCode.INTERNAL,
            code,
        )
    }

    private fun invokeOcrImageAndCatch(): SecurityException {
        val binder = newBinder()
        val image = newRawYMediaPart()
        return assertThrows(SecurityException::class.java) {
            binder.ocrImage(image, OcrImageOptions())
        }.also {
            assertNotNull("ocrImage must throw a wire-prefixed message", it.message)
        }
    }

    private fun newRawYMediaPart(): MediaPart {
        // The binder validates the MediaPart shape via IpcInputValidator
        // before reaching the extractor catch ladder, so we need a payload
        // that survives validation. A raw-Y plane with sane dims is the
        // minimum: 16x16 = 256 bytes, declared mimeType matches.
        val pipe = ParcelFileDescriptor.createPipe()
        // Close write side immediately — the extractor is mocked, so we
        // never actually read from this PFD.
        try { pipe[1].close() } catch (_: Throwable) { /* fine */ }
        return MediaPart(
            requestId = "ocr-err-mapping-${System.nanoTime()}",
            kind = MediaPart.KIND_IMAGE,
            mimeType = IpcInputValidator.OCR_RAW_Y_PLANE_MIME,
            source = pipe[0],
            isSharedMemory = false,
            payloadBytes = 256L,
            width = 16,
            height = 16,
            rowStride = 16,
        )
    }

    private fun newBinder(): ServiceBinder {
        val service = mockk<MindlayerMlService>(relaxed = true)
        every { service.sessionManager } returns mockk<SessionManager>(relaxed = true)
        every { service.packageName } returns "com.adsamcik.mindlayer.service"

        val rateLimiter = mockk<RateLimiter>(relaxed = true)
        every { rateLimiter.tryAcquire(any(), any()) } returns true
        every { rateLimiter.tryAcquireRejected(any()) } returns true
        every { rateLimiter.tryAcquireRejection(any()) } returns true

        val allow = mockk<AllowlistStore>(relaxed = true)
        every { allow.isDenied(any(), any()) } returns false
        every { allow.isAllowed(any(), any()) } returns true

        return ServiceBinder(
            service = service,
            engineManager = mockk<EngineManager>(relaxed = true),
            orchestrator = mockk<InferenceOrchestrator>(relaxed = true),
            diagnosticExporter = mockk<DiagnosticExporter>(relaxed = true),
            thermalMonitor = mockk<ThermalMonitor>(relaxed = true) {
                every { currentPolicy } returns MutableStateFlow(mockk(relaxed = true))
            },
            memoryBudget = mockk<MemoryBudget>(relaxed = true),
            callerVerifier = { _, _ -> CallerIdentity("pkg", "sig", "Pkg") },
            allowlistStore = allow,
            rateLimiter = rateLimiter,
            embeddingCoordinator = mockk<EmbeddingCoordinator>(relaxed = true) {
                every { defaultModelOrNull() } returns null
            },
            ocrSessionManager = ocrManager,
            sharedMemoryPool = pool,
            paddleOcrEngine = engine,
        )
    }
}
