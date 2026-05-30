package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

interface ForegroundTracker {
    fun enterForeground()
    fun exitForeground()
}

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
 * # Engine failure tolerance
 *
 * If `engine.recognise()` throws, the dispatcher logs the failure with
 * `safeLabel`, emits `FrameProcessed(lineCount=0)`, and DOES NOT poison the
 * session. The session manager's intake path keeps accepting frames; only
 * recognition results for the failed frame are missing.
 */
class OcrRecognitionDispatcher(
    private val engine: PaddleOcrEngine,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + ioDispatcher),
    private val barcodeDetector: BarcodeAnchorDetector? = BarcodeAnchorDetector(),
    private val llmExtractor: OcrLlmExtractor = NoOpOcrLlmExtractor(),
    private val foregroundTracker: ForegroundTracker? = null,
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
     * Per-session v0.9 multi-page realtime configuration. Absent (or
     * [PageBoundariesConfig.DISABLED]) means the dispatcher takes the
     * v0.8 single-document path verbatim. Populated via
     * [attachPageBoundariesConfig] right after [registerSession] when
     * the session manager parses a non-empty `pageBoundaries` block out
     * of `OcrSessionConfig.optionsJson`.
     */
    private val pageConfigs = ConcurrentHashMap<String, PageBoundariesConfig>()

    /**
     * Register the per-session structured-extraction context. Called by
     * [OcrSessionManager.createSession] right after the session record is
     * created. Idempotent — repeated calls for the same `sessionId`
     * overwrite. The session state is created here so finalizing a
     * zero-frame session still emits a terminal result instead of hanging
     * the stream reader. The context is removed by [closeSession].
     */
    fun registerSession(sessionId: String, context: OcrExtractionContext) {
        perSession.computeIfAbsent(sessionId) { SessionState() }
        extractionContexts[sessionId] = context
    }

    /**
     * Attach v0.9 page-boundary configuration for a session. Must be
     * called AFTER [registerSession]. Idempotent — repeated calls
     * overwrite. Passing [PageBoundariesConfig.DISABLED] (or never
     * calling this method) keeps the v0.8 behaviour: a single session-
     * scoped `OCR_RESULT_FINALIZED` event and no per-page events.
     */
    fun attachPageBoundariesConfig(sessionId: String, config: PageBoundariesConfig) {
        pageConfigs[sessionId] = config
    }

    /**
     * Read the effective page-boundary config for a session. Returns
     * [PageBoundariesConfig.DISABLED] when no config was attached. The
     * session manager uses this to decide whether to route a frame
     * through [submit] (legacy) or [submitWithMeta] (v0.9) — the
     * IMU sub-block in `OcrFrameMeta.extraJson` only matters in the
     * latter case.
     */
    fun pageBoundariesConfig(sessionId: String): PageBoundariesConfig =
        pageConfigs[sessionId] ?: PageBoundariesConfig.DISABLED

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
        writerMutex: Mutex? = null,
    ): Job = submitWithMeta(
        sessionId = sessionId,
        frameId = frameId,
        yPlane = yPlane,
        width = width,
        height = height,
        config = config,
        writer = writer,
        writerMutex = writerMutex,
        extraJson = null,
    )

    /**
     * v0.9-aware overload — same contract as [submit] but also forwards
     * the frame's [com.adsamcik.mindlayer.OcrFrameMeta.extraJson] string
     * so the page-boundary detector can read the IMU sub-block. Legacy
     * callers using [submit] pass `extraJson = null` and continue to get
     * the v0.8 single-document path.
     */
    fun submitWithMeta(
        sessionId: String,
        frameId: Long,
        yPlane: ByteArray,
        width: Int,
        height: Int,
        config: OcrEngineConfig,
        writer: OcrTokenStreamWriter?,
        writerMutex: Mutex? = null,
        extraJson: String? = null,
    ): Job {
        val state = perSession.computeIfAbsent(sessionId) { SessionState() }
        val pageConfig = pageConfigs[sessionId] ?: PageBoundariesConfig.DISABLED
        val job = scope.launch(start = CoroutineStart.LAZY) {
            foregroundTracker?.enterForeground()
            try {
                withWriterLock(writerMutex) {
                    writer?.runCatching { writeFrameProcessing(frameId) }
                }
                val startedNs = System.nanoTime()
                val output = try {
                    engine.recognise(yPlane, width, height, config)
                } catch (t: Throwable) {
                    if (t is CancellationException) return@launch
                    MindlayerLog.w(
                        TAG,
                        "OCR recognise failed: ${t.safeLabel()}, frameId=$frameId",
                        sessionId = sessionId,
                        throwable = null,
                    )
                    withWriterLock(writerMutex) {
                        writer?.runCatching {
                            writeFrameProcessed(frameId, lineCount = 0, durationMs = 0)
                        }
                    }
                    return@launch
                }
                val durationMs = (System.nanoTime() - startedNs) / 1_000_000L
                withWriterLock(writerMutex) {
                    writer?.runCatching {
                        writeFrameProcessed(frameId, lineCount = output.lines.size, durationMs = durationMs)
                    }

                    emitPerLineFusion(state, output.lines, frameId, writer)
                    emitBarcodeAnchors(state, yPlane, width, height, frameId, writer)

                    if (pageConfig.enabled) {
                        // Page-aware path: defer LLM extraction to finalize and
                        // run page-boundary detection here.
                        handlePageFrame(
                            sessionId = sessionId,
                            state = state,
                            pageConfig = pageConfig,
                            frameId = frameId,
                            lines = output.lines,
                            extraJson = extraJson,
                            writer = writer,
                        )
                    } else {
                        // Legacy v0.8 path: per-frame LLM extraction (when a
                        // context is attached) feeds OcrFieldFusion + the
                        // session-end OCR_RESULT_FINALIZED.fullJson.
                        emitPerFrameLlmExtraction(sessionId, state, output.lines, frameId, writer)
                    }
                }
            } finally {
                foregroundTracker?.exitForeground()
            }
        }
        state.activeJobs.add(job)
        job.invokeOnCompletion { state.activeJobs.remove(job) }
        job.start()
        return job
    }

    // ── per-frame emitters ──────────────────────────────────────────────

    /**
     * Per-line fusion: each [OcrTextLine].text becomes a candidate value
     * for a synthetic per-line field. Fires `OCR_FIELD_UPDATE` /
     * `OCR_FIELD_LOCKED` events as agreement crosses the lock threshold.
     */
    private fun emitPerLineFusion(
        state: SessionState,
        lines: List<OcrTextLine>,
        frameId: Long,
        writer: OcrTokenStreamWriter?,
    ) {
        for ((index, line) in lines.withIndex()) {
            val fieldName = "line[$index]"
            val obs = OcrFieldFusion.FieldObservation(
                value = line.text,
                confidence = line.confidence,
                frameQuality = 1.0,
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

    private fun emitBarcodeAnchors(
        state: SessionState,
        yPlane: ByteArray,
        width: Int,
        height: Int,
        frameId: Long,
        writer: OcrTokenStreamWriter?,
    ) {
        barcodeDetector?.decode(yPlane, width, height, frameId)?.forEach { anchor ->
            val key = "${anchor.format}|${anchor.value}"
            val merged = state.barcodes.merge(key, anchor) { existing, fresh ->
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
    }

    private suspend fun emitPerFrameLlmExtraction(
        sessionId: String,
        state: SessionState,
        lines: List<OcrTextLine>,
        frameId: Long,
        writer: OcrTokenStreamWriter?,
    ) {
        val context = extractionContexts[sessionId] ?: return
        val frameIndex = state.frameIndex.getAndIncrement()
        val evidence = OcrEvidencePackage(
            sessionId = sessionId,
            frameId = frameId,
            frameIndex = frameIndex,
            mode = context.mode,
            outputSchemaJson = context.outputSchemaJson,
            textLines = lines,
            barcodeAnchors = state.barcodes.values.toList(),
            frameQuality = DEFAULT_FRAME_QUALITY,
        )
        val extraction = try {
            llmExtractor.extract(evidence)
        } catch (t: Throwable) {
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

    // ── v0.9 page-boundary path ─────────────────────────────────────────

    /**
     * Run the page-boundary detector against the in-flight page state.
     * On boundary fire: close the current page (eagerly emit
     * `OCR_PAGE_FINALIZED` with `fullJson=null` unless `llmExtractPerPage`
     * is on, in which case defer); open a new page; emit
     * `OCR_PAGE_STARTED`. Always extends the active page with the new
     * frame's lines.
     *
     * Must be called from within the writer mutex.
     */
    private fun handlePageFrame(
        sessionId: String,
        state: SessionState,
        pageConfig: PageBoundariesConfig,
        frameId: Long,
        lines: List<OcrTextLine>,
        extraJson: String?,
        writer: OcrTokenStreamWriter?,
    ) {
        if (state.pageDetector == null) {
            state.pageDetector = PageBoundaryDetector(pageConfig)
        }
        val detector = state.pageDetector!!
        val imu = ImuFrameMetadata.parse(extraJson)

        val current = state.currentPage
        if (current == null) {
            // First frame of the session: open page 0 implicitly.
            val firstPage = PageAccumulator(pageIndex = 0)
            state.currentPage = firstPage
            state.nextPageIndex = 1
            writer?.runCatching { writePageStarted(0, triggerFrameId = 0L) }
            firstPage.extend(lines, frameId)
            return
        }

        val isBoundary = detector.isBoundary(current, lines, imu)
        if (isBoundary) {
            // Close the current page.
            state.closedPages.add(current)
            if (!pageConfig.llmExtractPerPage) {
                emitPageFinalizedEager(current, writer)
            }
            MindlayerLog.i(
                TAG,
                "OCR page ${current.pageIndex} closed: " +
                    "${current.lineCount()} lines, ${current.framesContributed} frames",
                sessionId = sessionId,
            )

            // Open the new page seeded with any pending diff frames
            // (the stability streak that led up to this boundary) plus
            // this boundary-triggering frame.
            val newIndex = state.nextPageIndex
            state.nextPageIndex = newIndex + 1
            val newPage = PageAccumulator(pageIndex = newIndex)
            state.currentPage = newPage
            writer?.runCatching {
                writePageStarted(newIndex, triggerFrameId = frameId)
            }
            for ((pendLines, pendFrameId) in state.pendingDiffFrames) {
                newPage.extend(pendLines, pendFrameId)
            }
            state.pendingDiffFrames.clear()
            newPage.extend(lines, frameId)
        } else if (detector.consecutiveDifferentFrames > 0) {
            // Diff-streak in progress but not yet at stabilityFrames.
            // Buffer this frame so it doesn't pollute the prev token
            // set; the next jaccard comparison stays against the stable
            // pre-streak page content.
            state.pendingDiffFrames.add(lines to frameId)
        } else {
            // Same-content frame. If a transient streak was building,
            // it was just noise — flush the pending frames into the
            // current page so no lines are lost.
            if (state.pendingDiffFrames.isNotEmpty()) {
                for ((pendLines, pendFrameId) in state.pendingDiffFrames) {
                    current.extend(pendLines, pendFrameId)
                }
                state.pendingDiffFrames.clear()
            }
            current.extend(lines, frameId)
        }
    }

    private fun emitPageFinalizedEager(page: PageAccumulator, writer: OcrTokenStreamWriter?) {
        writer?.runCatching {
            writePageFinalized(
                pageIndex = page.pageIndex,
                lines = page.bestLines().toWireLines(),
                fullJson = null,
                framesContributed = page.framesContributed,
            )
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
     *
     * When v0.9 page-boundary detection is enabled for the session
     * (via [attachPageBoundariesConfig]), this also emits any
     * outstanding `OCR_PAGE_FINALIZED` events (deferred or final-page)
     * before the terminal result.
     */
    suspend fun finalize(sessionId: String, writer: OcrTokenStreamWriter?) {
        val state = perSession[sessionId] ?: return
        state.activeJobs.toList().forEach { it.join() }
        val pageConfig = pageConfigs[sessionId] ?: PageBoundariesConfig.DISABLED
        if (pageConfig.enabled) {
            finalizePageAware(sessionId, state, pageConfig, writer)
        } else {
            val snapshot = state.fusion.snapshot()
            val fullJson = buildResultJson(snapshot, state.lastExtractionRawJson)
            writer?.runCatching { writeResultFinalized(fullJson) }
            writer?.runCatching { writeDone("ocr_complete") }
        }
    }

    private suspend fun finalizePageAware(
        sessionId: String,
        state: SessionState,
        pageConfig: PageBoundariesConfig,
        writer: OcrTokenStreamWriter?,
    ) {
        // Flush any pending diff-streak frames into the still-open
        // current page so finalize doesn't lose them. The streak never
        // completed → treat them as part of the last page.
        val openPage = state.currentPage
        if (openPage != null && state.pendingDiffFrames.isNotEmpty()) {
            for ((pendLines, pendFrameId) in state.pendingDiffFrames) {
                openPage.extend(pendLines, pendFrameId)
            }
            state.pendingDiffFrames.clear()
        }

        val allPages = buildList {
            addAll(state.closedPages)
            state.currentPage?.let { add(it) }
        }
        val context = extractionContexts[sessionId]

        // Per-page LLM extraction (when enabled). Build a map from pageIndex
        // to the raw JSON the extractor produced. Pages whose extraction
        // failed or whose context was missing are absent from the map and
        // their PAGE_FINALIZED event ships with fullJson=null.
        val perPageJson = mutableMapOf<Int, JsonElement>()
        if (pageConfig.llmExtractPerPage && context != null) {
            for (page in allPages) {
                val evidence = OcrEvidencePackage(
                    sessionId = sessionId,
                    frameId = page.triggerFrameId,
                    frameIndex = page.pageIndex,
                    mode = context.mode,
                    outputSchemaJson = context.outputSchemaJson,
                    textLines = page.bestLines(),
                    barcodeAnchors = state.barcodes.values.toList(),
                    frameQuality = DEFAULT_FRAME_QUALITY,
                )
                val raw = try {
                    llmExtractor.extract(evidence).rawJson
                } catch (t: Throwable) {
                    MindlayerLog.w(
                        TAG,
                        "OCR per-page LLM extraction failed: ${t.safeLabel()}, page=${page.pageIndex}",
                        sessionId = sessionId,
                        throwable = null,
                    )
                    null
                }
                if (raw != null) {
                    parseJsonOrNull(raw)?.let { perPageJson[page.pageIndex] = it }
                }
            }
        }

        // Emit OCR_PAGE_FINALIZED events.
        //   - llmExtractPerPage=true: emit for ALL pages here (deferred so
        //     each page's only PAGE_FINALIZED event carries fullJson).
        //   - llmExtractPerPage=false: closed pages were already emitted
        //     eagerly in handlePageFrame; emit only the still-open one.
        if (pageConfig.llmExtractPerPage) {
            for (page in allPages) {
                writer?.runCatching {
                    writePageFinalized(
                        pageIndex = page.pageIndex,
                        lines = page.bestLines().toWireLines(),
                        fullJson = perPageJson[page.pageIndex],
                        framesContributed = page.framesContributed,
                    )
                }
            }
        } else {
            state.currentPage?.let { emitPageFinalizedEager(it, writer) }
        }

        // Build OCR_RESULT_FINALIZED.fullJson:
        //   - llmExtractFinal=true:  run the LLM once on the joined page
        //     text with `\n\n--- page N ---\n\n` separators between pages.
        //     Use the extractor's raw JSON. Fall back to the rollup shape
        //     if the extractor returned no rawJson or no context exists.
        //   - llmExtractFinal=false: emit the rollup shape verbatim.
        val resultFullJson: String = if (pageConfig.llmExtractFinal && context != null) {
            val aggregateLines = buildAggregateTextLines(allPages)
            val evidence = OcrEvidencePackage(
                sessionId = sessionId,
                frameId = 0L,
                frameIndex = 0,
                mode = context.mode,
                outputSchemaJson = context.outputSchemaJson,
                textLines = aggregateLines,
                barcodeAnchors = state.barcodes.values.toList(),
                frameQuality = DEFAULT_FRAME_QUALITY,
            )
            val raw = try {
                llmExtractor.extract(evidence).rawJson
            } catch (t: Throwable) {
                MindlayerLog.w(
                    TAG,
                    "OCR aggregate LLM extraction failed: ${t.safeLabel()}",
                    sessionId = sessionId,
                    throwable = null,
                )
                null
            }
            raw ?: buildRollupJson(allPages)
        } else {
            buildRollupJson(allPages)
        }

        MindlayerLog.i(
            TAG,
            "OCR session finalized: ${allPages.size} pages, " +
                "${allPages.sumOf { it.lineCount() }} total lines, " +
                "${allPages.sumOf { it.framesContributed }} total frames",
            sessionId = sessionId,
        )

        writer?.runCatching { writeResultFinalized(resultFullJson) }
        writer?.runCatching { writeDone("ocr_complete") }
    }

    /**
     * Build the textLines argument for the aggregate LLM extraction:
     * each page's [PageAccumulator.bestLines] in order, with a synthetic
     * separator line `--- page N ---` between pages (matching the
     * `\n\n--- page N ---\n\n` text-join contract from the spec).
     */
    private fun buildAggregateTextLines(pages: List<PageAccumulator>): List<OcrTextLine> {
        if (pages.isEmpty()) return emptyList()
        val out = mutableListOf<OcrTextLine>()
        for ((idx, page) in pages.withIndex()) {
            if (idx > 0) {
                out += OcrTextLine(
                    text = "--- page ${page.pageIndex} ---",
                    confidence = OcrFieldFusion.Confidence.HIGH,
                )
            }
            out += page.bestLines()
        }
        return out
    }

    /**
     * Rollup JSON used as `OCR_RESULT_FINALIZED.fullJson` when no LLM
     * aggregate extraction runs. Shape: `{"pages":[{"index":N,
     * "lineCount":N, "framesContributed":N}, …]}`.
     */
    private fun buildRollupJson(pages: List<PageAccumulator>): String {
        val obj = buildJsonObject {
            putJsonArray("pages") {
                for (page in pages) {
                    add(
                        buildJsonObject {
                            put("index", page.pageIndex)
                            put("lineCount", page.lineCount())
                            put("framesContributed", page.framesContributed)
                        },
                    )
                }
            }
        }
        return obj.toString()
    }

    /**
     * Lenient parse: returns the JsonElement on success, null on any
     * failure (the extractor handed us non-JSON or partial JSON; the
     * wire surface uses the rollup fallback in that case).
     */
    private fun parseJsonOrNull(raw: String): JsonElement? = try {
        LENIENT_JSON.parseToJsonElement(raw)
    } catch (t: Throwable) {
        null
    }

    /**
     * Schedule terminal result emission without blocking the binder
     * thread that requested finalization. The stream reader observes
     * the result once in-flight frame jobs drain.
     */
    fun finalizeAsync(sessionId: String, writer: OcrTokenStreamWriter?): Job =
        scope.launch {
            finalize(sessionId, writer)
        }

    /**
     * Tear down per-session state. Idempotent. Called by the
     * session manager on `close()` / binder-death.
     */
    fun closeSession(sessionId: String) {
        perSession.remove(sessionId)?.activeJobs?.forEach { it.cancel() }
        extractionContexts.remove(sessionId)
        pageConfigs.remove(sessionId)
    }

    suspend fun drainForMemoryPressure() {
        scope.coroutineContext[Job]?.children?.toList()?.forEach { child ->
            child.cancelAndJoin()
        }
        perSession.clear()
    }

    fun cancelAllForMemoryPressure() {
        scope.coroutineContext[Job]?.children?.toList()?.forEach { child ->
            child.cancel()
        }
        perSession.clear()
        extractionContexts.clear()
        pageConfigs.clear()
    }

    /** Tear down everything. Idempotent. */
    fun shutdown() {
        perSession.clear()
        scope.coroutineContext[Job]?.cancel()
    }

    private suspend fun withWriterLock(mutex: Mutex?, block: suspend () -> Unit) {
        if (mutex != null) mutex.withLock { block() } else block()
    }

    private fun buildResultJson(
        snapshot: Map<String, OcrFieldFusion.FieldState>,
        lastExtractionRawJson: String?,
    ): String {
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

    /**
     * Wire-shape adapter from the engine's [OcrTextLine] to the writer's
     * nested [OcrTokenStreamWriter.OcrPageLine] DTO. Maps the confidence
     * enum to the stringly-typed wire value.
     */
    private fun List<OcrTextLine>.toWireLines(): List<OcrTokenStreamWriter.OcrPageLine> =
        map { line ->
            OcrTokenStreamWriter.OcrPageLine(
                text = line.text,
                confidence = when (line.confidence) {
                    OcrFieldFusion.Confidence.LOW -> "low"
                    OcrFieldFusion.Confidence.MEDIUM -> "medium"
                    OcrFieldFusion.Confidence.HIGH -> "high"
                },
                boundingBox = line.boundingBox,
            )
        }

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
        val activeJobs: MutableSet<Job> = java.util.concurrent.ConcurrentHashMap.newKeySet()

        /**
         * Most-recent raw-JSON output from the LLM extractor, if any.
         * Used by [buildResultJson] to surface the schema-shaped object
         * verbatim on `OcrEvent.ResultFinalized` instead of the flat
         * fusion-snapshot fallback.
         */
        @Volatile var lastExtractionRawJson: String? = null

        // ── v0.9 multi-page realtime state ──────────────────────────────
        //
        // All four fields stay null/empty under the legacy v0.8 path;
        // they're initialised lazily on the first frame when
        // `pageConfigs[sessionId].enabled == true`.

        /** Page-boundary detector, lazily instantiated on first frame. */
        var pageDetector: PageBoundaryDetector? = null

        /** The currently open page (still accepting frames). */
        var currentPage: PageAccumulator? = null

        /** Pages that have been closed off by a boundary fire. */
        val closedPages: MutableList<PageAccumulator> = mutableListOf()

        /** Next pageIndex to assign to a fresh PageAccumulator. */
        var nextPageIndex: Int = 0

        /**
         * Frames flagged "different" by the detector but not yet enough
         * to fire a boundary. Held out of [currentPage] so prev's token
         * set stays clean for the next jaccard comparison. On boundary
         * fire these seed the new page; on streak break they flush into
         * [currentPage]; on session finalize they flush into [currentPage].
         */
        val pendingDiffFrames: MutableList<Pair<List<OcrTextLine>, Long>> = mutableListOf()
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

        /**
         * Lenient JSON parser used to re-wrap extractor `rawJson`
         * strings into [JsonElement] for the page-finalized payload.
         */
        private val LENIENT_JSON = Json { isLenient = true; ignoreUnknownKeys = true }
    }
}
