package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrLimits
import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.OcrSessionState
import com.adsamcik.mindlayer.MediaPart
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Builder-style configuration for an OCR session.
 *
 * Use [Mindlayer.ocrSession] for the conventional DSL entry point:
 *
 * ```kotlin
 * val session = mindlayer.ocrSession {
 *     profile(OcrProfile.Receipt)
 *     // Optional overrides:
 *     languageHints = listOf("en", "de-DE")
 *     maxFrames = 30
 *     frameRateLimit = 5
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

    @Volatile
    private var pageBoundariesConfig: PageBoundariesBuilder? = null

    /**
     * Configure v0.9 page-boundary detection. When left untouched (the
     * builder is never invoked), the session behaves identically to v0.8
     * — single session-end [OcrEvent.ResultFinalized], no per-page
     * [OcrEvent.PageStarted] / [OcrEvent.PageFinalized] events.
     *
     * When enabled, the service detects when the camera moves to
     * different content (Jaccard text overlap + spatial bbox shift +
     * gyro spike, gated by an N-frame stability window), emits a fresh
     * [OcrEvent.PageStarted] / [OcrEvent.PageFinalized] pair per page,
     * and still emits a single session-end [OcrEvent.ResultFinalized].
     *
     * For the gyro signal to contribute, frames must include IMU samples
     * in [com.adsamcik.mindlayer.OcrFrameMeta.extraJson] — the
     * `:sdk-camerax` `OcrImageAnalyzer` forwards them automatically when
     * constructed with a [android.hardware.SensorManager]. Without IMU
     * samples the heuristic falls back to text + spatial signals alone
     * (still useful, just slightly less robust to held-by-hand shake).
     *
     * Calling this method multiple times is idempotent — only the last
     * configured block wins. The block is serialised into [optionsJson]
     * at [build] time; if the caller also supplied a raw `optionsJson`,
     * the two are merged with caller-supplied keys winning on collision
     * (consistent with how every other opaque-JSON envelope on the SDK
     * surface is handled).
     *
     * @see PageBoundariesBuilder for the per-knob KDoc.
     */
    fun pageBoundaries(configure: PageBoundariesBuilder.() -> Unit = {}) {
        val builder = PageBoundariesBuilder()
        builder.configure()
        pageBoundariesConfig = builder
    }

    internal fun build(): OcrSessionConfig = OcrSessionConfig(
        mode = profile.mode,
        outputSchemaJson = outputSchemaJson,
        languageHints = languageHints,
        maxFrames = maxFrames,
        frameRateLimitFps = frameRateLimitFps,
        optionsJson = mergedOptionsJson(),
    )

    private fun mergedOptionsJson(): String? {
        val pageBoundariesBlock = pageBoundariesConfig?.toJsonObject() ?: return optionsJson
        val callerObject = optionsJson?.takeIf { it.isNotBlank() }?.let {
            runCatching { OCR_OPTIONS_JSON.parseToJsonElement(it) as? JsonObject }
                .getOrNull()
        }
        // Caller-supplied keys win on collision (consistent with how
        // `extraContextJson` and friends are merged elsewhere in the SDK).
        val merged = buildJsonObject {
            put(KEY_PAGE_BOUNDARIES, pageBoundariesBlock)
            callerObject?.forEach { (key, value) -> put(key, value) }
        }
        return merged.toString()
    }

    private companion object {
        const val KEY_PAGE_BOUNDARIES = "pageBoundaries"
        val OCR_OPTIONS_JSON = Json { ignoreUnknownKeys = true; isLenient = true }
    }
}

/**
 * DSL block for [OcrSessionConfigBuilder.pageBoundaries].
 *
 * Every knob has a documented default chosen to match what
 * [com.adsamcik.mindlayer.service.engine.PageBoundariesConfig.parse] reads
 * server-side; out-of-range values are clamped on the service. Callers
 * may safely tweak only the knobs they care about.
 *
 * # Defaults
 *
 * | Knob | Default | Range / clamp |
 * |---|---|---|
 * | [enabled] | `true` | — calling the [OcrSessionConfigBuilder.pageBoundaries] block opts in |
 * | [jaccardThreshold] | `0.3f` | `0.0..1.0` |
 * | [spatialThreshold] | `0.5f` | `0.0..2.0` |
 * | [gyroThreshold] | `2.0f` | `0.0..50.0` |
 * | [stabilityFrames] | `3` | `1..30` |
 * | [llmExtractPerPage] | `false` | — defer per-page LLM extraction to session finalize when enabled |
 * | [llmExtractFinal] | `true` | — aggregate-LLM extraction on `OCR_RESULT_FINALIZED.fullJson` |
 *
 * See `docs/OCR_API.md` ("Multi-page realtime (v0.9 — preview)") for
 * the full boundary-detection rule and event-sequence example.
 */
class PageBoundariesBuilder {

    /**
     * Master switch. `true` opts the session into per-page events.
     * Defaults to `true` because the builder being invoked at all is
     * already an opt-in gesture; callers that want to wire the block
     * conditionally can flip it to `false` to keep v0.8 behavior.
     */
    var enabled: Boolean = true

    /**
     * Token-set Jaccard below this between successive frames signals
     * "different content" for one frame. Clamped server-side to
     * `0.0..1.0`.
     */
    var jaccardThreshold: Float = 0.3f

    /**
     * Manhattan distance (in normalised 0..1 frame coordinates, summed
     * across x+y axes) between successive bbox centroids that signals
     * "camera pan". Clamped server-side to `0.0..2.0`.
     */
    var spatialThreshold: Float = 0.5f

    /**
     * `gyro_max_rad_per_s` (from
     * [com.adsamcik.mindlayer.OcrFrameMeta.extraJson]'s `imu` block)
     * above this signals "physical motion". Clamped server-side to
     * `0.0..50.0`.
     */
    var gyroThreshold: Float = 2.0f

    /**
     * Number of consecutive "different" frames required before a
     * boundary fires. Filters single-frame glitches (focus search,
     * hand obscuring the page). Clamped server-side to `1..30`.
     */
    var stabilityFrames: Int = 3

    /**
     * When `true`, the service runs the OCR LLM extractor once per
     * page during session finalize; the result is attached to that
     * page's [OcrEvent.PageFinalized.fullJson]. Off by default —
     * per-page LLM extraction is heavyweight.
     */
    var llmExtractPerPage: Boolean = false

    /**
     * When `true`, the service runs the OCR LLM extractor once on the
     * aggregated text of all pages at session finalize and attaches
     * the result to [OcrEvent.ResultFinalized.fullJson]. When `false`,
     * the same field carries a lightweight rollup shape
     * (`{"pages":[…]}`) instead.
     */
    var llmExtractFinal: Boolean = true

    internal fun toJsonObject(): JsonObject = buildJsonObject {
        put("enabled", enabled)
        put("jaccardThreshold", jaccardThreshold)
        put("spatialThreshold", spatialThreshold)
        put("gyroThreshold", gyroThreshold)
        put("stabilityFrames", stabilityFrames)
        put("llmExtractPerPage", llmExtractPerPage)
        put("llmExtractFinal", llmExtractFinal)
    }
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
    private val mindlayer: MindlayerImpl,
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
            // Bug #7: provide bound context so the transport selector
            // uses a regular-file PFD (not a pipe) and survives the
            // service's H5 hardening.
            context = mindlayer.connection.getContext(),
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
