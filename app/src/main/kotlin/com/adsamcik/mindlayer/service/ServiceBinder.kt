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
import com.adsamcik.mindlayer.service.engine.SessionManager
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.logging.sanitizeLogField
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CallerIdentity
import com.adsamcik.mindlayer.service.security.CallerVerifier
import com.adsamcik.mindlayer.service.security.RateLimiter
import com.adsamcik.mindlayer.service.logging.safeLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

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
    private val diagnosticsRefreshInFlight = AtomicBoolean(false)
    private val engineWarmupInFlight = AtomicBoolean(false)

    @Volatile
    private var diagnosticsSnapshot: String = """{"status":"warming"}"""

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

    init {
        refreshDiagnosticsAsync()
    }

    companion object {
        private const val TAG = "ServiceBinder"

        /** Allowed characters for caller-supplied identifiers (sessionId/requestId). */
        private val SAFE_ID_REGEX = Regex("^[A-Za-z0-9._-]+$")
        private const val MAX_ID_LENGTH = 128

        /** Hard cap for `RequestMeta.textContent` on the binder side (256 KiB). */
        private const val MAX_REQUEST_TEXT_CHARS = 256 * 1024

        private val ALLOWED_ROLES = setOf("user", "model", "tool", "system")

        /** Boundary cap for SessionConfig.expirationMs — 1 ms .. 30 days. */
        private const val MAX_BOUNDARY_EXPIRATION_MS = 30L * 24 * 60 * 60 * 1000

        private val ALLOWED_BACKENDS = setOf("GPU", "CPU", "NPU")
        private val ALLOWED_HISTORY_ROLES = setOf("user", "model", "tool")
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
     *
     * **Order matters** (H2): rate-limit BEFORE allowlist so an un-approved
     * caller cannot drive unbounded `recordPending` disk I/O simply by
     * spamming the binder. The rejection-bookkeeping path is further
     * throttled by [RateLimiter.tryAcquireRejection] — only ~6 rejections
     * per minute per UID actually call `recordPending`.
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

        // 1. Rate-limit BEFORE any allowlist I/O. Stops volume regardless of
        //    whether the caller is approved or not.
        if (!rateLimiter.tryAcquire(uid)) {
            MindlayerLog.w(TAG, "Rate limit exceeded for ${identity.packageName} (uid=$uid)")
            throw SecurityException("Rate limit exceeded for ${identity.packageName}")
        }

        // 2. Allowlist check. A previously-denied caller is rejected silently.
        val store = allowlistStore
        if (store != null) {
            if (store.isDenied(identity.packageName, identity.signingCertSha256)) {
                throw SecurityException(
                    "App ${identity.packageName} not authorized — user approval required"
                )
            }
            if (!store.isAllowed(identity.packageName, identity.signingCertSha256)) {
                // Only do the (relatively expensive) recordPending if the
                // per-UID rejection bucket still has tokens. Otherwise drop
                // silently — prevents log spam + atomic-write storm.
                if (rateLimiter.tryAcquireRejection(uid)) {
                    store.recordPending(
                        pkg = identity.packageName,
                        sigSha256 = identity.signingCertSha256,
                        displayName = identity.displayName,
                    )
                    MindlayerLog.w(TAG, "Blocked un-approved caller ${identity.packageName} (uid=$uid)")
                }
                throw SecurityException(
                    "App ${identity.packageName} not authorized — user approval required"
                )
            }
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
            MindlayerLog.w(
                TAG,
                "Ownership violation: uid=$uid tried to touch session (owner=$owner)",
                sessionId = sanitizeLogField(sessionId),
            )
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

        // Atomic swap — the ConcurrentHashMap.compute call holds a per-bucket
        // lock for the duration of the lambda, ensuring that concurrent
        // registerClient calls from the same UID can never leak a
        // DeathRecipient nor end up with two recipients linked to the map.
        clientDeathRecipients.compute(uid) { _, prior ->
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
                clientToken to recipient
            } catch (e: RemoteException) {
                MindlayerLog.w(TAG, "Client token for uid=$uid already dead at registration")
                onClientDisconnected(uid)
                null
            }
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
        // M1: validate at the binder boundary BEFORE any expensive native
        // engine warmup. Otherwise an unprivileged-but-allowlisted caller
        // could trigger a multi-second engine init with arbitrary `backend`
        // / `maxTokens` values and only afterwards hit the SessionManager
        // validator.
        validateSessionConfigBoundary(config)
        MindlayerLog.d(TAG, "createSession from ${identity.packageName}")
        ensureEngineReadyOrStart(config)
        return try {
            orchestrator.createSession(config, uid)
        } catch (e: IllegalArgumentException) {
            // L2: do NOT echo `e.message` — internal validator strings can
            // leak memory-pressure level, KV-cache sizing, or other engine
            // state. Log internally with a safe label, surface a generic
            // SecurityException to the caller.
            MindlayerLog.w(TAG, "createSession rejected: ${e.javaClass.simpleName}")
            throw SecurityException("Invalid SessionConfig")
        } catch (e: IllegalStateException) {
            // Engine-not-ready is the legitimate signal — preserve it. Other
            // ISEs are redacted similarly to IAE above.
            if (e.message?.contains("initialization has been started") == true) throw e
            MindlayerLog.w(TAG, "createSession rejected: ${e.javaClass.simpleName}")
            throw SecurityException("Service not ready")
        }
    }

    /**
     * Boundary validation for [SessionConfig] (M1, M2). Mirrors and is
     * STRICTER than [com.adsamcik.mindlayer.service.engine.SessionManager.validateSessionConfig]
     * — duplicated intentionally so that an SDK bypass (someone calling AIDL
     * directly) cannot trigger the heavy engine path with malformed config.
     *
     * Throws [SecurityException] with a fixed, non-leaking message on any
     * failure (L2). The internal exception class is logged via
     * [MindlayerLog] for diagnostic purposes.
     */
    private fun validateSessionConfigBoundary(config: SessionConfig) {
        try {
            require(config.backend in ALLOWED_BACKENDS) { "backend" }
            require(config.maxTokens in 1..32_768) { "maxTokens" }
            require(config.samplerTopK in 1..1024) { "samplerTopK" }
            require(config.samplerTopP in 0.0f..1.0f) { "samplerTopP" }
            require(config.samplerTemperature in 0.0f..2.0f) { "samplerTemperature" }
            config.systemPrompt?.let {
                require(it.length <= SessionManager.MAX_SYSTEM_PROMPT_CHARS) { "systemPrompt" }
            }
            config.toolsJson?.let {
                require(it.length <= SessionManager.MAX_TOOLS_JSON_CHARS) { "toolsJson" }
            }
            config.extraContextJson?.let {
                require(it.length <= SessionManager.MAX_EXTRA_CONTEXT_CHARS) { "extraContextJson" }
            }
            config.initialHistory?.let { hist ->
                require(hist.size <= SessionManager.MAX_INITIAL_HISTORY_TURNS) { "initialHistory.size" }
                for (turn in hist) {
                    require(turn.role in ALLOWED_HISTORY_ROLES) { "initialHistory.role" }
                    require(turn.text.length <= SessionManager.MAX_HISTORY_TURN_CHARS) {
                        "initialHistory.text"
                    }
                }
            }
            require(config.expirationMs in 1L..MAX_BOUNDARY_EXPIRATION_MS) { "expirationMs" }
            config.sessionId?.let {
                require(it.length in 1..MAX_ID_LENGTH && SAFE_ID_REGEX.matches(it)) { "sessionId" }
            }
        } catch (e: IllegalArgumentException) {
            MindlayerLog.w(TAG, "SessionConfig boundary validation failed: ${e.message}")
            throw SecurityException("Invalid SessionConfig")
        }
    }

    /**
     * Boundary validation for [RequestMeta] (M3). Caps caller-supplied
     * identifiers and text size; restricts `role` to "user" (the only sane
     * runtime value) and `priority` to a small range.
     */
    private fun validateRequestMeta(meta: RequestMeta) {
        try {
            require(
                meta.requestId.length in 1..MAX_ID_LENGTH && SAFE_ID_REGEX.matches(meta.requestId)
            ) { "requestId" }
            require(
                meta.sessionId.length in 1..MAX_ID_LENGTH && SAFE_ID_REGEX.matches(meta.sessionId)
            ) { "sessionId" }
            // Only allowed roles for an inbound runtime request; "model"
            // / "tool" / "system" may also appear in tool-result turns.
            require(meta.role in ALLOWED_ROLES) { "role" }
            require(meta.priority in -10..10) { "priority" }
            meta.textContent?.let {
                require(it.length <= MAX_REQUEST_TEXT_CHARS) { "textContent" }
            }
        } catch (e: IllegalArgumentException) {
            MindlayerLog.w(TAG, "RequestMeta boundary validation failed: ${e.message}")
            throw SecurityException("Invalid request")
        }
    }

    private fun validateRequestId(requestId: String) {
        require(requestId.isNotBlank()) { "requestId must not be blank" }
        require(requestId.length <= 256) { "requestId too long" }
    }

    private fun ensureEngineReadyOrStart(config: SessionConfig) {
        if (engineManager.isInitialized) return
        startEngineWarmup(
            preferredBackend = config.backend,
            maxTokens = config.maxTokens,
        )
        throw IllegalStateException(
            "Engine is not initialized; initialization has been started. " +
                "Retry createSession after service status reports engine loaded."
        )
    }

    override fun destroySession(sessionId: String) {
        authorizeCall()
        requireOwnership(sessionId)
        MindlayerLog.d(TAG, "destroySession", sessionId = sanitizeLogField(sessionId))
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
        // M11: track FD ownership so any synchronous failure path closes the
        // FDs we received from AIDL. The orchestrator takes ownership only on
        // a successful return from `orchestrator.infer(...)`.
        var handedOff = false
        try {
            val identity = authorizeCall()
            val uid = Binder.getCallingUid()

            // M3: validate caller-controlled fields BEFORE doing any session
            // ownership lookup or rate-limit accounting. Cheap to do, prevents
            // garbage from flowing further into the engine.
            validateRequestMeta(meta)

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

            // L11: identifiers were already shape-checked by validateRequestMeta,
            // but use sanitizeLogField as defense-in-depth in case a future
            // refactor relaxes the regex.
            MindlayerLog.d(
                TAG,
                "infer from ${identity.packageName}",
                requestId = sanitizeLogField(meta.requestId),
                sessionId = sanitizeLogField(meta.sessionId),
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
                handedOff = true
            } catch (t: Throwable) {
                if (activeInferenceUids.remove(key) != null) {
                    rateLimiter.endInference(uid)
                }
                throw t
            }
        } finally {
            if (!handedOff) {
                // Close any FDs we duped during the AIDL transaction so they
                // don't leak when validation/ownership/rate-limit rejects the
                // call before orchestrator.infer takes ownership.
                try { eventWriteEnd.close() } catch (_: Exception) {}
                try { image?.source?.close() } catch (_: Exception) {}
                try { audio?.source?.close() } catch (_: Exception) {}
            }
        }
    }

    override fun cancelInference(requestId: String) {
        authorizeCall()
        try { validateRequestId(requestId) } catch (e: IllegalArgumentException) {
            throw SecurityException("Invalid request")
        }
        val uid = Binder.getCallingUid()
        // Self-UID dashboard can cancel anything (support/diagnostics); external
        // callers may only cancel their own requests.
        if (uid != Process.myUid()) {
            val key = inferenceKey(uid, requestId)
            if (!activeInferenceUids.containsKey(key)) {
                // Either already completed, never existed, or belongs to another UID.
                // Swallow silently — raising here would leak whether the id exists elsewhere.
                MindlayerLog.d(
                    TAG,
                    "cancelInference: no active request owned by uid=$uid",
                    requestId = sanitizeLogField(requestId),
                )
                return
            }
        }
        MindlayerLog.d(TAG, "cancelInference", requestId = sanitizeLogField(requestId))
        // Concurrency slot is released by the orchestrator's completion hook.
        orchestrator.cancelInference(requestId)
    }

    // ---- Tool results -------------------------------------------------------

    override fun submitToolResult(requestId: String, result: ToolResult) {
        authorizeCall()
        try { validateRequestId(requestId) } catch (e: IllegalArgumentException) {
            throw SecurityException("Invalid request")
        }
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid()) {
            val key = inferenceKey(uid, requestId)
            if (!activeInferenceUids.containsKey(key)) {
                throw SecurityException("No active request owned by caller")
            }
        }
        MindlayerLog.d(
            TAG,
            "submitToolResult tool=${sanitizeLogField(result.toolName)}, callId=${result.callId ?: "(none)"}",
            requestId = sanitizeLogField(requestId),
        )
        orchestrator.toolCallBridge.submitResult(
            requestId = requestId,
            callId = result.callId,
            toolName = result.toolName,
            resultJson = result.resultJson,
        )
    }

    private fun inferenceKey(uid: Int, requestId: String): String = "$uid:$requestId"

    // ---- Prewarm -----------------------------------------------------------

    override fun prewarm(backend: String?) {
        authorizeCall()
        MindlayerLog.d(TAG, "prewarm: backend=${backend ?: "GPU"}")
        startEngineWarmup(
            preferredBackend = backend ?: "GPU",
            maxTokens = 4096,
        )
    }

    private fun startEngineWarmup(preferredBackend: String, maxTokens: Int) {
        if (engineManager.isInitialized) return
        if (!engineWarmupInFlight.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                engineManager.initialize(
                    preferredBackend = preferredBackend,
                    maxTokens = maxTokens,
                )
            } catch (e: Exception) {
                MindlayerLog.w(TAG, "Engine warmup failed: ${e.safeLabel()}")
            } finally {
                engineWarmupInFlight.set(false)
            }
        }
    }

    // ---- Status ------------------------------------------------------------

    override fun getStatus(): ServiceStatus {
        authorizeCall()
        val uid = Binder.getCallingUid()
        val isSelf = uid == Process.myUid()
        val thermalPolicy = thermalMonitor.currentPolicy.value
        val thermalSample = thermalMonitor.latestSample.value
        val memSnapshot = memoryBudget.currentSnapshot()

        // L1: only the dashboard (self-UID) sees device-wide metrics that
        // could be used for cross-app workload inference / fingerprinting.
        // External callers see their own activeInferenceCount and zeroed
        // memory/headroom fields.
        return if (isSelf) {
            ServiceStatus(
                isEngineLoaded = engineManager.isInitialized,
                engineWarming = engineWarmupInFlight.get(),
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
        } else {
            ServiceStatus(
                isEngineLoaded = engineManager.isInitialized,
                engineWarming = engineWarmupInFlight.get(),
                activeSessionCount = orchestrator.listSessionsOwnedBy(uid).size,
                activeInferenceCount = rateLimiter.concurrentFor(uid),
                backend = engineManager.currentBackend,
                thermalBand = thermalPolicy.band.name,
                isForeground = false,
                uptimeMs = android.os.SystemClock.elapsedRealtime() - service.createdAtMs,
                memoryPressure = "NORMAL",
                availableRamMb = 0L,
                totalRamMb = 0L,
                maxSessions = memoryBudget.deviceTier.maxSessions,
                headroom = null,
            )
        }
    }

    override fun getDiagnostics(): String {
        authorizeCall()
        if (Binder.getCallingUid() != Process.myUid()) {
            throw SecurityException("Diagnostics are restricted to the Mindlayer dashboard")
        }
        refreshDiagnosticsAsync()
        return diagnosticsSnapshot
    }

    private fun refreshDiagnosticsAsync() {
        if (!diagnosticsRefreshInFlight.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                diagnosticsSnapshot = diagnosticExporter.export()
            } catch (e: Exception) {
                MindlayerLog.w(TAG, "Diagnostics refresh failed: ${e.safeLabel()}")
            } finally {
                diagnosticsRefreshInFlight.set(false)
            }
        }
    }

    override fun getEngineInfo(): EngineInfo {
        authorizeCall()
        val currentModel = engineManager.currentModel
        val modelPath = currentModel?.path.orEmpty()
        val modelId = currentModel?.id
            ?.takeUnless { it.isBlank() }
            ?: modelPath
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .removeSuffix(".litertlm")
        val modelSize = currentModel?.sizeBytes
            ?.takeIf { it > 0 }
            ?: 0L

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
