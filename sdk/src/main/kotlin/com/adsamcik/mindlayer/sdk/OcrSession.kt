package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrLimits
import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.OcrSessionState
import com.adsamcik.mindlayer.MediaPart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow

/**
 * Builder-style configuration for an OCR session.
 *
 * Use [Mindlayer.ocrRealtime] for the conventional DSL entry point:
 *
 * ```kotlin
 * val session = mindlayer.ocrRealtime(OcrProfile.Receipt) {
 *     // Optional overrides:
 *     languageHints = listOf("en", "de-DE")
 *     maxFrames = 30
 *     frameRateLimitFps = 5
 * }
 * ```
 *
 * The resulting [OcrSessionConfig] is what the service receives over
 * AIDL. Callers may also build a config directly via
 * [OcrSessionConfig]'s constructor if they need fine control.
 */
class OcrSessionConfigBuilder internal constructor(private val profile: OcrProfile) {

    /** Optional custom JSON schema. Defaults to the profile's schema. */
    var outputSchemaJson: String = profile.defaultSchema

    /** BCP-47 language hints. */
    var languageHints: List<String> = emptyList()

    /** Per-session frame cap. 0 = use service default. */
    var maxFrames: Int = 0

    /** Caller-requested frame-rate cap. 0 = use service default. */
    var frameRateLimitFps: Int = 0

    /** Opaque JSON envelope for forward-compatible knobs. */
    var optionsJson: String? = null

    internal fun build(): OcrSessionConfig = OcrSessionConfig(
        mode = profile.mode,
        outputSchemaJson = outputSchemaJson,
        languageHints = languageHints,
        maxFrames = maxFrames,
        frameRateLimitFps = frameRateLimitFps,
        optionsJson = optionsJson,
    )
}

/**
 * SDK-side handle to a live multi-frame OCR session.
 *
 * Wraps the seven AIDL methods (``createOcrSession``, ``pushOcrFrame``,
 * ``streamOcrEvents``, ``getOcrSessionState``, ``finalizeOcrSession``,
 * ``closeOcrSession``, ``getOcrLimits``) behind a Kotlin-idiomatic
 * surface.
 *
 * # Lifecycle
 *
 * Create via [Mindlayer.ocrRealtime]. Call [pushFrame] for each captured
 * frame; the synchronous return is an [OcrFrameAck] indicating intake
 * outcome. Asynchronous recognition results stream via [events] (when
 * the service-side recognition path is wired — Phase 1 ships only the
 * session lifecycle, so [events] in Phase 1 emits only the
 * synchronous intake events).
 *
 * When done, call [finalize] (waits for processing to drain and final
 * result event), then [close] to release resources. The session
 * supports [AutoCloseable] for ``use { }`` idioms.
 *
 * # Thread safety
 *
 * Safe to call any method from any thread. The wrapper does NOT
 * serialise calls internally beyond what AIDL already does — the
 * service-side [com.adsamcik.mindlayer.service.engine.OcrSessionManager]
 * is concurrent.
 *
 * # Phase 2 #2 status
 *
 * - ``events`` is now a real [Flow] backed by the OCR_V1 protocol
 *   pipe. The service emits OCR_FRAME_* and OCR_FIELD_* events as
 *   frames flow through the session; when the engine recognition
 *   path lands (track p2-engine-pipeline) those events surface
 *   recognized text. Until then, the flow is reachable but typically
 *   empty (the service only emits ``ocr_frame_received`` /
 *   ``ocr_frame_rejected_quality`` / ``ocr_frame_dropped_busy``
 *   events because no engine runs yet).
 * - The synchronous [pushFrame] returns the real wire-stable
 *   [OcrFrameAck], including ``REJECTED_QUALITY`` verdicts from the
 *   service-side presort wired in Phase 2 #1.
 * - ``Mindlayer.capabilities.supportsFeature(FEATURE_OCR_SESSION)``
 *   returns ``false`` until the engine + extraction land
 *   (p2-feature-flip). Capability-aware callers should check this
 *   before opening a session.
 */
class OcrSession internal constructor(
    val sessionId: String,
    val config: OcrSessionConfig,
    private val mindlayer: Mindlayer,
) : AutoCloseable {

    @Volatile
    private var closed: Boolean = false

    /** Push a raw Y-plane OCR frame. */
    suspend fun pushFrame(
        meta: com.adsamcik.mindlayer.OcrFrameMeta,
        yPlane: ByteArray,
        width: Int,
        height: Int,
        rowStride: Int = width,
        pixelStride: Int = 1,
    ): OcrFrameAck {
        checkNotClosed()
        val part = MediaTransfer.ocrYPlanePart(
            requestId = "ocr-frame-${java.util.UUID.randomUUID()}",
            yPlane = yPlane,
            width = width,
            height = height,
            rowStride = rowStride,
            pixelStride = pixelStride,
        )
        return mindlayer.pushOcrFrame(sessionId, part, meta)
    }

    /** Push an encoded JPEG/PNG/WEBP OCR frame. */
    suspend fun pushEncodedFrame(
        meta: com.adsamcik.mindlayer.OcrFrameMeta,
        bytes: ByteArray,
        mimeType: String,
    ): OcrFrameAck {
        checkNotClosed()
        val part = MediaTransfer.ocrEncodedImagePart(
            requestId = "ocr-frame-${java.util.UUID.randomUUID()}",
            bytes = bytes,
            mimeType = mimeType,
        )
        return mindlayer.pushOcrFrame(sessionId, part, meta)
    }

    /**
     * Cold [Flow] of OCR events for this session. Attach the pipe
     * once; subsequent collects on the returned Flow re-attach a
     * fresh pipe so multiple observers each get their own stream.
     *
     * Cancelling the collecting coroutine closes the read-end and
     * the service-side writer surfaces the disconnect on its next
     * emit (silently no-ops further events).
     */
    val events: Flow<OcrEvent> get() = flow {
        checkNotClosed()
        val pipe = android.os.ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        try {
            mindlayer.attachOcrEventStream(sessionId, writeEnd)
        } catch (t: Throwable) {
            try { readEnd.close() } catch (_: Throwable) {}
            try { writeEnd.close() } catch (_: Throwable) {}
            throw t
        }
        // Service has taken ownership of writeEnd via the binder
        // call (the AIDL transport dup'd it; our local copy is now
        // safe to close). The service-side OcrTokenStreamWriter
        // will close its dup'd FD on session end.
        try { writeEnd.close() } catch (_: Throwable) {}
        emitAll(OcrTokenStreamReader.readStream(readEnd))
    }

    /** Snapshot of the session's current state. */
    suspend fun state(): OcrSessionState {
        checkNotClosed()
        return mindlayer.getOcrSessionState(sessionId)
    }

    /**
     * Mark the session as finalizing. The service drains any
     * in-flight processing and emits a final [OcrEvent.ResultFinalized]
     * event (once the engine recognition path is wired).
     */
    suspend fun finalize() {
        checkNotClosed()
        mindlayer.finalizeOcrSession(sessionId)
    }

    /** Close + release. Idempotent. */
    override fun close() {
        if (closed) return
        closed = true
        // Fire-and-forget — close is idempotent on the service side.
        mindlayer.closeOcrSessionFireAndForget(sessionId)
    }

    private fun checkNotClosed() {
        check(!closed) { "OcrSession $sessionId is closed" }
    }
}

/**
 * Live limits the connected Mindlayer service advertises for OCR.
 *
 * Returned by [Mindlayer.ocrLimits]. Cached after first call within
 * a connection lifetime; reconnect clears the cache.
 */
typealias OcrLimitsSnapshot = OcrLimits
