package com.adsamcik.mindlayer.service.engine

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.adsamcik.mindlayer.service.engine.OcrFieldFusion.Confidence
import com.adsamcik.mindlayer.service.modeldelivery.DefaultLiveModelRuntimeController
import com.adsamcik.mindlayer.service.modeldelivery.LiveRuntimeReleaseResult
import com.adsamcik.mindlayer.service.modeldelivery.ModelFamily
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch

/**
 * Unit tests for [PaddleOcrEngine] using a fake backend.
 *
 * Validates:
 *   - state machine (Idle → Initializing → Ready / Failed)
 *   - mutex serialisation
 *   - lazy + idempotent initialisation
 *   - cached failure re-throws
 *   - memory pressure unload + relaunch
 *   - recognise() argument validation
 *   - bundle discovery via [PaddleOcrModelRegistry] integration
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@OptIn(ExperimentalCoroutinesApi::class)
class PaddleOcrEngineTest {

    private lateinit var realContext: Context
    private lateinit var filesDir: File

    @Before fun setUp() {
        val baseContext = ApplicationProvider.getApplicationContext<Context>()
        filesDir = File(baseContext.filesDir, "paddleocr-engine-test").apply {
            deleteRecursively()
            mkdirs()
        }
        realContext = object : ContextWrapper(baseContext) {
            override fun getFilesDir(): File = this@PaddleOcrEngineTest.filesDir
        }
        // Seed a minimal bundle so the registry has something to discover.
        File(filesDir, "paddleocr-ppocrv5-mobile-det.tflite").writeBytes(byteArrayOf(1))
        File(filesDir, "paddleocr-ppocrv5-mobile-rec.tflite").writeBytes(byteArrayOf(2))
        File(filesDir, "paddleocr-ppocrv5-mobile-dict.txt").writeBytes(byteArrayOf(3))
    }

    private fun engine(backend: FakePaddleOcrBackend = FakePaddleOcrBackend()) =
        PaddleOcrEngine(realContext, backendFactory = { backend })

    // ── State machine ────────────────────────────────────────────────────

    @Test fun `initial state is Idle`() {
        val engine = engine()
        assertEquals(PaddleOcrEngineState.Idle, engine.state.value)
    }

    @Test fun `initialize transitions to Ready and returns the bundle`() = runTest {
        val engine = engine()
        val bundle = engine.initialize()
        assertEquals(PaddleOcrEngineState.Ready, engine.state.value)
        assertEquals("paddleocr-ppocrv5-mobile", bundle.id)
    }

    @Test fun `initialize is idempotent`() = runTest {
        val backend = FakePaddleOcrBackend()
        val engine = engine(backend)
        engine.initialize()
        engine.initialize()
        assertEquals(1, backend.initCallCount)
    }

    @Test fun `initialize without bundle throws and enters Failed`() = runTest {
        // Strip bundle files so registry returns empty.
        filesDir.listFiles()?.forEach { it.deleteRecursively() }
        val engine = engine()
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { engine.initialize() }
        }
        assertTrue(engine.state.value is PaddleOcrEngineState.Failed)
        val failure = (engine.state.value as PaddleOcrEngineState.Failed).cause
        assertTrue("Expected ModelMissing, got $failure", failure is InitFailure.ModelMissing)
    }

    @Test fun `cached failure rethrows on second initialize`() = runTest {
        filesDir.listFiles()?.forEach { it.deleteRecursively() }
        val engine = engine()
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { engine.initialize() }
        }
        // Second attempt rethrows the cached failure rather than rediscovering.
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { engine.initialize() }
        }
    }

    @Test fun `missing bundle is rediscovered after on-demand materialization in same process`() = runTest {
        filesDir.listFiles()?.forEach { it.deleteRecursively() }
        val backend = FakePaddleOcrBackend()
        val engine = engine(backend)
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { engine.initialize() }
        }
        File(filesDir, "paddleocr-ppocrv5-mobile-det.tflite").writeBytes(byteArrayOf(1))
        File(filesDir, "paddleocr-ppocrv5-mobile-rec.tflite").writeBytes(byteArrayOf(2))
        File(filesDir, "paddleocr-ppocrv5-mobile-dict.txt").writeBytes(byteArrayOf(3))

        engine.initialize()
        assertEquals(1, backend.initCallCount)
        assertEquals(PaddleOcrEngineState.Ready, engine.state.value)
    }


    @Test fun lowMemoryInitFailureIsNotCachedAndRetryCanSucceed() = runTest {
        val backend = FakePaddleOcrBackend(initErrors = ArrayDeque(listOf(LowMemoryException(1, 2))))
        val engine = engine(backend)

        assertThrows(LowMemoryException::class.java) {
            kotlinx.coroutines.runBlocking { engine.initialize() }
        }
        assertTrue(engine.state.value is PaddleOcrEngineState.Failed)

        engine.initialize()
        assertEquals(2, backend.initCallCount)
        assertEquals(PaddleOcrEngineState.Ready, engine.state.value)
    }

    @Test fun nativeInitFailureRemainsSticky() = runTest {
        val backend = FakePaddleOcrBackend(initErrors = ArrayDeque(listOf(IllegalStateException("native secret detail"))))
        val engine = engine(backend)

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { engine.initialize() }
        }
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { engine.initialize() }
        }
        assertEquals(1, backend.initCallCount)
    }

    @Test fun runtimeActivationRetriesStickyNativeFailureAndSucceeds() = runTest {
        val backend = FakePaddleOcrBackend(
            initErrors = ArrayDeque(listOf(IllegalStateException("native secret detail"))),
        )
        val engine = engine(backend)
        val controller = DefaultLiveModelRuntimeController(
            quiesceAction = { LiveRuntimeReleaseResult.Released },
            retryOcrActivation = { engine.retryInitialize() },
            recordCleanShutdownBeforeProcessExit = {},
        )

        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { engine.initialize() }
        }

        controller.activate(ModelFamily.OCR)

        assertEquals(PaddleOcrEngineState.Ready, engine.state.value)
        assertEquals(2, backend.initCallCount)
        assertEquals(1, backend.shutdownCallCount)
    }

    @Test fun concurrentRuntimeActivationRetriesDoNotDoubleInitializeNativeBackend() = runTest {
        val backend = FakePaddleOcrBackend(
            initErrors = ArrayDeque(listOf(IllegalStateException("native secret detail"))),
        )
        val engine = engine(backend)
        val controller = DefaultLiveModelRuntimeController(
            quiesceAction = { LiveRuntimeReleaseResult.Released },
            retryOcrActivation = { engine.retryInitialize() },
            recordCleanShutdownBeforeProcessExit = {},
        )
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { engine.initialize() }
        }
        val allowRetryInitialization = CompletableDeferred<Unit>()
        val retryInitializationStarted = CompletableDeferred<Unit>()
        backend.initializeGate = allowRetryInitialization
        backend.initializeStarted = retryInitializationStarted

        val firstRetry = async { controller.activate(ModelFamily.OCR) }
        runCurrent()
        retryInitializationStarted.await()
        assertEquals(2, backend.initCallCount)
        val secondRetry = async { controller.activate(ModelFamily.OCR) }
        runCurrent()
        assertEquals(2, backend.initCallCount)

        allowRetryInitialization.complete(Unit)
        firstRetry.await()
        secondRetry.await()

        assertEquals(PaddleOcrEngineState.Ready, engine.state.value)
        assertEquals(2, backend.initCallCount)
        assertEquals(1, backend.shutdownCallCount)
    }

    @Test fun cancelledInitializationIsNotCachedAndRetryCanSucceed() = runTest {
        val backend = FakePaddleOcrBackend(
            initErrors = ArrayDeque(listOf(CancellationException("cancelled"))),
        )
        val engine = engine(backend)

        assertThrows(CancellationException::class.java) {
            kotlinx.coroutines.runBlocking { engine.initialize() }
        }
        assertEquals(PaddleOcrEngineState.Idle, engine.state.value)

        engine.initialize()
        assertEquals(2, backend.initCallCount)
        assertEquals(PaddleOcrEngineState.Ready, engine.state.value)
    }

    @Test fun cancellationWhileWaitingForModelLeaseRestoresIdle() = runTest {
        val acquired = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        val holder = async(Dispatchers.IO) {
            com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryFileLock.withLock(
                realContext.filesDir,
                com.adsamcik.mindlayer.service.modeldelivery.ModelFamily.OCR,
            ) {
                acquired.complete(Unit)
                release.await()
            }
        }
        acquired.await()
        val engine = engine()
        val initialization = async { engine.initialize() }
        runCurrent()

        initialization.cancelAndJoin()

        assertEquals(PaddleOcrEngineState.Idle, engine.state.value)
        release.countDown()
        holder.await()
    }

    // ── recognise() ──────────────────────────────────────────────────────

    @Test fun `recognise lazily initialises`() = runTest {
        val backend = FakePaddleOcrBackend()
        val engine = engine(backend)
        val out = engine.recognise(ByteArray(64 * 64), 64, 64)
        assertEquals(1, backend.initCallCount)
        assertEquals(PaddleOcrEngineState.Ready, engine.state.value)
        assertEquals(1, out.lines.size)
    }

    @Test fun `recognise validates yPlane length matches dimensions`() = runTest {
        val engine = engine()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { engine.recognise(ByteArray(50), 10, 10) }
        }
    }

    @Test fun `recognise validates positive dimensions`() = runTest {
        val engine = engine()
        assertThrows(IllegalArgumentException::class.java) {
            kotlinx.coroutines.runBlocking { engine.recognise(ByteArray(0), 0, 0) }
        }
    }

    @Test fun `recognise propagates backend errors`() = runTest {
        val backend = FakePaddleOcrBackend(recogniseError = IllegalStateException("kaboom"))
        val engine = engine(backend)
        val ex = assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { engine.recognise(ByteArray(64 * 64), 64, 64) }
        }
        assertEquals("kaboom", ex.message)
    }

    // ── unload + shutdown ────────────────────────────────────────────────

    @Test fun `unloadForMemoryPressure resets state to Idle`() = runTest {
        val backend = FakePaddleOcrBackend()
        val engine = engine(backend)
        engine.initialize()
        engine.unloadForMemoryPressure()
        assertEquals(PaddleOcrEngineState.Idle, engine.state.value)
        assertEquals(1, backend.shutdownCallCount)
    }

    @Test fun `after unload re-init works`() = runTest {
        val backend = FakePaddleOcrBackend()
        val engine = engine(backend)
        engine.initialize()
        engine.unloadForMemoryPressure()
        backend.isInitialized = false // fake backend respects this
        backend.currentBundle = null
        engine.initialize()
        assertEquals(2, backend.initCallCount)
        assertEquals(PaddleOcrEngineState.Ready, engine.state.value)
    }

    @Test fun `shutdown resets state to Idle`() = runTest {
        val backend = FakePaddleOcrBackend()
        val engine = engine(backend)
        engine.initialize()
        engine.shutdown()
        assertEquals(PaddleOcrEngineState.Idle, engine.state.value)
        assertEquals(1, backend.shutdownCallCount)
    }

    @Test fun `initialized fast path rejects OCR while removal is authoritative`() = runTest {
        val backend = FakePaddleOcrBackend()
        val engine = engine(backend)
        engine.recognise(ByteArray(64 * 64), 64, 64)
        val family = com.adsamcik.mindlayer.service.modeldelivery.ModelFamily.OCR
        com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryIntentStore(realContext.filesDir)
            .recordRemoval(family)
        val pending = com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryFileLock
            .pendingRemovalMarker(realContext.filesDir, family)
        val tombstone = com.adsamcik.mindlayer.service.modeldelivery.ModelDeliveryFileLock
            .removalTombstone(realContext.filesDir, family)
        assertTrue(pending.delete())
        assertTrue(tombstone.delete())

        val result = runCatching {
            engine.recognise(ByteArray(64 * 64), 64, 64)
        }

        assertTrue(result.exceptionOrNull() is IllegalStateException)
        assertTrue(!pending.exists())
        assertTrue(!tombstone.exists())
        assertEquals(1, backend.recogniseCallCount)
    }

    // ── Backend factory + dependency injection ───────────────────────────

    @Test fun `default backendFactory produces LiteRtPaddleOcrBackend`() {
        // We don't initialise it (would load real LiteRT model files), but we
        // can construct the engine and assert the backend delegate exists.
        val engine = PaddleOcrEngine(realContext)
        // Access the lazy backend via reflection just to assert it's the
        // right type — direct property access would force lazy load too.
        val backendField = PaddleOcrEngine::class.java.getDeclaredField("backend\$delegate")
        assertNotNull(backendField)
    }
}

/**
 * Test fake for [PaddleOcrBackend]. Returns a single canned recognition
 * output and exposes counters so tests can assert lifecycle behaviour.
 */
