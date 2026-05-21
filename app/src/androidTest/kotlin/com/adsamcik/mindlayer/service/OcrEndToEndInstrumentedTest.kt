package com.adsamcik.mindlayer.service

import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import android.graphics.Color
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.adsamcik.mindlayer.MediaPart
import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrFrameMeta
import com.adsamcik.mindlayer.OcrLimits
import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.sdk.OcrEvent
import com.adsamcik.mindlayer.sdk.OcrTokenStreamReader
import com.adsamcik.mindlayer.service.engine.EmbeddingCoordinator
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.OcrEngineConfig
import com.adsamcik.mindlayer.service.engine.OcrEngineOutput
import com.adsamcik.mindlayer.service.engine.OcrEvidencePackage
import com.adsamcik.mindlayer.service.engine.OcrExtractedField
import com.adsamcik.mindlayer.service.engine.OcrExtractionResult
import com.adsamcik.mindlayer.service.engine.OcrFieldFusion
import com.adsamcik.mindlayer.service.engine.OcrLlmExtractor
import com.adsamcik.mindlayer.service.engine.OcrRecognitionDispatcher
import com.adsamcik.mindlayer.service.engine.OcrSessionManager
import com.adsamcik.mindlayer.service.engine.OcrTextLine
import com.adsamcik.mindlayer.service.engine.PaddleOcrBackend
import com.adsamcik.mindlayer.service.engine.PaddleOcrEngine
import com.adsamcik.mindlayer.service.engine.PaddleOcrModelInfo
import com.adsamcik.mindlayer.service.engine.SessionManager
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CallerIdentity
import com.adsamcik.mindlayer.service.security.RateLimiter
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 27)
class OcrEndToEndInstrumentedTest {

    private val baseContext: Context
        get() = ApplicationProvider.getApplicationContext()

    @After
    fun tearDown() {
        File(baseContext.filesDir, "ocr-e2e-smoke").deleteRecursively()
        File(baseContext.cacheDir, "ocr-e2e-smoke").deleteRecursively()
    }

    @Test
    fun encodedImage_pushFrame_finalize_emitsStructuredResult() = runBlocking {
        val context = isolatedContext()
        seedPaddleOcrBundle(context.filesDir)
        val fakeBackend = FakePaddleOcrBackend()
        val engine = PaddleOcrEngine(context, backendFactory = { fakeBackend })
        val dispatcher = OcrRecognitionDispatcher(
            engine = engine,
            barcodeDetector = null,
            llmExtractor = FixedOcrExtractor(),
        )
        val manager = OcrSessionManager(
            engine = engine,
            limits = e2eLimits(),
            recognitionDispatcher = dispatcher,
        )
        val binder = serviceBinder(context, manager)
        val sessionId = binder.createOcrSession(
            OcrSessionConfig(
                mode = OcrSessionConfig.MODE_RECEIPT,
                outputSchemaJson = """{"type":"object","properties":{"total":{"type":"string"}}}""",
                maxFrames = 3,
            ),
        )
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val eventsDeferred = async(Dispatchers.IO) {
            val events = mutableListOf<OcrEvent>()
            withTimeout(10_000L) {
                OcrTokenStreamReader.readStream(readEnd).toList(events)
            }
            events
        }

        try {
            binder.streamOcrEvents(sessionId, writeEnd)
            val frame = encodedImagePart("ocr-e2e-frame-1", writeFixturePng(context.cacheDir))
            val ack = binder.pushOcrFrame(
                sessionId,
                frame,
                OcrFrameMeta(
                    frameId = 1L,
                    captureTimeMs = 1L,
                    rotationDegrees = 0,
                    qualityHint = OcrFrameMeta.QUALITY_GOOD,
                ),
            )
            assertEquals(OcrFrameAck.STATUS_ACCEPTED, ack.status)

            binder.finalizeOcrSession(sessionId)
            val events = eventsDeferred.await()

            assertTrue(events.any { it is OcrEvent.FrameProcessed && it.frameId == 1L && it.lineCount == 1 })
            assertTrue(
                events.any {
                    it is OcrEvent.FieldUpdate &&
                        it.fieldName == "extract.total" &&
                        it.topValue == "12.34"
                },
            )
            val finalEvent = events.filterIsInstance<OcrEvent.ResultFinalized>().single()
            assertTrue(finalEvent.fullJson.contains(""""total":"12.34""""))
        } finally {
            binder.closeOcrSession(sessionId)
            dispatcher.shutdown()
            runCatching { readEnd.close() }
            runCatching { writeEnd.close() }
        }
    }

    private fun isolatedContext(): Context {
        val files = File(baseContext.filesDir, "ocr-e2e-smoke/files").apply {
            deleteRecursively()
            mkdirs()
        }
        val cache = File(baseContext.cacheDir, "ocr-e2e-smoke/cache").apply {
            deleteRecursively()
            mkdirs()
        }
        return object : ContextWrapper(baseContext) {
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
        }
    }

    private fun serviceBinder(context: Context, manager: OcrSessionManager): ServiceBinder {
        val service = mockk<MindlayerMlService>(relaxed = true)
        every { service.sessionManager } returns mockk<SessionManager>(relaxed = true)
        every { service.packageName } returns context.packageName
        val rateLimiter = mockk<RateLimiter>(relaxed = true)
        every { rateLimiter.tryAcquire(any(), any()) } returns true
        every { rateLimiter.tryAcquireRejected(any()) } returns true
        every { rateLimiter.tryAcquireRejection(any()) } returns true
        val allowlist = mockk<AllowlistStore>(relaxed = true)
        every { allowlist.isDenied(any(), any()) } returns false
        every { allowlist.isAllowed(any(), any()) } returns true
        return ServiceBinder(
            service = service,
            engineManager = mockk<EngineManager>(relaxed = true),
            orchestrator = mockk<InferenceOrchestrator>(relaxed = true),
            diagnosticExporter = mockk<DiagnosticExporter>(relaxed = true),
            thermalMonitor = mockk<ThermalMonitor>(relaxed = true),
            memoryBudget = mockk<MemoryBudget>(relaxed = true),
            context = context,
            callerVerifier = { _, _ -> CallerIdentity(context.packageName, "self", "Mindlayer test") },
            allowlistStore = allowlist,
            rateLimiter = rateLimiter,
            embeddingCoordinator = mockk<EmbeddingCoordinator>(relaxed = true),
            ocrSessionManager = manager,
            sharedMemoryPool = SharedMemoryPool(context.cacheDir),
        )
    }

    private fun e2eLimits(): OcrLimits = OcrLimits(
        maxConcurrentOcrSessions = 1,
        maxOcrFramesPerMinute = 30,
        maxFramesPerOcrSession = 3,
        maxOcrSessionDurationMs = 60_000L,
        ocrPerFrameDecodeBudgetTokens = 256,
        ocrSchemaJsonMaxLen = 8 * 1024,
    )

    private fun seedPaddleOcrBundle(dir: File) {
        File(dir, "paddleocr-ppocrv5-mobile-det.tflite").writeBytes(byteArrayOf(1))
        File(dir, "paddleocr-ppocrv5-mobile-rec.tflite").writeBytes(byteArrayOf(2))
        File(dir, "paddleocr-ppocrv5-mobile-dict.txt").writeText(
            (0 until 128).joinToString(separator = "\n") { "tok$it" },
        )
    }

    private fun writeFixturePng(dir: File): File {
        val file = File(dir, "ocr-e2e-fixture.png")
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.ARGB_8888)
        try {
            for (y in 0 until bitmap.height) {
                for (x in 0 until bitmap.width) {
                    val darkInk = ((x / 8) + (y / 6)) % 2 == 0
                    bitmap.setPixel(x, y, if (darkInk) Color.BLACK else Color.WHITE)
                }
            }
            FileOutputStream(file).use { out ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                    "Failed to encode OCR fixture PNG"
                }
            }
        } finally {
            bitmap.recycle()
        }
        return file
    }

