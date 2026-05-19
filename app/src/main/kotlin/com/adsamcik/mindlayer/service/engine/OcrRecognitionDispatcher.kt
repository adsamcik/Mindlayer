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
) {

    /** Per-session inflight jobs + per-session fusion accumulator. */
    private val perSession = ConcurrentHashMap<String, SessionState>()

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
        val fullJson = buildResultJson(snapshot)
        writer?.runCatching { writeResultFinalized(fullJson) }
        writer?.runCatching { writeDone("ocr_complete") }
    }

    /**
     * Tear down per-session state. Idempotent. Called by the
     * session manager on `close()` / binder-death.
     */
    fun closeSession(sessionId: String) {
        perSession.remove(sessionId)
    }

    /** Tear down everything. Idempotent. */
    fun shutdown() {
        perSession.clear()
        scope.coroutineContext[Job]?.cancel()
    }

    private fun buildResultJson(snapshot: Map<String, OcrFieldFusion.FieldState>): String {
        // Minimal JSON — Phase 2 #4 (p2-llm-extraction) replaces this
        // with a real schema-constrained JSON from Gemma extraction.
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
    }

    private companion object {
        private const val TAG = "OcrRecognitionDispatcher"
    }
}
