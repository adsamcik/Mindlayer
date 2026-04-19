package com.adsamcik.mindlayer.service

import android.content.Context
import android.os.Binder
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.os.RemoteException
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.AudioTransfer
import com.adsamcik.mindlayer.EngineInfo
import com.adsamcik.mindlayer.IMindlayerService
import com.adsamcik.mindlayer.ImageTransfer
import com.adsamcik.mindlayer.RequestMeta
import com.adsamcik.mindlayer.ServiceStatus
import com.adsamcik.mindlayer.SessionConfig
import com.adsamcik.mindlayer.SessionInfo
import com.adsamcik.mindlayer.ToolResult
import com.adsamcik.mindlayer.service.engine.EngineManager
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CallerIdentity
import com.adsamcik.mindlayer.service.security.CallerVerifier
import com.adsamcik.mindlayer.service.security.RateLimiter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * AIDL binder implementation. Every entry point enforces:
 *  1. Caller identity resolution via [callerVerifier] (rejects shared UIDs).
 *  2. Allowlist check via [allowlistStore] (user-granted, sig-pinned).
 *  3. Request-rate + concurrency limits via [rateLimiter].
 *  4. Per-UID session ownership tracked in [InferenceOrchestrator] so that
 *     a caller's sessions can be torn down via [onClientDisconnected].
 *
 * Verification components are injected so tests can supply lenient fakes
 * without mocking [android.content.pm.PackageManager].
 */
