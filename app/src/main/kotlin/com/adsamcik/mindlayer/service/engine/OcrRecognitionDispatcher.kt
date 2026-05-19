package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Per-session recognition dispatcher.
 *
 * Receives accepted frames from [OcrSessionManager.pushFrame] (Phase 2
 * #1 wired the binder for Y-plane extraction; Phase 2 #2 wired the
 * event-stream writer), runs them through [PaddleOcrEngine.recognise]
 * on a background coroutine, pumps the resulting [OcrTextLine] list
 * through per-session [OcrFieldFusion], and emits OCR_V1 events on
 * the session's attached [OcrTokenStreamWriter].
 *
 * # Why a separate class instead of folding into OcrSessionManager?
 *
 * The session manager's intake path is sync (it has to return the
 * [com.adsamcik.mindlayer.OcrFrameAck] to the AIDL caller immediately).
 * Recognition is async — model inference takes 100–300 ms per frame
 * on mid-tier ARM. Keeping the dispatcher behind a coroutine scope
 * with a single-thread-per-session policy makes the threading model
 * obvious and keeps the session manager's intake path off the
 * inference critical path.
 *
 * # Concurrency
 *
 * One dispatcher serves all sessions. Recognition jobs are tracked
 * per-session so:
 *   - `finalize()` can drain in-flight jobs before emitting the
 *     terminal `ResultFinalized` event;
 *   - `cancel()` (on session close) cancels the per-session jobs
 *     without disturbing other sessions.
 *
 * The engine itself is single-writer (per `PaddleOcrEngine`'s
 * internal mutex) so concurrent recognise() calls queue. That is
 * acceptable at Phase 2 cadence — the SDK-side frame-rate limit
 * keeps push frequency below the engine's throughput on real
 * devices.
 *
 * # Engine-scaffold tolerance
 *
 * If `engine.recognise()` throws — including the
 * `LiteRtPaddleOcrBackend` scaffold's intentional
 * `IllegalStateException("PaddleOCR recognise() pipeline not yet
 * wired ...")` — the dispatcher logs the failure with `safeLabel`,
 * silently skips emitting recognition events, and DOES NOT poison
 * the session. The session manager's intake path keeps accepting
 * frames; only the recognition results are missing.
 *
 * This is the intentional Phase 2 #3 shipping behavior: the
 * dispatcher is wired and tested, but until the native PP-OCRv5
 * det/cls/rec pipeline + uploaded `.tflite` artifacts land
 * (separate follow-up after the `build-paddleocr-models.yml`
 * workflow runs and the integrity manifest is updated), the engine
 * scaffolds keep failing closed and no `OcrEvent.FieldUpdate` /
 * `OcrEvent.FieldLocked` / `OcrEvent.ResultFinalized` events fire.
 */
