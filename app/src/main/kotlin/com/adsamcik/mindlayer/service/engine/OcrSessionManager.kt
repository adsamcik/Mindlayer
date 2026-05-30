package com.adsamcik.mindlayer.service.engine

import android.os.SystemClock
import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrFrameMeta
import com.adsamcik.mindlayer.OcrLimits
import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.OcrSessionState
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.ceil
import kotlin.math.floor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.coroutines.Job
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
 * # Async recognition path
 *
 * Accepted frames are dispatched to [OcrRecognitionDispatcher] when an engine
 * is wired. The dispatcher runs [PaddleOcrEngine.recognise], fuses raw OCR
 * lines via [OcrFieldFusion], emits stream events when a writer is attached,
 * and degrades per-frame failures to `FrameProcessed(lineCount=0)` so session
 * intake stays alive.
 *
 * # Threading
 *
 * Public methods are safe to call from any thread. Each session owns a
 * coroutine [Mutex] that serializes intake state mutation and every
 * [com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter] emission;
 * dispatcher jobs receive that same mutex because the writer is not
 * thread-safe. The global registry uses a [ConcurrentHashMap].
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
    /** Defaults to OcrFeatureFlags.IS_PRODUCTION_READY; tests override for capability gating. */
    val isProductionReady: Boolean = OcrFeatureFlags.IS_PRODUCTION_READY,
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
        recognitionDispatcher?.registerSession(
            sessionId = sessionId,
            context = OcrExtractionContext(
                mode = config.mode,
                outputSchemaJson = config.outputSchemaJson,
            ),
        )
        val pageConfig = PageBoundariesConfig.parse(config.optionsJson)
        if (pageConfig.enabled) {
            recognitionDispatcher?.attachPageBoundariesConfig(sessionId, pageConfig)
        }
        MindlayerLog.i(
            TAG,
            "OCR session created: uid=$uid, mode=${config.mode}, maxFrames=$effectiveMaxFrames",
            sessionId = sessionId,
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
    ): OcrFrameAck = runBlocking {
        sweepIdleAndExpired()
        val sessionForLock = requireOwnedSession(uid, sessionId)
        sessionForLock.mutex.withLock {
            val intake = preparePushIntake(uid, sessionId, meta) ?: return@withLock rejectFinalized(meta)
            intake.earlyAck?.let { return@withLock it }
            val session = intake.session

            if (recognitionDispatcher != null && !session.streamAttached) {
                session.framesRejected++
                return@withLock OcrFrameAck(
                    frameId = meta.frameId,
                    status = OcrFrameAck.STATUS_REJECTED_STREAM_NOT_ATTACHED,
                    queueDepth = session.pendingQueueDepth,
                    retryAfterMs = 0L,
                )
            }

            val transformed = try {
                applyFrameMetadata(meta, yPlane, width, height)
            } catch (_: IllegalArgumentException) {
                session.framesRejected++
                return@withLock OcrFrameAck(frameId = meta.frameId, status = OcrFrameAck.STATUS_REJECTED_QUALITY, queueDepth = session.pendingQueueDepth, retryAfterMs = 0L)
            }

            val score = try {
                OcrFrameQualityPresort.score(transformed.yPlane, transformed.width, transformed.height, session.lastAcceptedDHash)
            } catch (e: IllegalArgumentException) {
                session.framesRejected++
                return@withLock OcrFrameAck(frameId = meta.frameId, status = OcrFrameAck.STATUS_REJECTED_QUALITY, queueDepth = session.pendingQueueDepth, retryAfterMs = 0L)
            }

            if (!score.isAccepted) {
                session.framesRejected++
                return@withLock OcrFrameAck(frameId = meta.frameId, status = OcrFrameAck.STATUS_REJECTED_QUALITY, queueDepth = session.pendingQueueDepth, retryAfterMs = 0L)
            }

            recordAcceptedFrame(session, intake.now, score.dHash).also { finalizeAfterSubmit ->
                val writer = session.eventWriter as? com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter
                val pageEnabled = recognitionDispatcher?.pageBoundariesConfig(sessionId)?.enabled == true
                val job = if (pageEnabled) {
                    recognitionDispatcher?.submitWithMeta(
                        sessionId = sessionId,
                        frameId = meta.frameId,
                        yPlane = transformed.yPlane,
                        width = transformed.width,
                        height = transformed.height,
                        config = OcrEngineConfig(),
                        writer = writer,
                        writerMutex = session.mutex,
                        extraJson = meta.extraJson,
                    )
                } else {
                    recognitionDispatcher?.submit(
                        sessionId = sessionId,
                        frameId = meta.frameId,
                        yPlane = transformed.yPlane,
                        width = transformed.width,
                        height = transformed.height,
                        config = OcrEngineConfig(),
                        writer = writer,
                        writerMutex = session.mutex,
                    )
                }
                job?.also {
                    session.activeJobs.add(it)
                    it.invokeOnCompletion { _ -> session.activeJobs.remove(it) }
                }
                if (finalizeAfterSubmit) {
                    recognitionDispatcher?.finalizeAsync(sessionId, writer)
                }
            }

            OcrFrameAck(frameId = meta.frameId, status = OcrFrameAck.STATUS_ACCEPTED, queueDepth = session.pendingQueueDepth, retryAfterMs = 0L)
        }
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
        sweepIdleAndExpired()
        return runBlocking {
            val sessionForLock = requireOwnedSession(uid, sessionId)
            sessionForLock.mutex.withLock {
                val intake = preparePushIntake(uid, sessionId, meta) ?: return@withLock rejectFinalized(meta)
                intake.earlyAck?.let { return@withLock it }
                val session = intake.session
                recordAcceptedFrame(session, intake.now, dhash = null)
                OcrFrameAck(frameId = meta.frameId, status = OcrFrameAck.STATUS_ACCEPTED, queueDepth = session.pendingQueueDepth, retryAfterMs = 0L)
            }
        }
    }

    fun rejectFrame(uid: Int, sessionId: String, meta: OcrFrameMeta): OcrFrameAck = runBlocking {
        val session = sessions[sessionId]
        if (session != null && session.uid == uid) {
            session.mutex.withLock {
                session.framesRejected++
                (session.eventWriter as? com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter)
                    ?.runCatching { writeFrameRejectedQuality(meta.frameId, "invalid_media") }
            }
        }
        OcrFrameAck(
            frameId = meta.frameId,
            status = OcrFrameAck.STATUS_REJECTED_QUALITY,
            queueDepth = session?.pendingQueueDepth ?: 0,
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

    private fun recordAcceptedFrame(session: OcrSession, now: Long, dhash: ULong?): Boolean {
        session.framesAccepted++
        session.lastFrameAtMs = now
        if (dhash != null) session.lastAcceptedDHash = dhash

        // Hard cap on per-session frame intake. Once we cross
        // maxFrames the session auto-finalises.
        if (session.framesAccepted >= session.maxFrames) {
            session.phase = OcrSessionState.PHASE_FINALIZING
            return true
        }

        // Recognition dispatch happens after this method returns the AIDL ack,
        // keeping frame intake synchronous and inference asynchronous.
        return false
    }

    internal data class TransformedFrame(
        val yPlane: ByteArray,
        val width: Int,
        val height: Int,
    )

    internal fun applyFrameMetadata(
        meta: OcrFrameMeta,
        yPlane: ByteArray,
        width: Int,
        height: Int,
    ): TransformedFrame {
        require(width > 0 && height > 0 && yPlane.size == width * height) {
            "Invalid Y-plane dimensions"
        }
        val rotated = rotateYPlane(yPlane, width, height, meta.rotationDegrees)
        return cropNormalized(rotated, meta.regionJson)
    }

    private fun rotateYPlane(yPlane: ByteArray, width: Int, height: Int, rotation: Int): TransformedFrame {
        return when (rotation) {
            0 -> TransformedFrame(yPlane, width, height)
            90 -> {
                val out = ByteArray(yPlane.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val nx = height - 1 - y
                        val ny = x
                        out[ny * height + nx] = yPlane[y * width + x]
                    }
                }
                TransformedFrame(out, height, width)
            }
            180 -> {
                val out = ByteArray(yPlane.size)
                for (i in yPlane.indices) out[yPlane.lastIndex - i] = yPlane[i]
                TransformedFrame(out, width, height)
            }
            270 -> {
                val out = ByteArray(yPlane.size)
                for (y in 0 until height) {
                    for (x in 0 until width) {
                        val nx = y
                        val ny = width - 1 - x
                        out[ny * height + nx] = yPlane[y * width + x]
                    }
                }
                TransformedFrame(out, height, width)
            }
            else -> throw IllegalArgumentException("Unsupported rotationDegrees=$rotation")
        }
    }

    private fun cropNormalized(frame: TransformedFrame, regionJson: String?): TransformedFrame {
        if (regionJson.isNullOrBlank()) return frame
        val obj = runCatching { json.parseToJsonElement(regionJson).jsonObject }
            .getOrElse { throw IllegalArgumentException("Invalid OCR regionJson") }
        fun value(name: String): Double = obj[name]?.jsonPrimitive?.doubleOrNull
            ?: throw IllegalArgumentException("Missing OCR region field $name")
        val x = value("x")
        val y = value("y")
        val w = value("w")
        val h = value("h")
        require(x in 0.0..1.0 && y in 0.0..1.0 && w > 0.0 && h > 0.0 && x + w <= 1.0 && y + h <= 1.0) {
            "OCR region must be normalized"
        }
        val left = floor(x * frame.width).toInt().coerceIn(0, frame.width - 1)
        val top = floor(y * frame.height).toInt().coerceIn(0, frame.height - 1)
        val right = ceil((x + w) * frame.width).toInt().coerceIn(left + 1, frame.width)
        val bottom = ceil((y + h) * frame.height).toInt().coerceIn(top + 1, frame.height)
        val outWidth = right - left
        val outHeight = bottom - top
        val out = ByteArray(outWidth * outHeight)
        for (row in 0 until outHeight) {
            System.arraycopy(frame.yPlane, (top + row) * frame.width + left, out, row * outWidth, outWidth)
        }
        return TransformedFrame(out, outWidth, outHeight)
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

    /** Mark the session as finalizing, drain jobs, and emit terminal events. */
    suspend fun finalize(uid: Int, sessionId: String) {
        sweepIdleAndExpired()
        val session = requireOwnedSession(uid, sessionId)
        session.mutex.withLock {
            if (session.phase == OcrSessionState.PHASE_FINALIZED) return
            if (session.phase < OcrSessionState.PHASE_FINALIZING) {
                session.phase = OcrSessionState.PHASE_FINALIZING
            }
        }
        drainActiveJobs(session, cancel = false)
        session.mutex.withLock {
            if (session.phase == OcrSessionState.PHASE_FINALIZED) return
            recognitionDispatcher?.finalize(
                sessionId,
                session.eventWriter as? com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter,
            )
            session.phase = OcrSessionState.PHASE_FINALIZED
        }
    }

    /** Close + remove a session. Idempotent. */
    suspend fun close(uid: Int, sessionId: String) {
        sweepIdleAndExpired()
        val session = sessions[sessionId] ?: return
        if (session.uid != uid) {
            throw IllegalStateException("Session not owned by uid=$uid")
        }
        cleanupSession(sessionId, session, cancelJobs = true)
    }

    /**
     * Drop all sessions owned by [uid]. Called by the binder layer
     * when a client dies (binder linkToDeath).
     */
    fun closeAllForUid(uid: Int) = runBlocking {
        val owned = sessions.entries.filter { it.value.uid == uid }
        owned.forEach { entry -> cleanupSession(entry.key, entry.value, cancelJobs = true) }
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
    fun attachEventWriter(uid: Int, sessionId: String, writer: Any): Boolean = runBlocking {
        val session = sessions[sessionId] ?: return@runBlocking false
        if (session.uid != uid) return@runBlocking false
        session.mutex.withLock {
            (writer as? com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter)
                ?.runCatching { writeHeader(sessionId) }
            session.eventWriter = writer
            session.streamAttached = true
        }
        true
    }

    /** Read-only view of the attached event writer for tests + binder cleanup. */
    internal fun eventWriterFor(uid: Int, sessionId: String): Any? {
        val session = sessions[sessionId] ?: return null
        if (session.uid != uid) return null
        return session.eventWriter
    }

    /** Cancel in-flight OCR recognition before pressure-driven native delegate unload. */
    suspend fun drainForMemoryPressure() {
        sessions.entries.toList().forEach { cleanupSession(it.key, it.value, cancelJobs = true) }
        recognitionDispatcher?.drainForMemoryPressure()
    }

    fun cancelAllForMemoryPressure() {
        sessions.entries.toList().forEach { (id, session) ->
            if (sessions.remove(id, session)) {
                session.phase = OcrSessionState.PHASE_CLOSED
                session.activeJobs.forEach { it.cancel() }
                session.activeJobs.clear()
                (session.eventWriter as? OcrTokenStreamWriter)?.runCatching { close() }
            }
        }
        recognitionDispatcher?.cancelAllForMemoryPressure()
    }

    suspend fun shutdown() {
        drainForMemoryPressure()
        recognitionDispatcher?.shutdown()
    }

    /** Currently active session count — used for diagnostics + tests. */
    fun activeSessionCount(): Int =
        sessions.values.count { it.phase < OcrSessionState.PHASE_CLOSED }

    /** Get the limits this manager is configured with. */
    fun getLimits(): OcrLimits = limits

    /**
     * True when the OCR engine bundle is loaded and the native delegates
     * have initialized successfully (i.e. the engine's state machine is
     * in [PaddleOcrEngineState.Ready]).
     *
     * Used by [com.adsamcik.mindlayer.service.ServiceBinder.getCapabilities]
     * to flip [com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_OCR_SESSION]
     * into the advertised set ONLY on devices where the OCR pipeline can
     * actually serve traffic. Devices without the model bundle, or where
     * native delegate init failed, keep the flag off — capability-aware
     * SDKs then degrade gracefully instead of calling `createOcrSession`
     * and getting a runtime error on `pushOcrFrame`.
     *
     * Returns `false` when no engine is wired (i.e. tests / dispatcher-only
     * configurations).
     */
    fun isEngineReady(): Boolean = engine?.state?.value == PaddleOcrEngineState.Ready

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
                MindlayerLog.i(TAG, "OCR session expired (age=${age}ms)", sessionId = id)
                toRemove += id
            } else if (idleTimeoutMs in 1..idleFor && session.phase < OcrSessionState.PHASE_FINALIZING) {
                MindlayerLog.i(TAG, "OCR session idle (${idleFor}ms)", sessionId = id)
                toRemove += id
            }
        }
        toRemove.forEach { id ->
            sessions[id]?.let { runBlocking { cleanupSession(id, it, cancelJobs = true) } }
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private suspend fun cleanupSession(sessionId: String, session: OcrSession, cancelJobs: Boolean) {
        if (!sessions.remove(sessionId, session)) return
        session.mutex.withLock { session.phase = OcrSessionState.PHASE_CLOSED }
        drainActiveJobs(session, cancel = cancelJobs)
        recognitionDispatcher?.closeSession(sessionId)
        session.mutex.withLock {
            (session.eventWriter as? com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter)
                ?.runCatching { close() }
        }
        MindlayerLog.i(
            TAG,
            "OCR session closed: accepted=${session.framesAccepted}, dropped=${session.framesDropped}, rejected=${session.framesRejected}",
            sessionId = sessionId,
        )
    }

    private suspend fun drainActiveJobs(session: OcrSession, cancel: Boolean) {
        while (true) {
            val jobs = session.activeJobs.toList()
            if (jobs.isEmpty()) return
            if (cancel) jobs.forEach { it.cancel() }
            jobs.joinAll()
        }
    }

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
        val mutex: Mutex = Mutex()
        val activeJobs: MutableSet<Job> = ConcurrentHashMap.newKeySet()
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
        private val json = Json { ignoreUnknownKeys = true }

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
