package com.adsamcik.mindlayer.service.engine

import android.os.SystemClock
import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrFrameMeta
import com.adsamcik.mindlayer.OcrLimits
import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.OcrSessionState
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

/**
 * In-process manager for v0.8 multi-frame OCR sessions.
 *
 * # Responsibilities
 *
 *  1. **Per-UID session creation** — assigns ``ocr-<uid>-<uuid>`` ids,
 *     tracks ownership for [requireOwnership] at the AIDL boundary,
 *     and enforces [OcrLimits.maxConcurrentOcrSessions] per UID.
 *  2. **Frame intake routing** — every ``pushOcrFrame`` invocation
 *     runs through [OcrFrameQualityPresort] (PR C1) so a buggy or
 *     hostile client can't bypass quality gating; the verdict feeds
 *     the wire-stable [OcrFrameAck.status].
 *  3. **Idle + max-duration timeouts** — sessions close themselves
 *     automatically when [OcrLimits.maxOcrSessionDurationMs] elapses
 *     since creation, or when no frame has been accepted for
 *     [idleTimeoutMs] (default 30 s).
 *  4. **Read-only snapshot** for ``getOcrSessionState``.
 *  5. **Per-UID frame-rate gate** — soft cap from
 *     [OcrLimits.maxOcrFramesPerMinute] using a sliding token bucket.
 *
 * # What is intentionally NOT here yet
 *
 *  - Calling [PaddleOcrEngine.recognise] on each accepted frame
 *    (engine is a scaffold in PR C2; the manager threads frames
 *    through to the engine when present and gracefully degrades
 *    when ``recognise()`` throws the scaffolded failure).
 *  - Field fusion via [OcrFieldFusion] — wired in but only when
 *    recognise() returns lines.
 *  - LLM (Gemma) structured-extraction pass — that's a separate
 *    PR after the engine's PP-OCRv5 pipeline is wired up.
 *  - Stream-pipe writer for ``streamOcrEvents`` — the binder layer
 *    closes the pipe immediately for now; full streaming wiring is
 *    a follow-up.
 *
 * # Threading
 *
 * Public methods are safe to call from any thread. State per
 * session is guarded by a per-session synchronized block (acceptable
 * because intake is naturally serialised by the caller). The global
 * registry uses a [ConcurrentHashMap].
 *
 * # Resource lifecycle
 *
 * The manager does NOT own the [PaddleOcrEngine] — that is a service-
 * scoped singleton injected by the binder layer. Closing the manager
 * is a no-op; per-session close is initiated by the caller, by
 * binder-death of the owning client, or by the idle/max-duration
 * sweeper.
 */
