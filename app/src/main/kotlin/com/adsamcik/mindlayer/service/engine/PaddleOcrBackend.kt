package com.adsamcik.mindlayer.service.engine

/**
 * Backend interface for the PaddleOCR PP-OCRv5 mobile pipeline.
 *
 * Mirrors [EmbeddingBackend] in shape but loads a **bundle** of three
 * ``.tflite`` files (det / rec / cls) plus a character dictionary.
 *
 * Implementations:
 *   - [LiteRtPaddleOcrBackend] — production, uses ``com.google.ai.edge.litert``.
 *   - Test fakes (see ``PaddleOcrEngineTest``).
 *
 * # Threading
 *
 * The [PaddleOcrEngine] orchestrator serialises all calls through a
 * per-engine [kotlinx.coroutines.sync.Mutex]; implementations may
 * assume single-writer access and need not lock internally for
 * inference (init/shutdown still need their own discipline if they
 * spawn background threads).
 */
interface PaddleOcrBackend {

    /** Hardware backend label: "NPU", "GPU", "CPU", or "NONE" before init. */
    val activeBackend: String

    val isInitialized: Boolean

    /** The loaded bundle, or null when not yet initialised. */
    val currentBundle: PaddleOcrModelInfo?

    /**
     * Load [bundle] with the given backend preference chain.
     *
     * Implementations MUST be idempotent: a second initialize call with
     * the same bundle is a no-op fast path. Throws [LowMemoryException]
     * when RAM is below total bundle size + headroom. Throws a typed
     * exception (or [IllegalStateException] with a [safeLabel]) on any
     * other init failure — the engine layer catches and converts.
     */
    suspend fun initialize(
        bundle: PaddleOcrModelInfo,
        preferredBackend: String? = null,
    )

    /**
     * Recognise text in a single frame.
     *
     * @param yPlane 8-bit greyscale row-major Y data.
     * @param width column count.
     * @param height row count.
     * @param config per-frame knobs (bounding boxes, max lines, etc.)
     */
    suspend fun recognise(
        yPlane: ByteArray,
        width: Int,
        height: Int,
        config: OcrEngineConfig = OcrEngineConfig(),
    ): OcrEngineOutput

    /** Release native resources. Idempotent. */
    suspend fun shutdown()
}
