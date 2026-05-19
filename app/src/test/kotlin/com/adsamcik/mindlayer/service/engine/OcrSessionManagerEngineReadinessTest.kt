package com.adsamcik.mindlayer.service.engine

import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pure-JVM-ish (Robolectric for Android context) tests for the Phase 3 #1
 * wire-up of [OcrSessionManager.isEngineReady] against a real
 * [PaddleOcrEngine] state machine.
 *
 * Distinct from `ServiceBinderOcrCapabilityTest`, which mocks
 * `isEngineReady` — here we exercise the actual `PaddleOcrEngine.state`
 * <-> manager wiring with a fake backend so the lifecycle transitions
 * are pinned at the engine level.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OcrSessionManagerEngineReadinessTest {

    @Test fun `idle engine is not ready`() {
        val engine = engineWithBackend(FakePaddleOcrBackend(preInitialized = false))
        val manager = OcrSessionManager(engine = engine)
        assertEquals(PaddleOcrEngineState.Idle, engine.state.value)
        assertFalse(manager.isEngineReady())
    }

    @Test fun `engine that reports already-initialised flips ready to true`() = runTest {
        val backend = FakePaddleOcrBackend(preInitialized = true)
        val engine = engineWithBackend(backend)
        val manager = OcrSessionManager(engine = engine)
        // initializeLocked's fast path sees `backend.isInitialized == true`
        // + non-null `currentBundle`, sets state to Ready, returns the bundle.
        engine.initialize()
        assertEquals(PaddleOcrEngineState.Ready, engine.state.value)
        assertTrue(manager.isEngineReady())
    }

    @Test fun `missing bundle leaves engine Failed and not ready`() = runTest {
        // FakePaddleOcrBackend with preInitialized=false forces the
        // initializeLocked path that calls PaddleOcrModelRegistry —
        // which under Robolectric finds no AI-Pack assets, so the
        // engine settles into Failed(ModelMissing).
        val engine = engineWithBackend(FakePaddleOcrBackend(preInitialized = false))
        val manager = OcrSessionManager(engine = engine)
        try { engine.initialize() } catch (_: Throwable) { /* expected */ }
        assertTrue(
            "expected Failed state, got ${engine.state.value}",
            engine.state.value is PaddleOcrEngineState.Failed,
        )
        assertFalse(manager.isEngineReady())
    }

    @Test fun `shutdown flips ready back to false`() = runTest {
        val engine = engineWithBackend(FakePaddleOcrBackend(preInitialized = true))
        val manager = OcrSessionManager(engine = engine)
        engine.initialize()
        assertTrue(manager.isEngineReady())
        engine.shutdown()
        assertEquals(PaddleOcrEngineState.Idle, engine.state.value)
        assertFalse(manager.isEngineReady())
    }

    @Test fun `manager without engine is never ready`() {
        val manager = OcrSessionManager(engine = null)
        assertFalse(manager.isEngineReady())
    }

    // ── helpers ──

    private fun engineWithBackend(backend: PaddleOcrBackend): PaddleOcrEngine =
        PaddleOcrEngine(
            context = ApplicationProvider.getApplicationContext(),
            backendFactory = { backend },
        )

    /**
     * Test fake. When [preInitialized] is true, the backend reports
     * itself as already-loaded with a fixed sentinel bundle so
     * [PaddleOcrEngine.initializeLocked] takes the fast path and skips
     * the [PaddleOcrModelRegistry] discovery (which would otherwise
     * fail under Robolectric without an AI-Pack-bundled asset).
     */
    private class FakePaddleOcrBackend(preInitialized: Boolean) : PaddleOcrBackend {

        private val initial: PaddleOcrModelInfo? = if (preInitialized) {
            PaddleOcrModelInfo(
                id = "fake-bundle",
                displayName = "Fake Test Bundle",
                detectionPath = "/dev/null",
                recognitionPath = "/dev/null",
                classifierPath = null,
                dictionaryPath = "/dev/null",
                totalSizeBytes = 0L,
                detSha256 = null,
                recSha256 = null,
                clsSha256 = null,
                dictSha256 = null,
            )
        } else {
            null
        }

        @Volatile private var loaded: PaddleOcrModelInfo? = initial

        override val isInitialized: Boolean get() = loaded != null
        override val currentBundle: PaddleOcrModelInfo? get() = loaded
        override val activeBackend: String get() = if (loaded != null) "CPU" else "NONE"

        override suspend fun initialize(
            bundle: PaddleOcrModelInfo,
            preferredBackend: String?,
        ) {
            loaded = bundle
        }

        override suspend fun recognise(
            yPlane: ByteArray,
            width: Int,
            height: Int,
            config: OcrEngineConfig,
        ): OcrEngineOutput = OcrEngineOutput(
            lines = emptyList(),
            backend = "CPU",
            detDurationMs = 0,
            recDurationMs = 0,
            clsDurationMs = 0,
            totalDurationMs = 0,
        )

        override suspend fun shutdown() {
            loaded = null
        }
    }
}