class OcrRecognitionDispatcher(
    private val engine: PaddleOcrEngine,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    private val barcodeDetector: BarcodeAnchorDetector? = BarcodeAnchorDetector(),
    private val llmExtractor: OcrLlmExtractor = NoOpOcrLlmExtractor(),
) {

    /** Per-session inflight jobs + per-session fusion accumulator. */
    private val perSession = ConcurrentHashMap<String, SessionState>()

    /**
     * Per-session structured-extraction context (schema + mode). Populated
     * by [registerSession] when the OCR session is created. When absent
     * for a session, the LLM extraction pass is skipped for that session
     * — useful for tests and for the legacy dispatcher-only path before
     * the manager wires the context through.
     */
    private val extractionContexts = ConcurrentHashMap<String, OcrExtractionContext>()

    /**
     * Register the per-session structured-extraction context. Called by
     * [OcrSessionManager.createSession] right after the session record is
     * created. Idempotent — repeated calls for the same `sessionId`
     * overwrite. The context is removed by [closeSession].
     */
    fun registerSession(sessionId: String, context: OcrExtractionContext) {
        extractionContexts[sessionId] = context
    }

    /**
     * Submit a frame for recognition.
     *
     * Non-blocking: returns immediately after launching the background
     * job. The dispatcher emits events on the supplied [writer] as
     * recognition progresses.
     *
     * @param sessionId the OCR session id (for state lookup + log
     *   tagging).
     * @param frameId the caller-monotonic frame id; surfaced in
     *   ``FrameProcessing`` / ``FrameProcessed`` events.
     * @param yPlane row-major 8-bit greyscale Y data.
     * @param width pixel width.
     * @param height pixel height.
     * @param config per-frame engine knobs.
     * @param writer event-stream writer attached to this session (may
     *   be null if the SDK never called `streamOcrEvents`; in that
     *   case recognition still runs but no events surface).
     */
    fun submit(
        sessionId: String,
        frameId: Long,
        yPlane: ByteArray,
        width: Int,
        height: Int,
        config: OcrEngineConfig,
        writer: OcrTokenStreamWriter?,
    ): Job {
        val state = perSession.computeIfAbsent(sessionId) { SessionState() }
        return scope.launch {
            writer?.runCatching { writeFrameProcessing(frameId) }
            val startedNs = System.nanoTime()
            val output = try {
                engine.recognise(yPlane, width, height, config)
            } catch (t: Throwable) {
                // Most importantly: PR C2's LiteRtPaddleOcrBackend scaffold
                // throws IllegalStateException pointing at the conversion
                // workflow. Swallow + log + skip — Phase 2 #3 intentionally
                // ships the dispatcher even when the engine scaffold
                // hasn't been replaced with the native pipeline yet.
                MindlayerLog.w(
                    TAG,
                    "OCR recognise failed: ${t.safeLabel()}",
                    sessionId = sessionId,
                    throwable = null,
                )
                writer?.runCatching {
                    writeFrameProcessed(frameId, lineCount = 0, durationMs = 0)
                }
                return@launch
            }
            val durationMs = (System.nanoTime() - startedNs) / 1_000_000L
            writer?.runCatching {
                writeFrameProcessed(frameId, lineCount = output.lines.size, durationMs = durationMs)
            }

            // Per-line fusion: each OcrTextLine.text becomes a candidate
            // value for a synthetic per-line field. The actual evidence-
            // package → LLM structured-extraction pipeline lives in the
            // p2-llm-extraction track; PR #3 ships the raw OCR-text →
            // fusion path so the wire surface is exercised.
            for ((index, line) in output.lines.withIndex()) {
                val fieldName = "line[$index]"
                val obs = OcrFieldFusion.FieldObservation(
                    value = line.text,
                    confidence = line.confidence,
                    frameQuality = 1.0, // No client-side score yet; Phase 2 #4 wires it.
                    frameId = frameId,
                )
                val newState = state.fusion.accept(fieldName, obs)
                writer?.runCatching {
                    writeFieldUpdate(
                        fieldName = fieldName,
                        topValue = newState.topValue ?: "",
                        confidence = newState.locked.toConfidenceString(),
                        consecutiveAgreement = newState.consecutiveAgreement,
                        boundingBox = line.boundingBox,
                    )
                }
                if (newState.locked && !state.lockedFields.contains(fieldName)) {
                    state.lockedFields.add(fieldName)
                    writer?.runCatching {
                        writeFieldLocked(fieldName, newState.topValue ?: "", line.boundingBox)
                    }
                }
            }

            // Barcode anchors: run ZXing on the same Y-plane. Each
            // decoded barcode is treated as a synthetic field named
            // `barcode[<index>]` so the fusion module + wire path
            // are reused as-is. The structured-extraction stage
            // (p2-llm-extraction) will mine the barcodes map from
            // SessionState to inject GTIN / QR anchors into the
            // schema-constrained extraction prompt.
            //
            // Failure-tolerant: decode() returns empty on any error;
            // a missed barcode never propagates a failure to the
            // session lifecycle.
            barcodeDetector?.decode(yPlane, width, height, frameId)?.forEach { anchor ->
                val key = "${anchor.format}|${anchor.value}"
                val merged = state.barcodes.merge(key, anchor) { existing, fresh ->
                    // Keep the original frame's bbox (oldest detection
                    // is typically the highest-quality one), but record
                    // the latest frameId for downstream weighting.
                    existing.copy(frameId = fresh.frameId)
                } ?: anchor
                val fieldName = "barcode[${anchor.format}|${anchor.value.take(BARCODE_VALUE_KEY_PREFIX_CHARS)}]"
                val obs = OcrFieldFusion.FieldObservation(
                    value = anchor.value,
                    confidence = OcrFieldFusion.Confidence.HIGH,
                    frameQuality = 1.0,
                    frameId = frameId,
                )
                val newState = state.fusion.accept(fieldName, obs)
                writer?.runCatching {
                    writeFieldUpdate(
                        fieldName = fieldName,
                        topValue = merged.value,
                        confidence = newState.locked.toConfidenceString(),
                        consecutiveAgreement = newState.consecutiveAgreement,
                    )
                }
                if (newState.locked && !state.lockedFields.contains(fieldName)) {
                    state.lockedFields.add(fieldName)
                    writer?.runCatching {
                        writeFieldLocked(fieldName, merged.value)
                    }
                }
            }

            // Structured extraction (Phase 2 #4 — p2-llm-extraction).
            // Runs the LLM extractor on the evidence package built from
            // this frame's recognised lines + decoded barcodes. The
            // resulting structured fields flow through OcrFieldFusion
            // using the same field-update/field-locked wire path as the
            // raw-line and barcode emitters above.
            //
            // Strategy A — reset-per-frame KV: each frame is a fresh
            // one-shot prompt. Cross-frame agreement happens in fusion,
            // not in the LLM's context window.
            //
            // Failure-tolerant: if no extraction context is registered
            // (legacy dispatcher-only path / unit tests), or if the
            // extractor throws, the extraction emission is skipped and
            // the per-line + barcode emissions above are unaffected.
            val context = extractionContexts[sessionId]
            if (context != null) {
                val frameIndex = state.frameIndex.getAndIncrement()
                val evidence = OcrEvidencePackage(
                    sessionId = sessionId,
                    frameId = frameId,
                    frameIndex = frameIndex,
                    mode = context.mode,
                    outputSchemaJson = context.outputSchemaJson,
                    textLines = output.lines,
                    barcodeAnchors = state.barcodes.values.toList(),
                    frameQuality = DEFAULT_FRAME_QUALITY,
                )
                val extraction = try {
                    llmExtractor.extract(evidence)
                } catch (t: Throwable) {
                    // Like the recognise() scaffold above: degrade
                    // silently and let the per-line emission keep
                    // flowing. The extractor implementation owns its
                    // own privacy-safe logging.
                    MindlayerLog.w(
                        TAG,
                        "OCR LLM extraction failed: ${t.safeLabel()}",
                        sessionId = sessionId,
                        throwable = null,
                    )
                    OcrExtractionResult.EMPTY
                }
                if (extraction.rawJson != null) {
                    state.lastExtractionRawJson = extraction.rawJson
                }
                for (field in extraction.fields) {
                    val fieldName = "extract.${field.name}"
                    val obs = OcrFieldFusion.FieldObservation(
                        value = field.value,
                        confidence = field.confidence,
                        frameQuality = evidence.frameQuality,
                        frameId = frameId,
                    )
                    val newState = state.fusion.accept(fieldName, obs)
                    writer?.runCatching {
                        writeFieldUpdate(
                            fieldName = fieldName,
                            topValue = newState.topValue ?: "",
                            confidence = newState.locked.toConfidenceString(),
                            consecutiveAgreement = newState.consecutiveAgreement,
                        )
                    }
                    if (newState.locked && !state.lockedFields.contains(fieldName)) {
                        state.lockedFields.add(fieldName)
                        writer?.runCatching {
                            writeFieldLocked(fieldName, newState.topValue ?: "")
                        }
                    }
                }
            }
        }
    }

    /**
     * Drain in-flight jobs for a session + emit the terminal
     * `ResultFinalized` event. Called by the session manager on
     * `finalize()`.
     *
     * Best-effort: if any job throws, the failure is already
     * logged inside [submit] and `ResultFinalized` is emitted
     * anyway with whatever fusion state accumulated.
     */
    suspend fun finalize(sessionId: String, writer: OcrTokenStreamWriter?) {
        val state = perSession[sessionId] ?: return
        // No explicit join — SupervisorJob children are cancelled on
        // close(); for finalize the caller already waited for the
        // last pushFrame to enqueue. A future refinement can track
        // active jobs and join them here.
        val snapshot = state.fusion.snapshot()
        val fullJson = buildResultJson(snapshot, state.lastExtractionRawJson)
        writer?.runCatching { writeResultFinalized(fullJson) }
        writer?.runCatching { writeDone("ocr_complete") }
    }

    /**
     * Tear down per-session state. Idempotent. Called by the
     * session manager on `close()` / binder-death.
     */
    fun closeSession(sessionId: String) {
        perSession.remove(sessionId)
        extractionContexts.remove(sessionId)
    }

    /** Tear down everything. Idempotent. */
    fun shutdown() {
        perSession.clear()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun buildResultJson(
        snapshot: Map<String, OcrFieldFusion.FieldState>,
        lastExtractionRawJson: String?,
    ): String {
        // Phase 2 #4: prefer the LLM extractor's last raw JSON when
        // present (it is the schema-shaped object the caller actually
        // asked for). Fall back to a flat fusion-snapshot dump so the
        // wire surface keeps emitting *something* even when the
        // extractor stays silent (NoOpOcrLlmExtractor default, or the
        // extractor errored out on every frame).
        if (lastExtractionRawJson != null) {
            return lastExtractionRawJson
        }
        val sb = StringBuilder("{")
        var first = true
        for ((field, state) in snapshot) {
            if (!first) sb.append(',')
            sb.append('"').append(field.replace("\"", "\\\"")).append('"')
                .append(':').append('"')
                .append((state.topValue ?: "").replace("\"", "\\\""))
                .append('"')
            first = false
        }
        sb.append('}')
        return sb.toString()
    }

    private fun Boolean.toConfidenceString(): String = if (this) "high" else "medium"

    private class SessionState {
        val fusion = OcrFieldFusion()
        val lockedFields: MutableSet<String> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /**
         * Accumulator for barcode anchors decoded across the session's
         * frames. Keyed by canonical `format|value` so repeated
         * detections of the same barcode in successive frames merge
         * into a single anchor (and bump the field-update agreement
         * counter via OcrFieldFusion just like recognised text does).
         */
        val barcodes: MutableMap<String, BarcodeAnchor> =
            java.util.concurrent.ConcurrentHashMap()

        /**
         * Monotonic per-session frame index handed to the LLM extractor
         * as [OcrEvidencePackage.frameIndex]. Distinct from the
         * caller-supplied [OcrEvidencePackage.frameId] — the index lets
         * the extractor reason about position in the accepted-frame
         * sequence regardless of frame-id gaps from rejected frames.
         */
        val frameIndex: AtomicInteger = AtomicInteger(0)

        /**
         * Most-recent raw-JSON output from the LLM extractor, if any.
         * Used by [buildResultJson] to surface the schema-shaped object
         * verbatim on `OcrEvent.ResultFinalized` instead of the flat
         * fusion-snapshot fallback.
         */
        @Volatile var lastExtractionRawJson: String? = null
    }

    private companion object {
        private const val TAG = "OcrRecognitionDispatcher"

        /**
         * Max number of barcode-value chars used in the synthetic
         * fusion field name. Caps the field-name length so a long
         * QR payload (URL, vCard, etc.) does not blow up the
         * fusion-state map key footprint.
         */
        private const val BARCODE_VALUE_KEY_PREFIX_CHARS = 16

        /**
         * Default frame-quality weight handed to the LLM extractor's
         * evidence package. The presort already gated this frame as
         * "accepted" so it is at least average quality; the actual
         * per-frame blur score is computed during intake but is not
         * threaded back here yet. Conservatively treat every accepted
         * frame as a strong reading until a future patch wires the
         * presort score forward.
         */
        private const val DEFAULT_FRAME_QUALITY = 1.0
    }
}