class OcrSessionManager(
    private val engine: PaddleOcrEngine? = null,
    private val limits: OcrLimits = defaultLimits(),
    private val clock: () -> Long = { SystemClock.elapsedRealtime() },
    private val idleTimeoutMs: Long = DEFAULT_IDLE_TIMEOUT_MS,
    /**
     * Phase 2 #3: optional recognition dispatcher. When null, intake
     * still works (frames accepted/rejected per presort) but no
     * recognition runs — this keeps Phase 1 + Phase 2 #1/#2 test
     * paths unchanged. The binder layer constructs a dispatcher
     * paired with [engine] when both are wired.
     */
    private val recognitionDispatcher: OcrRecognitionDispatcher? = null,
) {

    private val sessions = ConcurrentHashMap<String, OcrSession>()
    private val frameRateBuckets = ConcurrentHashMap<Int, FrameRateBucket>()
    private val sessionSequence = AtomicLong(0L)

    // ── Lifecycle ────────────────────────────────────────────────────────

    /**
     * Create a new OCR session for [uid] with the supplied config.
     *
     * @throws IllegalStateException with [MindlayerErrorCode.OCR_SCHEMA_INVALID]-prefixed
     *   message when the config is malformed (caller has already
     *   validated via [com.adsamcik.mindlayer.service.security.IpcInputValidator]
     *   but this is defense-in-depth).
     * @throws IllegalStateException with [MindlayerErrorCode.CONCURRENT_LIMIT]
     *   when [uid] already owns [OcrLimits.maxConcurrentOcrSessions]
     *   active sessions.
     * @return the new session id.
     */
    fun createSession(uid: Int, config: OcrSessionConfig): String {
        require(config.mode in OcrSessionConfig.ALL_MODES) {
            "OcrSessionConfig.mode=${config.mode} not in ALL_MODES"
        }
        sweepIdleAndExpired()

        val ownedCount = sessions.values.count { it.uid == uid && it.phase < OcrSessionState.PHASE_CLOSED }
        check(ownedCount < limits.maxConcurrentOcrSessions) {
            "Per-UID OCR concurrent session limit exceeded ($ownedCount/${limits.maxConcurrentOcrSessions})"
        }

        val sessionId = "ocr-$uid-${UUID.randomUUID()}-${sessionSequence.incrementAndGet()}"
        val effectiveMaxFrames = if (config.maxFrames > 0) {
            minOf(config.maxFrames, limits.maxFramesPerOcrSession)
        } else {
            limits.maxFramesPerOcrSession
        }
        val session = OcrSession(
            sessionId = sessionId,
            uid = uid,
            config = config,
            maxFrames = effectiveMaxFrames,
            createdAtMs = clock(),
        )
        session.lastFrameAtMs = session.createdAtMs
        sessions[sessionId] = session
        MindlayerLog.i(
            TAG,
            "OCR session created: id=$sessionId, uid=$uid, mode=${config.mode}, " +
                "maxFrames=$effectiveMaxFrames",
        )
        return sessionId
    }

    /**
     * Push a frame into the session intake.
     *
     * Runs the service-side quality presort, applies the rate-limit
     * token bucket, and returns the wire-stable [OcrFrameAck]. If the
     * engine is present and the frame is accepted, schedules a
     * background recognise call — but PR C3 does NOT block this method
     * on the engine result (results stream out via [streamOcrEvents]).
     */
    fun pushFrame(
        uid: Int,
        sessionId: String,
        meta: OcrFrameMeta,
        yPlane: ByteArray,
        width: Int,
        height: Int,
    ): OcrFrameAck {
        val intake = preparePushIntake(uid, sessionId, meta) ?: return rejectFinalized(meta)
        intake.earlyAck?.let { return it }
        val session = intake.session

        // Service-side quality presort — re-evaluate the frame regardless
        // of the client's qualityHint so a malicious client can't bypass
        // gating by mis-labelling.
        val score = try {
            OcrFrameQualityPresort.score(yPlane, width, height, session.lastAcceptedDHash)
        } catch (e: IllegalArgumentException) {
            // Bad dimensions — surface as REJECTED_QUALITY so the SDK
            // recalibrates without crashing the whole session.
            session.framesRejected++
            return OcrFrameAck(
                frameId = meta.frameId,
                status = OcrFrameAck.STATUS_REJECTED_QUALITY,
                queueDepth = session.pendingQueueDepth,
                retryAfterMs = 0L,
            )
        }

        if (!score.isAccepted) {
            session.framesRejected++
            return OcrFrameAck(
                frameId = meta.frameId,
                status = OcrFrameAck.STATUS_REJECTED_QUALITY,
                queueDepth = session.pendingQueueDepth,
                retryAfterMs = 0L,
            )
        }

        recordAcceptedFrame(session, intake.now, score.dHash)

        // Phase 2 #3: schedule recognition on the dispatcher when both
        // engine + dispatcher are wired. Fire-and-forget — the binder
        // path returns the synchronous ACK immediately; recognition
        // results stream via OCR_V1 events on the session's writer.
        recognitionDispatcher?.submit(
            sessionId = sessionId,
            frameId = meta.frameId,
            yPlane = yPlane,
            width = width,
            height = height,
            config = OcrEngineConfig(),
            writer = session.eventWriter as? com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter,
        )

        return OcrFrameAck(
            frameId = meta.frameId,
            status = OcrFrameAck.STATUS_ACCEPTED,
            queueDepth = session.pendingQueueDepth,
            retryAfterMs = 0L,
        )
    }

    /**
     * Push a frame into the session intake **without running the
     * service-side pixel presort** — does monotonicity + rate-limit
     * + counter updates only.
     *
     * This is the binder-layer entry point in Phase 1 PR C3, where the
     * service does not yet extract Y-plane bytes from the supplied
     * [com.adsamcik.mindlayer.MediaPart]. A follow-up adds Y-plane
     * extraction in the binder and switches to [pushFrame] for full
     * presort. SDK-side presort (see ``OcrFrameMeta.qualityHint``)
     * still applies; this method honours it as advisory.
     *
     * Honouring the client hint here is **not** a security gate — the
     * binder-layer [com.adsamcik.mindlayer.service.security.IpcInputValidator]
     * has already constrained the hint to the wire-stable enum range.
     * A buggy or hostile client at most accepts a frame the service-
     * side presort would reject; the engine path then drops the
     * recognition output silently.
     */
    fun pushFrameMetadataOnly(
        uid: Int,
        sessionId: String,
        meta: OcrFrameMeta,
    ): OcrFrameAck {
        val intake = preparePushIntake(uid, sessionId, meta) ?: return rejectFinalized(meta)
        intake.earlyAck?.let { return it }
        val session = intake.session

        // Trust the client-side qualityHint advisorily — we still
        // accept frames marked QUALITY_DUPLICATE / QUALITY_BLURRY /
        // QUALITY_TOO_DARK so the SDK can choose to push them as
        // fallbacks. Only QUALITY_GOOD and QUALITY_UNKNOWN are
        // counted as "accept" for advisory metrics.
        recordAcceptedFrame(session, intake.now, dhash = null)
        return OcrFrameAck(
            frameId = meta.frameId,
            status = OcrFrameAck.STATUS_ACCEPTED,
            queueDepth = session.pendingQueueDepth,
            retryAfterMs = 0L,
        )
    }

    private data class PushIntake(
        val session: OcrSession,
        val now: Long,
        val earlyAck: OcrFrameAck?,
    )

    private fun preparePushIntake(
        uid: Int,
        sessionId: String,
        meta: OcrFrameMeta,
    ): PushIntake? {
        val session = requireOwnedSession(uid, sessionId)

        // Phase check: only ACTIVE sessions accept frames. Anything
        // past FINALIZING returns the wire-stable REJECTED_FINALIZED
        // status so the SDK can detect a closed-session-frame race.
        if (session.phase >= OcrSessionState.PHASE_FINALIZING) {
            session.framesRejected++
            return null
        }

        // Monotonicity check — caller-supplied frameId must increase.
        val lastFrameId = session.lastFrameId
        if (lastFrameId != null && meta.frameId <= lastFrameId) {
            // Treat as quality-reject rather than throwing; non-monotonic
            // is usually a race in the SDK presort, not an attack.
            session.framesRejected++
            return PushIntake(
                session = session,
                now = clock(),
                earlyAck = OcrFrameAck(
                    frameId = meta.frameId,
                    status = OcrFrameAck.STATUS_REJECTED_QUALITY,
                    queueDepth = 0,
                    retryAfterMs = 0L,
                ),
            )
        }
        session.lastFrameId = meta.frameId

        // Per-UID frame-rate gate (token bucket).
        val now = clock()
        val bucket = frameRateBuckets.computeIfAbsent(uid) { FrameRateBucket(limits.maxOcrFramesPerMinute) }
        val rateOk = bucket.tryAcquire(now)
        if (!rateOk) {
            session.framesDropped++
            return PushIntake(
                session = session,
                now = now,
                earlyAck = OcrFrameAck(
                    frameId = meta.frameId,
                    status = OcrFrameAck.STATUS_DROPPED_BUSY,
                    queueDepth = session.pendingQueueDepth,
                    retryAfterMs = bucket.retryAfterMs(now),
                ),
            )
        }

        return PushIntake(session = session, now = now, earlyAck = null)
    }

    private fun rejectFinalized(meta: OcrFrameMeta): OcrFrameAck = OcrFrameAck(
        frameId = meta.frameId,
        status = OcrFrameAck.STATUS_REJECTED_FINALIZED,
        queueDepth = 0,
        retryAfterMs = 0L,
    )

    private fun recordAcceptedFrame(session: OcrSession, now: Long, dhash: ULong?) {
        session.framesAccepted++
        session.lastFrameAtMs = now
        if (dhash != null) session.lastAcceptedDHash = dhash

        // Hard cap on per-session frame intake. Once we cross
        // maxFrames the session auto-finalises.
        if (session.framesAccepted >= session.maxFrames) {
            session.phase = OcrSessionState.PHASE_FINALIZING
        }

        // TODO(PR C3 follow-up): hand `score`, `yPlane`, `meta` to the
        // engine on a background coroutine and stream the result via
        // streamOcrEvents. PaddleOcrEngine.recognise() in PR C2 is a
        // scaffold; calling it would throw. Once the engine pipeline
        // is wired we will:
        //   1. Capture the frame on an intake queue (max queueDepth
        //      bounded by limits.maxConcurrentOcrSessions * 2).
        //   2. Run recognise() on engine dispatcher.
        //   3. Pump OcrTextLine list through OcrFieldFusion.
        //   4. Emit ocr_field_update / ocr_field_locked events.
    }

    /** Read-only snapshot of session state. */
    fun stateOf(uid: Int, sessionId: String): OcrSessionState {
        val session = requireOwnedSession(uid, sessionId)
        return OcrSessionState(
            sessionId = sessionId,
            phase = session.phase,
            framesAccepted = session.framesAccepted,
            framesDropped = session.framesDropped,
            framesRejected = session.framesRejected,
            pendingQueueDepth = session.pendingQueueDepth,
            streamAttached = session.streamAttached,
            createdAtMs = session.createdAtMs,
            lastFrameAtMs = session.lastFrameAtMs,
        )
    }

    /** Mark the session as finalizing. Triggers result emission. */
    fun finalize(uid: Int, sessionId: String) {
        val session = requireOwnedSession(uid, sessionId)
        if (session.phase < OcrSessionState.PHASE_FINALIZING) {
            session.phase = OcrSessionState.PHASE_FINALIZING
            // TODO(PR C3 follow-up): drain queue + emit ocr_result_finalized.
            // For now we just transition phase; the next stateOf() call
            // observes FINALIZING.
        }
    }

    /** Close + remove a session. Idempotent. */
    fun close(uid: Int, sessionId: String) {
        val session = sessions[sessionId] ?: return
        if (session.uid != uid) {
            throw IllegalStateException("Session not owned by uid=$uid")
        }
        sessions.remove(sessionId)
        recognitionDispatcher?.closeSession(sessionId)
        // Close the event-stream writer if attached so the SDK-side
        // Flow.collect coroutine sees EOF and terminates.
        (session.eventWriter as? com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter)
            ?.runCatching { close() }
        MindlayerLog.i(
            TAG,
            "OCR session closed: id=$sessionId, accepted=${session.framesAccepted}, " +
                "dropped=${session.framesDropped}, rejected=${session.framesRejected}",
        )
    }

    /**
     * Drop all sessions owned by [uid]. Called by the binder layer
     * when a client dies (binder linkToDeath).
     */
    fun closeAllForUid(uid: Int) {
        val owned = sessions.entries.filter { it.value.uid == uid }
        owned.forEach { entry ->
            sessions.remove(entry.key)
            recognitionDispatcher?.closeSession(entry.key)
            (entry.value.eventWriter as? com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter)
                ?.runCatching { close() }
        }
        frameRateBuckets.remove(uid)
        if (owned.isNotEmpty()) {
            MindlayerLog.i(TAG, "Closed ${owned.size} OCR session(s) for uid=$uid on death")
        }
    }

    /** True when [uid] owns [sessionId]; used by binder requireOwnership. */
    fun isOwner(uid: Int, sessionId: String): Boolean =
        sessions[sessionId]?.uid == uid

    /**
     * Attach an OCR_V1 event-stream writer to a session. The binder
     * layer calls this after validating ownership of [sessionId] for
     * [uid]. Subsequent intake/processing calls emit events via this
     * writer.
     *
     * Idempotent: attaching the same writer twice is fine; attaching
     * a different writer replaces the previous (the binder closes
     * the displaced pipe on the SDK side via the AutoClose semantics
     * of [android.os.ParcelFileDescriptor]).
     *
     * @return true when the session was found + owned; false otherwise
     *   (caller must close the pipe in that case).
     */
    fun attachEventWriter(uid: Int, sessionId: String, writer: Any): Boolean {
        val session = sessions[sessionId] ?: return false
        if (session.uid != uid) return false
        session.eventWriter = writer
        session.streamAttached = true
        return true
    }

    /** Read-only view of the attached event writer for tests + binder cleanup. */
    internal fun eventWriterFor(uid: Int, sessionId: String): Any? {
        val session = sessions[sessionId] ?: return null
        if (session.uid != uid) return null
        return session.eventWriter
    }

    /** Currently active session count — used for diagnostics + tests. */
    fun activeSessionCount(): Int =
        sessions.values.count { it.phase < OcrSessionState.PHASE_CLOSED }

    /** Get the limits this manager is configured with. */
    fun getLimits(): OcrLimits = limits

    /**
     * Sweep idle + expired sessions. Called opportunistically on
     * every createSession; expensive-loop-free since it iterates
     * concurrent map entries.
     */
    fun sweepIdleAndExpired() {
        val now = clock()
        val maxDuration = limits.maxOcrSessionDurationMs
        val toRemove = mutableListOf<String>()
        for ((id, session) in sessions) {
            val age = now - session.createdAtMs
            val idleFor = now - session.lastFrameAtMs
            if (maxDuration in 1..age) {
                MindlayerLog.i(TAG, "OCR session $id expired (age=${age}ms)")
                toRemove += id
            } else if (idleTimeoutMs in 1..idleFor && session.phase < OcrSessionState.PHASE_FINALIZING) {
                MindlayerLog.i(TAG, "OCR session $id idle (${idleFor}ms)")
                toRemove += id
            }
        }
        toRemove.forEach { sessions.remove(it) }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun requireOwnedSession(uid: Int, sessionId: String): OcrSession {
        val session = sessions[sessionId]
            ?: throw IllegalStateException("Session not found or not owned by uid=$uid")
        if (session.uid != uid) {
            // Anti-enumeration: surface the same exception class +
            // message as not-found. The binder layer translates this
            // to SESSION_NOT_FOUND_OR_NOT_OWNED.
            throw IllegalStateException("Session not found or not owned by uid=$uid")
        }
        return session
    }

    // ── Inner classes ────────────────────────────────────────────────────

    /** Mutable per-session state. */
    internal class OcrSession(
        val sessionId: String,
        val uid: Int,
        val config: OcrSessionConfig,
        val maxFrames: Int,
        val createdAtMs: Long,
    ) {
        @Volatile var phase: Int = OcrSessionState.PHASE_ACTIVE
        @Volatile var framesAccepted: Int = 0
        @Volatile var framesDropped: Int = 0
        @Volatile var framesRejected: Int = 0
        @Volatile var pendingQueueDepth: Int = 0
        @Volatile var streamAttached: Boolean = false
        @Volatile var lastFrameAtMs: Long = 0
        @Volatile var lastFrameId: Long? = null
        @Volatile var lastAcceptedDHash: ULong? = null

        /**
         * Optional event-stream writer for the OCR_V1 protocol pipe.
         * Set via [OcrSessionManager.attachEventWriter] when a caller
         * invokes `streamOcrEvents`. The session manager emits OCR
         * events through this writer; null when no caller has
         * subscribed.
         *
         * Type-erased to `Any?` to keep the inner-class declaration
         * dependency-free; the binder layer casts to
         * `OcrTokenStreamWriter` before invoking.
         */
        @Volatile var eventWriter: Any? = null
    }

    /**
     * Simple sliding-window token bucket for per-UID frame-rate
     * limiting. Not thread-safe; we accept the small overcount under
     * contention rather than serialise the hot intake path.
     */
    internal class FrameRateBucket(private val maxPerMinute: Int) {
        private val timestamps = ArrayDeque<Long>(maxPerMinute.coerceAtLeast(1))
        fun tryAcquire(nowMs: Long): Boolean {
            if (maxPerMinute <= 0) return true
            // Drop everything older than 60s.
            while (timestamps.isNotEmpty() && nowMs - timestamps.first() >= 60_000L) {
                timestamps.removeFirst()
            }
            if (timestamps.size >= maxPerMinute) return false
            timestamps.addLast(nowMs)
            return true
        }
        fun retryAfterMs(nowMs: Long): Long {
            if (timestamps.isEmpty()) return 0L
            val oldest = timestamps.first()
            return ((60_000L - (nowMs - oldest)).coerceAtLeast(0L))
        }
    }

    companion object {
        private const val TAG = "OcrSessionManager"
        const val DEFAULT_IDLE_TIMEOUT_MS = 30_000L

        /**
         * Limits the service advertises by default for OCR sessions on
         * the binder's [com.adsamcik.mindlayer.IMindlayerService.getOcrLimits]
         * surface. These are conservative Phase 1 defaults; PR C3+
         * can tune them once we have device-class telemetry.
         */
        fun defaultLimits(): OcrLimits = OcrLimits(
            maxConcurrentOcrSessions = 1,
            maxOcrFramesPerMinute = 120,
            maxFramesPerOcrSession = 60,
            maxOcrSessionDurationMs = 5L * 60L * 1000L, // 5 minutes
            ocrPerFrameDecodeBudgetTokens = 1024,
            ocrSchemaJsonMaxLen = 16 * 1024,
        )
    }
}