    private fun encodedImagePart(requestId: String, file: File): MediaPart =
        MediaPart(
            requestId = requestId,
            kind = MediaPart.KIND_IMAGE,
            mimeType = "image/png",
            source = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY),
            isSharedMemory = false,
            payloadBytes = file.length(),
        )

    private class FakePaddleOcrBackend : PaddleOcrBackend {
        override var activeBackend: String = "NONE"
            private set
        override var isInitialized: Boolean = false
            private set
        override var currentBundle: PaddleOcrModelInfo? = null
            private set

        override suspend fun initialize(bundle: PaddleOcrModelInfo, preferredBackend: String?) {
            currentBundle = bundle
            activeBackend = "CPU"
            isInitialized = true
        }

        override suspend fun recognise(
            yPlane: ByteArray,
            width: Int,
            height: Int,
            config: OcrEngineConfig,
        ): OcrEngineOutput {
            return OcrEngineOutput(
                lines = listOf(
                    OcrTextLine(
                        text = "TOTAL 12.34",
                        confidence = OcrFieldFusion.Confidence.HIGH,
                        boundingBox = floatArrayOf(0.1f, 0.1f, 0.9f, 0.1f, 0.9f, 0.2f, 0.1f, 0.2f),
                    ),
                ),
                backend = activeBackend,
                detDurationMs = 1L,
                recDurationMs = 1L,
                clsDurationMs = 0L,
                totalDurationMs = 2L,
            )
        }

        override suspend fun shutdown() {
            isInitialized = false
            currentBundle = null
            activeBackend = "NONE"
        }
    }

    private class FixedOcrExtractor : OcrLlmExtractor {
        override suspend fun extract(evidence: OcrEvidencePackage): OcrExtractionResult {
            require(evidence.textLines.any { it.text == "TOTAL 12.34" })
            return OcrExtractionResult(
                fields = listOf(
                    OcrExtractedField(
                        name = "total",
                        value = "12.34",
                        confidence = OcrFieldFusion.Confidence.HIGH,
                    ),
                ),
                rawJson = """{"total":"12.34"}""",
            )
        }
    }
}