class ServiceBinder(
    private val service: MindlayerMlService,
    val engineManager: EngineManager,
    val orchestrator: InferenceOrchestrator,
    private val diagnosticExporter: DiagnosticExporter,
    private val thermalMonitor: ThermalMonitor,
    private val memoryBudget: MemoryBudget,
    private val context: Context = service,
    private val callerVerifier: CallerVerifierGate = DefaultCallerVerifierGate,
    private val allowlistStore: AllowlistStore? = AllowlistStore(service),
    private val rateLimiter: RateLimiter = RateLimiter(),
) : IMindlayerService.Stub() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** requestId → UID that owns the concurrency slot. */
    private val activeInferenceUids = ConcurrentHashMap<String, Int>()

    /**
     * Client liveness tokens keyed by UID. Value is the DeathRecipient linked
     * to the client's binder; we keep a reference so we can unlinkToDeath on
     * explicit teardown if needed. One entry per UID is sufficient — multiple
     * registerClient calls from the same UID are coalesced.
     */
    private val clientDeathRecipients =
        ConcurrentHashMap<Int, Pair<IBinder, IBinder.DeathRecipient>>()

    companion object {
        private const val TAG = "ServiceBinder"
    }

    // ---- Security gate -----------------------------------------------------

    /**
     * Resolve the calling UID to a verified, allowlisted identity. Consumes
     * one token from the per-UID rate-limit bucket. Throws [SecurityException]
     * on any failure. Callers must invoke this as their FIRST operation.
     *
     * **Self-UID bypass**: when the caller is in the same UID as this process
     * (the built-in dashboard speaking to its own `:ml` service over AIDL),
     * we skip the allowlist + rate-limit checks. Without this the dashboard
     * would self-deny (never user-approved) and self-rate-limit (polling
     * 3 RPCs every 2s = 90 RPM > default 60 RPM).
     */
    private fun authorizeCall(): CallerIdentity {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) {
            return SELF_IDENTITY
        }
        val identity = callerVerifier.identify(context, uid)
            ?: throw SecurityException(
                "Caller identity could not be verified (uid=$uid)"
            )

        val store = allowlistStore
        if (store != null && !store.isAllowed(identity.packageName, identity.signingCertSha256)) {
            store.recordPending(
                pkg = identity.packageName,
                sigSha256 = identity.signingCertSha256,
                displayName = identity.displayName,
            )
            MindlayerLog.w(TAG, "Blocked un-approved caller ${identity.packageName} (uid=$uid)")
            throw SecurityException(
                "App ${identity.packageName} not authorized — user approval required"
            )
        }

        if (!rateLimiter.tryAcquire(uid)) {
            MindlayerLog.w(TAG, "Rate limit exceeded for ${identity.packageName} (uid=$uid)")
            throw SecurityException("Rate limit exceeded for ${identity.packageName}")
        }
        return identity
    }

    /** Identity assigned to same-UID callers (the built-in dashboard). */
    private val SELF_IDENTITY: CallerIdentity
        get() = CallerIdentity(
            packageName = context.packageName,
            signingCertSha256 = "self",
            displayName = "Mindlayer (self)",
        )

    /**
     * Assert that the caller owns the session, or is the self-UID dashboard.
     * Unknown sessions are treated as not-owned (SecurityException) so we
     * don't leak their existence to arbitrary callers via a 404.
     */
    private fun requireOwnership(sessionId: String) {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return
        val owner = orchestrator.getSessionOwner(sessionId)
        if (owner == null || owner != uid) {
            MindlayerLog.w(TAG, "Ownership violation: uid=$uid tried to touch session=$sessionId (owner=$owner)", sessionId = sessionId)
            throw SecurityException("Session not found or not owned by caller")
        }
    }

    // ---- Client liveness ---------------------------------------------------

    /**
     * Client registers a liveness token; we [linkToDeath] so that if the
     * client process dies unexpectedly, [onClientDisconnected] fires and all
     * sessions owned by the caller's UID are torn down. Safe to call multiple
     * times — subsequent calls from the same UID replace the prior recipient.
     */
    override fun registerClient(clientToken: IBinder) {
        authorizeCall()
        val uid = Binder.getCallingUid()

        val prior = clientDeathRecipients.remove(uid)
        prior?.let { (oldToken, oldRecipient) ->
            try { oldToken.unlinkToDeath(oldRecipient, 0) } catch (_: Throwable) { /* fine */ }
        }

        val recipient = IBinder.DeathRecipient {
            MindlayerLog.w(TAG, "Client uid=$uid died; cleaning up owned sessions")
            clientDeathRecipients.remove(uid)
            onClientDisconnected(uid)
        }
        try {
            clientToken.linkToDeath(recipient, 0)
            clientDeathRecipients[uid] = clientToken to recipient
        } catch (e: RemoteException) {
            // Token already dead — run cleanup immediately.
            MindlayerLog.w(TAG, "Client token for uid=$uid already dead at registration")
            onClientDisconnected(uid)
        }
    }

    /**
     * Tears down every session owned by [uid] and releases any lingering
     * concurrency slots. Invoked by the [IBinder.DeathRecipient] registered
     * in [registerClient], and by tests. Idempotent.
     */
    fun onClientDisconnected(uid: Int) {
        val orphaned = orchestrator.closeAllOwnedBy(uid)
        if (orphaned.isNotEmpty()) {
            MindlayerLog.i(TAG, "Released ${orphaned.size} session(s) for uid=$uid")
        }
        val prefix = "$uid:"
        val stale = activeInferenceUids.entries
            .filter { it.key.startsWith(prefix) }
            .map { it.key }
        stale.forEach { key ->
            if (activeInferenceUids.remove(key) != null) {
                rateLimiter.endInference(uid)
            }
        }
    }

    // ---- Session management ------------------------------------------------

    override fun createSession(config: SessionConfig): String {
        val identity = authorizeCall()
        val uid = Binder.getCallingUid()
        MindlayerLog.d(TAG, "createSession from ${identity.packageName}")
        return try {
            orchestrator.createSession(config, uid)
        } catch (e: IllegalArgumentException) {
            throw SecurityException("Invalid SessionConfig: ${e.message}")
        }
    }

    override fun destroySession(sessionId: String) {
        authorizeCall()
        requireOwnership(sessionId)
        MindlayerLog.d(TAG, "destroySession: $sessionId", sessionId = sessionId)
        orchestrator.destroySession(sessionId)
    }

    override fun getSessionInfo(sessionId: String): SessionInfo? {
        authorizeCall()
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid()) {
            val owner = orchestrator.getSessionOwner(sessionId) ?: return null
            if (owner != uid) return null
        }
        return orchestrator.getSessionInfo(sessionId)
    }

    override fun listSessions(): List<SessionInfo> {
        authorizeCall()
        val uid = Binder.getCallingUid()
        // Dashboard (self-UID) sees every session for diagnostics; external
        // callers only see their own — otherwise one app could enumerate
        // another's session IDs.
        return if (uid == Process.myUid()) {
            orchestrator.listSessions()
        } else {
            orchestrator.listSessionsOwnedBy(uid)
        }
    }

    // ---- Inference ---------------------------------------------------------

    override fun infer(
        meta: RequestMeta,
        image: ImageTransfer?,
        audio: AudioTransfer?,
        eventWriteEnd: ParcelFileDescriptor,
    ) {
        val identity = authorizeCall()
        val uid = Binder.getCallingUid()

        // Caller must own the target session.
        requireOwnership(meta.sessionId)

        if (!rateLimiter.beginInference(uid)) {
            MindlayerLog.w(
                TAG,
                "Concurrent inference limit exceeded for ${identity.packageName}",
            )
            throw SecurityException(
                "Concurrent inference limit exceeded for ${identity.packageName}"
            )
        }

        MindlayerLog.d(
            TAG,
            "infer: requestId=${meta.requestId}, session=${meta.sessionId} from ${identity.packageName}",
            requestId = meta.requestId,
            sessionId = meta.sessionId,
        )
        // Namespace the active-request map by UID so two callers that coin the
        // same requestId (UUID collision / malicious) cannot collide on our
        // concurrency accounting, nor cancel each other's inference.
        val key = inferenceKey(uid, meta.requestId)
        activeInferenceUids[key] = uid
        try {
            orchestrator.infer(meta, image, audio, eventWriteEnd) {
                if (activeInferenceUids.remove(key) != null) {
                    rateLimiter.endInference(uid)
                }
            }
        } catch (t: Throwable) {
            if (activeInferenceUids.remove(key) != null) {
                rateLimiter.endInference(uid)
            }
            throw t
        }
    }

    override fun cancelInference(requestId: String) {
        authorizeCall()
        val uid = Binder.getCallingUid()
        // Self-UID dashboard can cancel anything (support/diagnostics); external
        // callers may only cancel their own requests.
        if (uid != Process.myUid()) {
            val key = inferenceKey(uid, requestId)
            if (!activeInferenceUids.containsKey(key)) {
                // Either already completed, never existed, or belongs to another UID.
                // Swallow silently — raising here would leak whether the id exists elsewhere.
                MindlayerLog.d(TAG, "cancelInference: no active request owned by uid=$uid", requestId = requestId)
                return
            }
        }
        MindlayerLog.d(TAG, "cancelInference: $requestId", requestId = requestId)
        // Concurrency slot is released by the orchestrator's completion hook.
        orchestrator.cancelInference(requestId)
    }

    // ---- Tool results -------------------------------------------------------

    override fun submitToolResult(requestId: String, result: ToolResult) {
        authorizeCall()
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid()) {
            val key = inferenceKey(uid, requestId)
            if (!activeInferenceUids.containsKey(key)) {
                throw SecurityException("No active request owned by caller")
            }
        }
        MindlayerLog.d(
            TAG,
            "submitToolResult: $requestId, tool=${result.toolName}",
            requestId = requestId,
        )
        orchestrator.toolCallBridge.submitResult(
            requestId = requestId,
            toolName = result.toolName,
            resultJson = result.resultJson,
        )
    }

    private fun inferenceKey(uid: Int, requestId: String): String = "$uid:$requestId"

    // ---- Prewarm -----------------------------------------------------------

    override fun prewarm(backend: String?) {
        authorizeCall()
        MindlayerLog.d(TAG, "prewarm: backend=${backend ?: "GPU"}")
        scope.launch {
            try {
                engineManager.initialize(
                    preferredBackend = backend ?: "GPU",
                    maxTokens = 4096,
                )
            } catch (e: Exception) {
                MindlayerLog.w(TAG, "Prewarm failed: ${e.message}")
            }
        }
    }

    // ---- Status ------------------------------------------------------------

    override fun getStatus(): ServiceStatus {
        authorizeCall()
        val thermalPolicy = thermalMonitor.currentPolicy.value
        val thermalSample = thermalMonitor.latestSample.value
        val memSnapshot = memoryBudget.currentSnapshot()

        return ServiceStatus(
            isEngineLoaded = engineManager.isInitialized,
            activeSessionCount = orchestrator.listSessions().size,
            activeInferenceCount = service.activeInferenceCount,
            backend = engineManager.currentBackend,
            thermalBand = thermalPolicy.band.name,
            isForeground = service.activeInferenceCount > 0,
            uptimeMs = android.os.SystemClock.elapsedRealtime() - service.createdAtMs,
            memoryPressure = memSnapshot.pressure.name,
            availableRamMb = memSnapshot.availableMb,
            totalRamMb = memSnapshot.totalMb,
            maxSessions = memoryBudget.deviceTier.maxSessions,
            headroom = thermalSample?.headroom10s,
        )
    }

    override fun getDiagnostics(): String {
        authorizeCall()
        return runBlocking { diagnosticExporter.export() }
    }

    override fun getEngineInfo(): EngineInfo {
        authorizeCall()
        val currentModel = engineManager.currentModel
        val modelPath = try {
            engineManager.modelPath
        } catch (_: Throwable) {
            ""
        }
        val modelId = currentModel?.id
            ?.takeUnless { it.isBlank() }
            ?: modelPath
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .removeSuffix(".litertlm")
        val modelSize = currentModel?.sizeBytes
            ?.takeIf { it > 0 }
            ?: if (modelPath.isNotEmpty()) {
            try {
                File(modelPath).length()
            } catch (_: Throwable) {
                0L
            }
        } else 0L

        return EngineInfo(
            modelId = modelId,
            modelSizeBytes = modelSize,
            backend = engineManager.currentBackend,
            maxTokens = 4096,
            initTimeSeconds = engineManager.initTimeSeconds,
            lastPrefillToksPerSec = 0f,
            lastDecodeToksPerSec = 0f,
        )
    }

    /**
     * Pluggable caller-verification hook. Production uses [CallerVerifier];
     * tests inject a lenient fake so they don't need to mock PackageManager.
     */
    fun interface CallerVerifierGate {
        fun identify(context: Context, callingUid: Int): CallerIdentity?
    }

    object DefaultCallerVerifierGate : CallerVerifierGate {
        override fun identify(context: Context, callingUid: Int): CallerIdentity? =
            CallerVerifier.identifyCaller(context, callingUid)
    }
}