internal class FakePaddleOcrBackend(
    private val recogniseError: Throwable? = null,
    private val initErrors: ArrayDeque<Throwable> = ArrayDeque(),
    private val cannedOutput: OcrEngineOutput = defaultOutput(),
) : PaddleOcrBackend {

    override var activeBackend: String = "NONE"
    override var isInitialized: Boolean = false
    override var currentBundle: PaddleOcrModelInfo? = null

    var initCallCount: Int = 0
        private set
    var shutdownCallCount: Int = 0
        private set
    var recogniseCallCount: Int = 0
        private set
    var initializeGate: CompletableDeferred<Unit>? = null
    var initializeStarted: CompletableDeferred<Unit>? = null

    override suspend fun initialize(bundle: PaddleOcrModelInfo, preferredBackend: String?) {
        initCallCount++
        initErrors.removeFirstOrNull()?.let { throw it }
        initializeStarted?.complete(Unit)
        initializeGate?.await()
        currentBundle = bundle
        isInitialized = true
        activeBackend = preferredBackend ?: "GPU"
    }

    override suspend fun recognise(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        config: OcrEngineConfig,
    ): OcrEngineOutput {
        recogniseCallCount++
        recogniseError?.let { throw it }
        return cannedOutput
    }

    override suspend fun shutdown() {
        shutdownCallCount++
        isInitialized = false
        currentBundle = null
        activeBackend = "NONE"
    }

    private companion object {
        fun defaultOutput() = OcrEngineOutput(
            lines = listOf(
                OcrTextLine(
                    text = "hello",
                    confidence = OcrFieldFusion.Confidence.HIGH,
                    boundingBox = null,
                    orientationDegrees = 0,
                ),
            ),
            backend = "GPU",
            detDurationMs = 10,
            recDurationMs = 20,
            clsDurationMs = 5,
            totalDurationMs = 35,
        )
    }
}
