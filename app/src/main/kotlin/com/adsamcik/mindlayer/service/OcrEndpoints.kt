package com.adsamcik.mindlayer.service

import android.os.Binder
import android.os.ParcelFileDescriptor
import com.adsamcik.mindlayer.MediaPart
import com.adsamcik.mindlayer.OcrFrameAck
import com.adsamcik.mindlayer.OcrFrameMeta
import com.adsamcik.mindlayer.OcrLimits
import com.adsamcik.mindlayer.OcrSessionConfig
import com.adsamcik.mindlayer.OcrSessionState
import com.adsamcik.mindlayer.service.engine.MediaPartYPlaneExtractor
import com.adsamcik.mindlayer.service.engine.OcrSessionManager
import com.adsamcik.mindlayer.service.ipc.MAX_MEDIA_BYTES
import com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPoolExhaustedException
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.service.logging.safeLabelWithDetail
import com.adsamcik.mindlayer.service.logging.sanitizeLogField
import com.adsamcik.mindlayer.service.security.IpcInputValidator
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import kotlinx.coroutines.runBlocking

/**
 * Multi-frame OCR session endpoint logic, extracted from [ServiceBinder] as the
 * first slice of decomposing the AIDL trust-boundary god class.
 *
 * [ServiceBinder] keeps the authorization gate (`authorizeCall(...)`) at the
 * binder boundary — so `AidlAuthorizationGuardTest` still observes it on every
 * override, and the default-deny posture is unchanged — and delegates the
 * validated, post-auth engine interaction to this collaborator. Behaviour is
 * preserved exactly; only the home of the logic moved off the 3000-line binder.
 *
 * [typedException] is `ServiceBinder::typedBinderException` passed by reference so
 * the wire-error mapping stays single-sourced without exposing the binder's
 * private surface. The single-image `ocrImage` path and the embedding / deferred
 * / diagnostics endpoint groups remain in [ServiceBinder] for follow-up
 * extractions (see the PR roadmap).
 */
