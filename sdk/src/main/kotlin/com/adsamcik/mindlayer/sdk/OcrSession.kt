package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrLimits
import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.OcrSessionState

/**
 * Builder-style configuration for an OCR session.
 *
 * Use [Mindlayer.ocrSession] for the conventional DSL entry point:
 *
 * ```kotlin
 * val session = mindlayer.ocrSession(OcrProfile.Receipt) {
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
 * Create via [Mindlayer.ocrSession]. Call [pushFrame] for each captured
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
 * # Phase 1 limitations
 *
 * - ``events`` returns an empty stream until the engine recognition
 *   path is wired (the service closes the pipe immediately at this
 *   stage).
 * - The synchronous [pushFrame] / [pushFrameMetadataOnly] return the
 *   real wire-stable [OcrFrameAck], including ``REJECTED_QUALITY``
 *   verdicts from the service-side presort.
 * - ``Mindlayer.capabilities.supportsFeature(FEATURE_OCR_SESSION)``
 *   returns ``false`` until the engine is wired. Capability-aware
 *   callers should check this before opening a session.
 */
class OcrSession internal constructor(
    val sessionId: String,
    val config: OcrSessionConfig,
    private val mindlayer: Mindlayer,
) : AutoCloseable {

    @Volatile
    private var closed: Boolean = false

    /**
     * Push a frame's metadata into the session intake.
     *
     * In Phase 1 PR C3 the service-side accepts metadata-only (the
     * `frame` parameter on the AIDL call is still required for wire
     * compatibility but the service does not extract Y-plane pixels
     * yet). A follow-up wires pixel extraction and runs the full
     * service-side quality presort.
     *
     * Returns the intake verdict; check [OcrFrameAck.status] for the
     * outcome (or use the [isAccepted] / [isDroppedBusy] etc. helpers).
     */
    suspend fun pushFrame(meta: com.adsamcik.mindlayer.OcrFrameMeta): OcrFrameAck {
        checkNotClosed()
        return mindlayer.pushOcrFrameMetadataOnly(sessionId, meta)
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