internal class OcrEndpoints(
    private val ocrSessionManager: OcrSessionManager,
    private val sharedMemoryPool: SharedMemoryPool?,
    private val typedException: (code: Int, message: String) -> RuntimeException,
) {

    fun createOcrSession(cfg: OcrSessionConfig): String {
        IpcInputValidator.validateOcrSessionConfig(cfg)
        val uid = Binder.getCallingUid()
        return try {
            ocrSessionManager.createSession(uid, cfg)
        } catch (e: IllegalStateException) {
            val msg = e.message.orEmpty()
            val code = when {
                msg.contains("concurrent session limit", ignoreCase = true) ->
                    MindlayerErrorCode.CONCURRENT_LIMIT
                else -> MindlayerErrorCode.OCR_SCHEMA_INVALID
            }
            throw typedException(code, msg)
        }
    }

    fun pushOcrFrame(sessionId: String, frame: MediaPart, meta: OcrFrameMeta): OcrFrameAck {
        IpcInputValidator.validateId(sessionId, "sessionId")
        IpcInputValidator.validateOcrFrameMeta(meta)
        val uid = Binder.getCallingUid()
        try {
            IpcInputValidator.validateImageTransfer(frame, MAX_MEDIA_BYTES)
        } catch (t: Throwable) {
            try { frame.source.close() } catch (_: Throwable) { /* fine */ }
            MindlayerLog.w(
                TAG,
                "OCR frame MediaPart rejected: ${t.safeLabel()}",
                sessionId = sanitizeLogField(sessionId),
                throwable = null,
            )
            throw typedException(MindlayerErrorCode.INVALID_REQUEST, "Invalid OCR MediaPart: ${t.safeLabel()}")
        }
        if (!ocrSessionManager.isOwner(uid, sessionId)) {
            try { frame.source.close() } catch (_: Throwable) { /* fine */ }
            throw typedException(
                MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
                "Session not found or not owned by caller",
            )
        }
        val pool = sharedMemoryPool
            ?: return ocrSessionManager.pushFrameMetadataOnly(uid, sessionId, meta)

        val scopedKey = "ocr:$uid:$sessionId:${meta.frameId}"
        val extracted = try {
            MediaPartYPlaneExtractor.extractY(frame, pool, scopedKey)
        } catch (e: SecurityException) {
            try { frame.source.close() } catch (_: Throwable) { /* fine */ }
            MindlayerLog.w(
                TAG,
                "OCR Y-plane extraction rejected: ${e.safeLabel()}",
                requestId = scopedKey,
                sessionId = sanitizeLogField(sessionId),
                throwable = null,
            )
            throw e
        } catch (e: SharedMemoryPoolExhaustedException) {
            try { frame.source.close() } catch (_: Throwable) { /* fine */ }
            MindlayerLog.w(
                TAG,
                "OCR Y-plane extraction resource exhausted: ${e.safeLabel()}",
                requestId = scopedKey,
                sessionId = sanitizeLogField(sessionId),
                throwable = null,
            )
            throw typedException(
                MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED,
                "shm_pool_exhausted reason=${e.reason} retryAfterMs=${e.retryAfterMs}",
            )
        } catch (t: Throwable) {
            try { frame.source.close() } catch (_: Throwable) { /* fine */ }
            MindlayerLog.w(
                TAG,
                "OCR Y-plane extraction failed: ${t.safeLabelWithDetail()}",
                requestId = scopedKey,
                sessionId = sanitizeLogField(sessionId),
                throwable = null,
            )
            return ocrSessionManager.rejectFrame(uid, sessionId, meta)
        }

        return ocrSessionManager.pushFrame(
            uid = uid,
            sessionId = sessionId,
            meta = meta,
            yPlane = extracted.yPlane,
            width = extracted.width,
            height = extracted.height,
        )
    }

    fun streamOcrEvents(sessionId: String, eventWriteEnd: ParcelFileDescriptor) {
        IpcInputValidator.validateId(sessionId, "sessionId")
        val uid = Binder.getCallingUid()
        if (!ocrSessionManager.isOwner(uid, sessionId)) {
            try {
                eventWriteEnd.close()
            } catch (_: java.io.IOException) {
                // best-effort
            }
            throw typedException(
                MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
                "Session not found or not owned by caller",
            )
        }
        // The writer takes ownership of the PFD (the wrapped
        // AutoCloseOutputStream closes it on writer close).
        val writer = OcrTokenStreamWriter(eventWriteEnd)
        val attached = ocrSessionManager.attachEventWriter(uid, sessionId, writer)
        if (!attached) {
            // Ownership flipped between isOwner check and attach — close the
            // writer (which closes the PFD).
            try {
                writer.close()
            } catch (_: Throwable) {
                // best-effort
            }
            throw typedException(
                MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
                "Session not found or not owned by caller",
            )
        }
    }

    fun getOcrSessionState(sessionId: String): OcrSessionState {
        IpcInputValidator.validateId(sessionId, "sessionId")
        val uid = Binder.getCallingUid()
        if (!ocrSessionManager.isOwner(uid, sessionId)) {
            throw typedException(
                MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
                "Session not found or not owned by caller",
            )
        }
        return try {
            ocrSessionManager.stateOf(uid, sessionId)
        } catch (e: IllegalStateException) {
            throw typedException(
                MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
                "Session not found or not owned by caller",
            )
        }
    }

    fun finalizeOcrSession(sessionId: String) {
        IpcInputValidator.validateId(sessionId, "sessionId")
        val uid = Binder.getCallingUid()
        if (!ocrSessionManager.isOwner(uid, sessionId)) {
            throw typedException(
                MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
                "Session not found or not owned by caller",
            )
        }
        runBlocking { ocrSessionManager.finalize(uid, sessionId) }
    }

    fun closeOcrSession(sessionId: String) {
        IpcInputValidator.validateId(sessionId, "sessionId")
        val uid = Binder.getCallingUid()
        // Idempotent: closing a non-owned session is a no-op (mirrors
        // close-semantics-for-already-closed-streams). We do NOT anti-enumerate
        // here because close() returning silently for unowned sessions is
        // indistinguishable from close()-of-closed.
        try {
            runBlocking { ocrSessionManager.close(uid, sessionId) }
        } catch (_: IllegalStateException) {
            // Session not found or not owned — silently no-op.
        }
    }

    fun getOcrLimits(): OcrLimits = ocrSessionManager.getLimits()

    private companion object {
        private const val TAG = "OcrEndpoints"
    }
}
