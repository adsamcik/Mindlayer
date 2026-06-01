package com.adsamcik.mindlayer.service

import android.content.Context
import android.os.Bundle
import com.adsamcik.mindlayer.DeferredHandle
import com.adsamcik.mindlayer.DeferredResult
import com.adsamcik.mindlayer.service.engine.DeferredStore
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.EOFException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.os.Binder
import android.os.Build
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
import com.adsamcik.mindlayer.service.engine.EmbeddingCoordinator
import com.adsamcik.mindlayer.service.engine.MediaPartYPlaneExtractor
import com.adsamcik.mindlayer.service.engine.OcrSessionManager
import com.adsamcik.mindlayer.service.engine.EngineState
import com.adsamcik.mindlayer.service.engine.InitFailure
import com.adsamcik.mindlayer.service.engine.SessionQuotaExceededException
import com.adsamcik.mindlayer.service.engine.SessionResourceExhaustedException
import com.adsamcik.mindlayer.service.engine.InferenceOrchestrator
import com.adsamcik.mindlayer.service.engine.MemoryBudget
import com.adsamcik.mindlayer.service.engine.SessionManager
import com.adsamcik.mindlayer.service.engine.SessionOwnerToken
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import com.adsamcik.mindlayer.service.engine.ThermalConfidence
import com.adsamcik.mindlayer.service.health.MlHealthRecorder
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.loggable
import com.adsamcik.mindlayer.service.logging.safeLabel
import com.adsamcik.mindlayer.service.logging.safeLabelWithDetail
import com.adsamcik.mindlayer.service.logging.sanitizeLogField
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CallerIdentity
import com.adsamcik.mindlayer.service.security.CallerVerifier
import com.adsamcik.mindlayer.service.security.IpcInputValidator
import com.adsamcik.mindlayer.service.security.EvictionRegistry
import com.adsamcik.mindlayer.service.security.RateLimiter
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

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
    private val allowlistStore: AllowlistStore = AllowlistStore(service),
    private val rateLimiter: RateLimiter = RateLimiter(),
    private val logRepository: LogRepository? = null,
    private val mlHealthRecorder: MlHealthRecorder? = null,
    private val deferredStore: DeferredStore? = null,
    private val embeddingCoordinator: EmbeddingCoordinator? = null,
    private val callbackRegistry: EvictionRegistry = EvictionRegistry(),
    private val ocrSessionManager: OcrSessionManager = OcrSessionManager(),
    private val sharedMemoryPool: com.adsamcik.mindlayer.service.ipc.SharedMemoryPool? = null,
    /**
     * Direct engine handle for the single-image `ocrImage` AIDL method
     * (v0.9). When `null` the `ocrImage` call fails with
     * [MindlayerErrorCode.SERVICE_UNAVAILABLE]. The service wires the
     * same engine instance that powers the session pipeline so the two
     * APIs share the per-engine mutex and never race for the native
     * delegate.
     */
    private val paddleOcrEngine: com.adsamcik.mindlayer.service.engine.PaddleOcrEngine? = null,
    /**
     * Structured-extraction extractor used when the caller sets
     * [com.adsamcik.mindlayer.OcrImageOptions.runLlmExtraction] = true.
     * Defaults to [com.adsamcik.mindlayer.service.engine.NoOpOcrLlmExtractor]
     * so the binder is constructible in tests; production
     * [MindlayerMlService] injects [com.adsamcik.mindlayer.service.engine.LiteRtLmGemmaOcrExtractorProduction].
     */
    private val ocrLlmExtractor: com.adsamcik.mindlayer.service.engine.OcrLlmExtractor =
        com.adsamcik.mindlayer.service.engine.NoOpOcrLlmExtractor(),
) : IMindlayerService.Stub() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val diagnosticsRefreshInFlight = AtomicBoolean(false)
    private val engineWarmupInFlight = AtomicBoolean(false)

    @Volatile
    private var diagnosticsSnapshot: String = """{"status":"warming"}"""

    /**
     * Scoped key (`uid:publicRequestId`) → UID that owns the concurrency slot.
     *
     * Namespaced by UID so that two callers with colliding `requestId` values
     * (UUID collision or malicious co-signed peer) cannot collide on
     * concurrency accounting nor cancel each other's inference. See
     * `inferenceKey` for the canonical key shape.
     */
    private val activeInferenceUids = ConcurrentHashMap<String, Int>()
    private val activeInferenceOwners = ConcurrentHashMap<String, Any>()

    /**
     * v0.4 recently-completed inference tracking. Maps `scopedKey
     * (uid:requestId)` → wall-clock millis when the inference terminated.
     *
     * `cancelInferenceV2` and `submitToolResultV2` consult this map to
     * distinguish [com.adsamcik.mindlayer.CancelResult.ALREADY_FINISHED]
     * from [com.adsamcik.mindlayer.CancelResult.UNKNOWN] for callers
     * whose request just terminated. Entries older than
     * [RECENTLY_COMPLETED_RETENTION_MS] are pruned opportunistically on
     * each lookup so the map stays bounded.
     *
     * Keyed by the scoped key (uid:requestId) so cross-UID lookups stay
     * opaque — `cancelInferenceV2` builds the lookup key from
     * `(callingUid, requestId)`, never finds another UID's entry, and the
     * F-007 anti-enumeration property is preserved.
     */
    private val recentlyCompleted = ConcurrentHashMap<String, Long>()
    private val recentlyCompletedSweepCounter = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * Bound concurrent diagnostic dumps to one. Each dump holds a binder
     * thread for Room I/O — without this an authorized attacker could
     * cheaply exhaust the binder pool while still under the per-UID
     * rate-limit RPM cap (see SECURITY_REVIEW F-019).
     */
    private val diagnosticsLock = Mutex()

    /**
     * Client liveness tokens keyed by per-registration owner tokens. Multiple
     * registrations from the same UID are retained independently so a later
     * process cannot mask an earlier process's death.
     */
    private val clientDeathRecipients =
        ConcurrentHashMap<ClientRegistration, Pair<IBinder, IBinder.DeathRecipient>>()

    private val currentRegistrationByUid = ConcurrentHashMap<Int, ClientRegistration>()

    /**
     * v0.4 eviction-callback registry. Owned exclusively by this binder so
     * it can be cleared from `MindlayerMlService.onDestroy` and so that the
     * [SessionManager] eviction listener has a single dispatch target.
     *
     * Internal visibility allows the service to call [EvictionRegistry.clear]
     * during teardown.
     */
    internal val evictionRegistry = callbackRegistry


    /**
     * F-051: lifetime registerClient counter per UID. Hostile or buggy
     * clients that re-register on a tight loop are blocked once they
     * cross [MAX_REGISTRATIONS_PER_UID]. The cap is intentionally
     * generous — legitimate first-party SDKs will typically register
     * once per process; reconnect-after-rebind retries are rare.
     */
    private val registrationAttempts = ConcurrentHashMap<Int, Int>()

    companion object {
        private const val TAG = "ServiceBinder"
        /** F-051: lifetime per-UID registerClient cap. */
        const val MAX_REGISTRATIONS_PER_UID = 64

        /**
         * F-073: sentinel value written to [ServiceStatus.thermalBand] when
         * the active [ThermalPolicy] has [ThermalConfidence.INFERRED] —
         * meaning the device exposes no thermal telemetry (Android 8 / 8.1)
         * and the orchestrator is running on a conservative duty-cycle
         * variant of the policy. Encoded into the existing String field
         * so we do not have to grow [ServiceStatus]'s frozen Parcelable
         * shape (see `docs/AIDL_STABILITY.md`). Dashboard surfaces the
         * indicator by recognising this constant.
         */
        const val THERMAL_TELEMETRY_UNAVAILABLE = "UNAVAILABLE"

        /**
         * Logical API version surfaced via [getCapabilities]. Bumped whenever
         * a new method is appended to the AIDL interface. v1 was the pre-
         * `inferMulti` surface; v2 added [inferMulti]; v3 added
         * [prewarmAndAwait]; v4 added [cancelInferenceV2] +
         * [submitToolResultV2]; v5 added [getDiagnosticsTyped]; v6 added
         * [subscribeEvictionNotices] + [unsubscribeEvictionNotices].
         */
        const val CURRENT_API_VERSION = 8

        /**
         * How long after termination a scoped key remains in
         * [recentlyCompleted] for `cancelInferenceV2` /
         * `submitToolResultV2` to distinguish "already finished" from
         * "never existed". 30 s is generous; bumping further means a
         * larger steady-state map and longer-lived references to
         * terminated requestIds.
         */
        const val RECENTLY_COMPLETED_RETENTION_MS: Long = 30_000L

        /**
         * Sweep [recentlyCompleted] entries older than retention every Nth
         * lookup. Cheap amortized cost; prevents the map from growing
         * unbounded under sustained churn.
         */
        const val RECENTLY_COMPLETED_SWEEP_INTERVAL: Int = 32

        /**
         * Maximum [com.adsamcik.mindlayer.MediaPart] entries accepted in a
         * single [inferMulti] call. Today's engine consumes at most one
         * image + one audio (validator enforces this) so the effective cap
         * is `2`; once litert-lm #1874 lifts multi-image, the validator
         * loosens and this constant rises without a wire-level change.
         */
        const val MAX_MEDIA_PARTS_PER_REQUEST = 2

        /** Lower bound on the caller-supplied [prewarmAndAwait] timeout (ms). */
        const val PREWARM_AWAIT_MIN_TIMEOUT_MS: Long = 1_000L

        /**
         * Upper bound on the caller-supplied [prewarmAndAwait] timeout (ms).
         * 30 s is generous — engine init is typically 5-10 s but cold cache
         * misses on slow internal storage can stretch it. Caps higher than
         * this risk pinning a binder thread for unacceptable durations.
         */
        const val PREWARM_AWAIT_MAX_TIMEOUT_MS: Long = 30_000L

        /**
         * Capability strings the current service implementation supports.
         * Append to this set when a new feature lands; never repurpose
         * existing strings. Documented in `docs/AIDL_STABILITY.md`.
         */
        val SUPPORTED_FEATURES: Set<String> = setOf(
            com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_TYPED_ERRORS,
            com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_PIPE_PROTO_V1,
            com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_PIPE_STREAM_V1,
            com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_SHARED_MEMORY_MEDIA,
            com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_TOOL_RESULTS,
            com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_HISTORY_RECOVERY,
            com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_STRUCTURED_OUTPUT,
            com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_MEDIA_LIST,
            com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_PREWARM_AWAIT,
            com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_DETAILED_CANCEL,
            com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_TYPED_DIAGNOSTICS,
            com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_TOKEN_BATCH,
            com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_EVICTION_CALLBACK,
            com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_DEFERRED_INFERENCE,
            // Phase 2 #6: ZXing barcode anchor injected into the OCR
            // evidence package. The detector runs unconditionally
            // alongside recognition in OcrRecognitionDispatcher; the
            // capability flag advertises the surface to SDK callers
            // so they know they can rely on `barcode[FMT|VAL]` field
            // updates appearing for receipts / product captures.
            com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_OCR_BARCODE_ANCHOR,
            // Phase 2 #7: per-line bounding-box geometry surfaced on
            // OCR_FIELD_UPDATE / OCR_FIELD_LOCKED events. Pure wire-
            // shape capability — advertised unconditionally since the
            // OcrTokenStreamWriter and SDK reader handle the optional
            // box field as a no-op on receivers that don't ask for it.
            com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_OCR_BOUNDING_BOXES,
            // Phase 3 #8 (p3-health-check): lightweight ping() endpoint
            // returning a HealthCheck parcelable. Bypasses the allowlist
            // gate and charges zero rate-limit cost; co-signed peers in
            // pending-approval can use it for liveness probes.
            com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_HEALTH_CHECK,
        )

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

    private data class ClientRegistration(
        override val ownerUid: Int,
        val registrationId: String,
    ) : SessionOwnerToken

    init {
        // v0.4 eviction-callback: route every involuntary session retirement
        // to the per-UID callback registry. The listener fires AFTER
        // SessionManager has released its monitor and closed the conversation,
        // so dispatching binder transactions here cannot deadlock the eviction
        // path. Sessions with no resolved owner UID (purely internal/self-UID)
        // notify with no recipient — silent no-op.
        service.sessionManager.setEvictionListener { sessionId, ownerUid, reasonCode ->
            if (ownerUid != null) {
                evictionRegistry.notifyEviction(ownerUid, sessionId, reasonCode)
            }
        }
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
    private fun authorizeCall(): CallerIdentity = authorizeCall(cost = 1.0)

    /**
     * F-064: cost-weighted authz. Cheap status/info AIDL methods pass a
     * fractional [cost] so a polling dashboard or health monitor doesn't burn
     * budget at the documented "request" granularity. Self-UID still bypasses
     * the rate-limit gate entirely.
     */
    private fun authorizeCall(cost: Double): CallerIdentity {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) {
            return SELF_IDENTITY
        }
        // F-074: crash-loop watchdog. When the `:ml` process has restarted
        // abnormally [DEATH_COUNT_THRESHOLD] times in the rolling
        // [THROTTLE_WINDOW_MS] window, refuse external binds with the
        // typed [MindlayerErrorCode.SERVICE_THROTTLED] code so the SDK's
        // reconnect loop backs off until the cooldown expires instead of
        // hot-spinning and re-loading the 2.4 GB model into the OOM-killer's
        // jaws. Self-UID dashboard already returned above so the user can
        // still observe the throttle banner via direct file reads.
        val healthRecorder = mlHealthRecorder
        if (healthRecorder != null && healthRecorder.shouldThrottleBinds()) {
            val cooldown = healthRecorder.cooldownEndsAt()
            logRepository?.logCrashLoopThrottle(uid, cooldown)
            MindlayerLog.w(
                TAG,
                "Service throttled — refusing bind from uid=$uid (cooldownEndsAt=$cooldown)",
            )
            throw SecurityException(
                MindlayerErrorCode.wireMessage(
                    MindlayerErrorCode.SERVICE_THROTTLED,
                    "service_throttled (cooldown=$cooldown)",
                ),
            )
        }
        val identity = callerVerifier.identify(context, uid)
            ?: run {
                // F-033: no identity at all. We have no (pkg, sig) to bucket
                // on except the calling UID — throttle hostile flooders that
                // can't even produce a valid identity before we waste any more
                // work on them.
                if (!rateLimiter.tryAcquireRejected(uid)) {
                    logRepository?.logRateLimitReject(callerAidlMethodName(), uid, cost)
                    MindlayerLog.w(TAG, "Reject flood from uid=$uid (no identity)")
                    throw typedBinderException(MindlayerErrorCode.RATE_LIMITED, "Rate limit exceeded")
                }
                throw typedBinderException(
                    MindlayerErrorCode.IDENTITY_UNKNOWN,
                    "Caller identity could not be verified",
                )
            }

        // 1. Allowlist check. A previously-denied caller is rejected silently.
        val store = allowlistStore
        if (store.isDenied(identity.packageName, identity.signingCertSha256)) {
            throw typedBinderException(
                MindlayerErrorCode.ALLOWLIST_REVOKED,
                "App not authorized — approval revoked",
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
                    logRepository?.logAllowlistPendingRecorded(
                        uid = uid,
                        packageName = identity.packageName,
                        sigShaPrefix = identity.signingCertSha256.take(12),
                    )
                    MindlayerLog.w(TAG, "Blocked un-approved caller ${identity.packageName} (uid=$uid)")
                }
                throw typedBinderException(
                    MindlayerErrorCode.ALLOWLIST_PENDING,
                    "App not authorized — user approval required",
                )
        }

        // 2. Rate-limit only callers that have cleared identity + allowlist.
        //    Rejected callers are separately bounded by tryAcquireRejection()
        //    around recordPending above, preserving first-run approval
        //    discovery even though main buckets start empty.
        if (!rateLimiter.tryAcquire(uid, cost)) {
            logRepository?.logRateLimitReject(callerAidlMethodName(), uid, cost)
            MindlayerLog.w(TAG, "Rate limit exceeded for ${identity.packageName} (uid=$uid)")
            throw typedBinderException(MindlayerErrorCode.RATE_LIMITED, "Rate limit exceeded")
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
     * Unknown sessions are treated as not-owned ([SESSION_NOT_FOUND_OR_NOT_OWNED])
     * so we don't leak their existence to arbitrary callers via a 404. The
     * single shared error code is the F-008 anti-enumeration property.
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
            throw typedBinderException(
                MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
                "Session not found or not owned by caller",
            )
        }
    }

    private fun typedBinderException(code: Int, message: String): RuntimeException {
        return SecurityException(MindlayerErrorCode.wireMessage(code, message))
    }

    private fun initFailureBinderException(failure: InitFailure): RuntimeException = when (failure) {
        InitFailure.LowMemory -> typedBinderException(MindlayerErrorCode.LOW_MEMORY, "Insufficient memory")
        InitFailure.ModelMissing -> typedBinderException(MindlayerErrorCode.MODEL_MISSING, "Model file missing")
        InitFailure.IntegrityMismatch -> typedBinderException(MindlayerErrorCode.INTEGRITY_MISMATCH, "Model integrity check failed")
        is InitFailure.BackendUnavailable -> typedBinderException(MindlayerErrorCode.BACKEND_UNAVAILABLE, "Backend unavailable: ${failure.backend}")
        is InitFailure.NativeError -> typedBinderException(MindlayerErrorCode.NATIVE_ERROR, "Native engine error")
    }

    private fun initFailureForThrowable(t: Throwable): InitFailure = when (t) {
        is com.adsamcik.mindlayer.service.engine.LowMemoryException -> InitFailure.LowMemory
        is SecurityException -> InitFailure.IntegrityMismatch
        is IllegalStateException -> if (t.message?.contains("No .litertlm model files", ignoreCase = true) == true) {
            InitFailure.ModelMissing
        } else {
            InitFailure.NativeError(t.safeLabel())
        }
        else -> InitFailure.NativeError(t.safeLabel())
    }

    private fun requireDeferredStore(): DeferredStore =
        deferredStore ?: throw typedBinderException(MindlayerErrorCode.INTERNAL, "deferred_store_unavailable")

    private fun requireEmbeddingCoordinator(): EmbeddingCoordinator =
        embeddingCoordinator ?: throw typedBinderException(MindlayerErrorCode.EMBEDDING_DISABLED, "embeddings unavailable")

    /**
     * Apply [IpcInputValidator.validateEmbeddingRequest] at the AIDL
     * boundary and translate validator [IllegalArgumentException]s into the
     * service's typed wire error [MindlayerErrorCode.INVALID_REQUEST]. The
     * SDK's `withTypedErrors` chokepoint then maps this to a
     * [MindlayerException] with the same code on the client side.
     *
     * Semantic checks that depend on the loaded model (modelId match,
     * outputDim ∈ supportedDims) still happen in
     * `EmbeddingCoordinator.validateSingle`; this validator is the
     * pre-engine shape gate.
     */
    private fun validateEmbeddingRequestOrThrow(req: com.adsamcik.mindlayer.EmbeddingRequest) {
        try {
            IpcInputValidator.validateEmbeddingRequest(req)
        } catch (e: IllegalArgumentException) {
            throw typedBinderException(MindlayerErrorCode.INVALID_REQUEST, e.message ?: "invalid embedding request")
        }
    }

    private fun validateEmbeddingRequestsOrThrow(
        list: List<com.adsamcik.mindlayer.EmbeddingRequest>,
        maxBatchSize: Int,
    ) {
        try {
            IpcInputValidator.validateEmbeddingRequests(list, maxBatchSize)
        } catch (e: IllegalArgumentException) {
            // Batch-size violations map to a different typed code than per-item
            // shape errors so capability-aware SDKs can produce a precise error
            // ("you sent N requests but the cap is M") rather than the generic
            // INVALID_REQUEST.
            val msg = e.message.orEmpty()
            val code = if (msg.contains("batch too large") || msg.contains("must not be empty")) {
                MindlayerErrorCode.EMBEDDING_BATCH_TOO_LARGE
            } else if (msg.contains("text too long") || msg.contains("aggregate embedding text bytes")) {
                MindlayerErrorCode.EMBEDDING_INPUT_TOO_LONG
            } else {
                MindlayerErrorCode.INVALID_REQUEST
            }
            throw typedBinderException(code, msg.ifEmpty { "invalid embedding batch" })
        }
    }

    private fun validateEmbeddingRequestIdOrThrow(requestId: String) {
        try {
            IpcInputValidator.validateEmbeddingRequestId(requestId)
        } catch (e: IllegalArgumentException) {
            throw typedBinderException(MindlayerErrorCode.INVALID_REQUEST, e.message ?: "invalid embedding requestId")
        }
    }

    private fun requireRegisteredClient(): ClientRegistration? {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return null
        return currentRegistrationByUid[uid]
            ?: throw SecurityException("Client must call registerClient before stateful operations")
    }

    private fun requireRequestOwner(scopedKey: String): ClientRegistration? {
        val uid = Binder.getCallingUid()
        if (uid == Process.myUid()) return null
        val registration = currentRegistrationByUid[uid]
            ?: throw SecurityException("Client must call registerClient before stateful operations")
        val owner = activeInferenceOwners[scopedKey]
            ?: throw typedBinderException(
                MindlayerErrorCode.NO_ACTIVE_REQUEST,
                "No active request owned by caller",
            )
        if (owner != registration) {
            throw typedBinderException(
                MindlayerErrorCode.NO_ACTIVE_REQUEST,
                "No active request owned by caller",
            )
        }
        return registration
    }

    // ---- Client liveness ---------------------------------------------------

    /**
     * Client registers a liveness token; we [linkToDeath] so that if the
     * client process dies unexpectedly, [onClientDisconnected] fires and all
     * sessions owned by the caller's UID are torn down. Safe to call multiple
     * times — subsequent calls from the same UID replace the prior recipient.
     */
    override fun registerClient(clientToken: IBinder?) {
        // AIDL `IBinder` parameters can carry null over the wire even when
        // the Kotlin stub marks them non-null — fail closed before touching
        // the prior death-recipient state (F-042).
        val token = requireNotNull(clientToken) { "clientToken must not be null" }
        authorizeCall()
        val uid = Binder.getCallingUid()

        // F-051: the death-recipient mechanism is the wrong defence layer
        // for a hostile client, but a self-UID dashboard caller may
        // accidentally pass a system Binder (e.g. a service token from
        // another binding it holds) that never dies until the system
        // server crashes. We can't *prove* the token comes from the
        // calling process — `Binder` doesn't expose an owner UID — but we
        // can refuse two pathological cases:
        //  1. tokens that are local to *our* process (i.e. the caller is
        //     trying to register OUR own service Binder back at us, which
        //     would `binderDied` only when *our* process dies),
        //  2. tokens that already advertise an interface descriptor for a
        //     different AIDL contract than `IMindlayerService`.
        // Combined with the per-UID registration cap below, this turns an
        // edge-case foot-gun into a deterministic SecurityException.
        if (token === asBinder()) {
            throw SecurityException("clientToken must not be the service's own binder")
        }
        val descriptor = try { token.interfaceDescriptor } catch (_: Throwable) { null }
        if (descriptor != null && descriptor.isNotEmpty() &&
            descriptor != "android.os.IBinder" &&
            descriptor != IMindlayerService.DESCRIPTOR
        ) {
            // The token implements some other AIDL contract — almost
            // certainly a misuse. Reject loudly.
            MindlayerLog.w(
                TAG,
                "Rejecting registerClient: clientToken descriptor='$descriptor' " +
                    "is neither anonymous IBinder nor ${IMindlayerService.DESCRIPTOR}",
            )
            throw SecurityException("clientToken descriptor mismatch")
        }
        // Cap repeat registrations from a single UID. The map is keyed by
        // UID so a hostile caller can't grow it past one entry, but a
        // buggy client repeatedly invoking registerClient with new tokens
        // each time would still churn through linkToDeath/unlinkToDeath
        // pairs. This counter is best-effort (concurrent registrations
        // from the same UID race), but caps the worst-case cost.
        val attempts = registrationAttempts.merge(uid, 1, Int::plus) ?: 1
        if (attempts > MAX_REGISTRATIONS_PER_UID) {
            registrationAttempts[uid] = MAX_REGISTRATIONS_PER_UID
            MindlayerLog.w(
                TAG,
                "Rejecting registerClient: uid=$uid exceeded " +
                    "$MAX_REGISTRATIONS_PER_UID lifetime registrations",
            )
            throw SecurityException("registerClient cap exceeded")
        }

        val registration = ClientRegistration(uid, UUID.randomUUID().toString())

        // Build the recipient first so it can capture itself by reference.
        lateinit var recipient: IBinder.DeathRecipient
        recipient = IBinder.DeathRecipient {
            logRepository?.logBinderDeathClient(uid, registration.registrationId)
            MindlayerLog.w(TAG, "Client uid=$uid registration died; cleaning up owned sessions")
            val cur = clientDeathRecipients[registration]
            if (cur != null && cur.second === recipient) {
                clientDeathRecipients.remove(registration, cur)
            }
            if (currentRegistrationByUid.remove(uid, registration)) {
                promoteRegistrationForUid(uid)
            }
            onClientRegistrationDisconnected(registration)
        }
        try {
            // linkToDeath may throw RemoteException if the token is already
            // dead; only after a successful link do we replace any prior
            // recipient. This avoids ending up with no recipient at all if
            // the new token is dead at registration time (F-042).
            token.linkToDeath(recipient, 0)
            clientDeathRecipients[registration] = token to recipient
            currentRegistrationByUid[uid] = registration
        } catch (e: RemoteException) {
            // Token already dead — run cleanup immediately. Don't disturb
            // any existing live recipient for this UID.
            MindlayerLog.w(TAG, "Client token for uid=$uid already dead at registration")
            onClientRegistrationDisconnected(registration)
        }
    }

    private fun promoteRegistrationForUid(uid: Int) {
        val replacement = clientDeathRecipients.keys.firstOrNull { it.ownerUid == uid }
        if (replacement != null) {
            currentRegistrationByUid[uid] = replacement
        }
    }

    private fun onClientRegistrationDisconnected(registration: ClientRegistration) {
        val activeKeys = activeInferenceOwners.entries
            .filter { it.value == registration }
            .map { it.key }

        activeKeys.forEach { key ->
            MindlayerLog.i(TAG, "Cancelling active inference for disconnected registration")
            orchestrator.cancelInference(key)
        }

        val orphaned = orchestrator.closeAllOwnedBy(registration)
        if (orphaned.isNotEmpty()) {
            MindlayerLog.i(TAG, "Released ${orphaned.size} session(s) for disconnected registration")
        }
        ocrSessionManager.closeAllForUid(registration.ownerUid)
        embeddingCoordinator?.cancelAllForUid(registration.ownerUid)
    }

    /**
     * Tears down every session owned by [uid] and releases any lingering
     * concurrency slots. Invoked by the [IBinder.DeathRecipient] registered
     * in [registerClient], and by tests. Idempotent.
     */
    fun onClientDisconnected(uid: Int) {
        val prefix = "$uid:"
        val activeKeys = activeInferenceUids.keys
            .filter { it.startsWith(prefix) }

        activeKeys.forEach { key ->
            MindlayerLog.i(TAG, "Cancelling active inference for disconnected uid=$uid")
            orchestrator.cancelInference(key)
            activeInferenceOwners.remove(key)
        }

        val orphaned = orchestrator.closeAllOwnedByUid(uid)
        if (orphaned.isNotEmpty()) {
            MindlayerLog.i(TAG, "Released ${orphaned.size} session(s) for uid=$uid")
        }
        ocrSessionManager.closeAllForUid(uid)
        embeddingCoordinator?.cancelAllForUid(uid)
    }

    private fun closeAllOwnedByRevokedUid(uid: Int) {
        val prefix = "$uid:"
        val activeKeys = activeInferenceUids.keys
            .filter { it.startsWith(prefix) }

        activeKeys.forEach { key ->
            MindlayerLog.i(TAG, "Cancelling active inference for revoked uid=$uid")
            orchestrator.cancelInference(key)
            activeInferenceOwners.remove(key)
        }

        val revoked = orchestrator.closeAllOwnedByUidForRevoke(uid)
        if (revoked.isNotEmpty()) {
            MindlayerLog.i(TAG, "Revoked ${revoked.size} session(s) for uid=$uid")
        }
        ocrSessionManager.closeAllForUid(uid)
        embeddingCoordinator?.cancelAllForUid(uid)
    }

    // ---- Session management ------------------------------------------------

    override fun createSession(config: SessionConfig): String {
        val identity = authorizeCall()
        val uid = Binder.getCallingUid()
        val ownerToken = requireRegisteredClient()
        // M1: validate at the binder boundary BEFORE any expensive native
        // engine warmup. Otherwise an unprivileged-but-allowlisted caller
        // could trigger a multi-second engine init with arbitrary `backend`
        // / `maxTokens` values and only afterwards hit the SessionManager
        // validator.
        validateSessionConfigBoundary(config)
        // Validate every client-supplied string in SessionConfig against
        // explicit byte budgets and identifier shape constraints. See
        // IpcInputValidator for the rules.
        try {
            IpcInputValidator.validateSessionConfig(config)
        } catch (e: IllegalArgumentException) {
            throw typedBinderException(
                MindlayerErrorCode.INVALID_SESSION_CONFIG,
                "Invalid SessionConfig: ${e.message}",
            )
        }
        // External callers may NOT choose their own sessionId — that lets a
        // co-signed peer overwrite a victim's session by guessing/harvesting
        // the id (see SECURITY_REVIEW F-008). Self-UID dashboard keeps full
        // control because it owns every session it creates.
        val safeConfig = if (uid == Process.myUid()) {
            config
        } else if (config.sessionId != null) {
            MindlayerLog.w(
                TAG,
                "Ignoring client-supplied sessionId from ${identity.packageName} " +
                    "(only self-UID may pin sessionIds)",
            )
            config.copy(sessionId = null)
        } else {
            config
        }
        MindlayerLog.d(TAG, "createSession from ${identity.packageName}")
        if (!engineManager.isInitialized) {
            runBlocking {
                startEngineWarmup(
                    preferredBackend = safeConfig.backend,
                    maxTokens = safeConfig.maxTokens,
                )
                when (val state = engineManager.awaitReady()) {
                    is EngineState.Ready -> Unit
                    is EngineState.Failed -> throw initFailureBinderException(state.cause)
                    EngineState.Idle, EngineState.Initializing -> throw typedBinderException(
                        MindlayerErrorCode.ENGINE_INITIALIZING,
                        "engine_initializing",
                    )
                }
            }
        }
        return try {
            orchestrator.createSession(safeConfig, ownerToken ?: uid)
        } catch (e: com.adsamcik.mindlayer.service.engine.EngineNotReadyException) {
            // F-018: typed engine-init-in-progress signal. AIDL marshals
            // a prefixed Binder exception losslessly so the SDK can detect
            // the code and apply backoff retry without parsing free-form text.
            throw typedBinderException(
                MindlayerErrorCode.ENGINE_INITIALIZING,
                "engine_initializing",
            )
        } catch (e: com.adsamcik.mindlayer.service.engine.LowMemoryException) {
            // F-071: typed low-memory signal. The bg init job hit the
            // memory pre-flight check and refused to load — propagate
            // the concrete numbers so a diagnostic UI can show how short
            // the device is. Distinct code from ENGINE_LOAD_FAILED so
            // the SDK retry budget can treat it as terminal.
            throw typedBinderException(
                MindlayerErrorCode.LOW_MEMORY,
                "Insufficient memory: availMb=${e.availMb} requiredMb=${e.requiredMb}",
            )
        } catch (e: SessionQuotaExceededException) {
            throw typedBinderException(
                MindlayerErrorCode.SESSION_QUOTA_EXHAUSTED,
                "Session quota exhausted",
            )
        } catch (e: SessionResourceExhaustedException) {
            throw typedBinderException(
                MindlayerErrorCode.MEMORY_PRESSURE,
                "Memory pressure: cannot create session",
            )
        } catch (e: com.adsamcik.mindlayer.service.engine.ContextOverflowException) {
            // F-072: service-owned prompt overhead (system prompt + tool
            // definitions + structured-output schema) already exhausts
            // the device-tier KV budget — no room for any user input.
            // The wire message body carries `remainingTokens=N` so the
            // SDK can show the caller how much room is actually free.
            throw typedBinderException(
                MindlayerErrorCode.INPUT_EXCEEDS_CONTEXT,
                e.wireMessage,
            )
        } catch (e: IllegalArgumentException) {
            // Privacy: orchestrator's IllegalArgumentException can carry
            // internal detail like "internal: KV cache size 1234 exceeds
            // tier limit". The wire-stable code (3002 / INVALID_SESSION_CONFIG)
            // already conveys what the SDK side needs; we deliberately drop
            // `e.message` so the AIDL boundary message is just the
            // human-readable label. The orchestrator log line still records
            // the safe label (class chain only) for diagnostics.
            MindlayerLog.w(TAG, "createSession rejected: ${e.safeLabel()}")
            throw typedBinderException(
                MindlayerErrorCode.INVALID_SESSION_CONFIG,
                "Invalid SessionConfig",
            )
        } catch (e: IllegalStateException) {
            val initFailure = engineManager.lastInitFailure
            if (initFailure != null) {
                throw initFailureBinderException(initFailure)
            }
            if (e.message == "engine_initializing") {
                throw typedBinderException(MindlayerErrorCode.ENGINE_INITIALIZING, "engine_initializing")
            }
            MindlayerLog.w(TAG, "createSession rejected: ${e.safeLabel()}")
            throw initFailureBinderException(initFailureForThrowable(e))
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
            // Privacy: only the field label (e.g. "maxTokens", "samplerTopK")
            // crosses the wire — production validators throw with field-name
            // strings, never values. Wrap in the typed wire format so the
            // SDK side can decode `INVALID_SESSION_CONFIG` consistently with
            // the orchestrator-translation path; older raw `SecurityException`
            // throws were silently dropped by `assertCode` because they had
            // no MLERR prefix to parse.
            MindlayerLog.w(TAG, "SessionConfig boundary validation failed: ${e.message}")
            throw typedBinderException(
                MindlayerErrorCode.INVALID_SESSION_CONFIG,
                "Invalid SessionConfig",
            )
        }
    }

    /**
     * Boundary validation for [RequestMeta] is handled by
     * [IpcInputValidator.validateRequestMeta]; the legacy private copy
     * was removed when the validator centralised AIDL ingress checks
     * (b15b656). Priority bound enforcement (-10..10) lives there now.
     */
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
        requireRegisteredClient()
        try {
            IpcInputValidator.validateId(sessionId, "sessionId")
        } catch (e: IllegalArgumentException) {
            throw typedBinderException(
                MindlayerErrorCode.INVALID_REQUEST,
                "Invalid sessionId: ${e.message}",
            )
        }
        requireOwnership(sessionId)
        MindlayerLog.d(TAG, "destroySession", sessionId = sanitizeLogField(sessionId))
        orchestrator.destroySession(sessionId)
    }

    override fun getSessionInfo(sessionId: String): SessionInfo? {
        authorizeCall()
        try {
            IpcInputValidator.validateId(sessionId, "sessionId")
        } catch (e: IllegalArgumentException) {
            throw typedBinderException(
                MindlayerErrorCode.INVALID_REQUEST,
                "Invalid sessionId: ${e.message}",
            )
        }
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid()) {
            // Anti-enumeration: same code for "no such session" and "exists
            // but owned by another UID". Throw the typed error instead of
            // returning null so the SDK declaration (non-null SessionInfo)
            // is honoured and consumers can react via MindlayerException.
            val owner = orchestrator.getSessionOwner(sessionId)
                ?: throw typedBinderException(
                    MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
                    "Session not found or not owned by caller",
                )
            if (owner != uid) {
                throw typedBinderException(
                    MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
                    "Session not found or not owned by caller",
                )
            }
        }
        return orchestrator.getSessionInfo(sessionId)
            ?: throw typedBinderException(
                MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
                "Session not found or not owned by caller",
            )
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
            val ownerToken = requireRegisteredClient()

            // Validate every client-supplied identifier and string at AIDL
            // ingress so the downstream code never sees malformed values.
            try {
                IpcInputValidator.validateRequestMeta(meta)
                image?.let { IpcInputValidator.validateImageTransfer(it, MAX_MEDIA_BYTES) }
                audio?.let { IpcInputValidator.validateAudioTransfer(it, MAX_MEDIA_BYTES) }
                // Inbound media transfer requestId must agree with meta.requestId
                // — defends against staging-cleanup keying mismatches.
                require(image == null || image.requestId == meta.requestId) {
                    "ImageTransfer.requestId must equal RequestMeta.requestId"
                }
                require(audio == null || audio.requestId == meta.requestId) {
                    "AudioTransfer.requestId must equal RequestMeta.requestId"
                }
            } catch (e: IllegalArgumentException) {
                throw typedBinderException(
                    MindlayerErrorCode.INVALID_REQUEST,
                    "Invalid request: ${e.message}",
                )
            }

            // Caller must own the target session.
            requireOwnership(meta.sessionId)

            if (!rateLimiter.beginInference(uid)) {
                logRepository?.logRateLimitReject(
                    method = "infer",
                    uid = uid,
                    cost = 1.0,
                    requestId = meta.requestId,
                    sessionId = meta.sessionId,
                )
                MindlayerLog.w(
                    TAG,
                    "Concurrent inference limit exceeded for ${identity.packageName}",
                )
                throw typedBinderException(
                    MindlayerErrorCode.CONCURRENT_LIMIT,
                    "Concurrent inference limit exceeded for ${identity.packageName}",
                )
            }

            // L11: identifiers were already shape-checked by IpcInputValidator,
            // but use sanitizeLogField as defense-in-depth in case a future
            // refactor relaxes the regex.
            MindlayerLog.d(
                TAG,
                "infer request from ${identity.packageName}",
                requestId = sanitizeLogField(meta.requestId),
                sessionId = sanitizeLogField(meta.sessionId),
            )
            // Namespace the active-request map by UID so two callers that coin the
            // same requestId (UUID collision / malicious) cannot collide on our
            // concurrency accounting, nor cancel each other's inference. The
            // public requestId still flows to the SDK in stream events; the
            // scoped key is purely an in-process invariant.
            val scopedKey = inferenceKey(uid, meta.requestId)
            // F-028: putIfAbsent guarantees a duplicate (uid, requestId) is
            // rejected up-front rather than overwriting the slot accounting.
            if (activeInferenceUids.putIfAbsent(scopedKey, uid) != null) {
                rateLimiter.endInference(uid)
                throw typedBinderException(
                    MindlayerErrorCode.DUPLICATE_REQUEST,
                    "Duplicate requestId: ${meta.requestId}",
                )
            }
            if (ownerToken != null) {
                activeInferenceOwners[scopedKey] = ownerToken
            }
            try {
                orchestrator.infer(scopedKey, meta, image, audio, eventWriteEnd) {
                    activeInferenceOwners.remove(scopedKey)
                    if (activeInferenceUids.remove(scopedKey) != null) {
                        rateLimiter.endInference(uid)
                    }
                    markRecentlyCompleted(scopedKey)
                }
                handedOff = true
            } catch (e: com.adsamcik.mindlayer.service.engine.ContextOverflowException) {
                // F-072: budget check tripped synchronously at the orchestrator
                // gate; the request never reached `scope.launch`. Release the
                // concurrency slot, drop ownership, and translate to a typed
                // wire-prefixed SecurityException so the SDK surfaces the
                // typed code + `remainingTokens=N` payload.
                activeInferenceOwners.remove(scopedKey)
                if (activeInferenceUids.remove(scopedKey) != null) {
                    rateLimiter.endInference(uid)
                }
                markRecentlyCompleted(scopedKey)
                throw typedBinderException(
                    MindlayerErrorCode.INPUT_EXCEEDS_CONTEXT,
                    e.wireMessage,
                )
            } catch (e: com.adsamcik.mindlayer.service.ipc.SharedMemoryPoolExhaustedException) {
                // F-076: SharedMemoryPool bounds gate tripped at the
                // synchronous orchestrator pre-flight. Release the
                // concurrency slot and translate to a typed wire-prefixed
                // SecurityException so the SDK can surface
                // TRANSIENT_RESOURCE_EXHAUSTED with the retry-after hint.
                activeInferenceOwners.remove(scopedKey)
                if (activeInferenceUids.remove(scopedKey) != null) {
                    rateLimiter.endInference(uid)
                }
                markRecentlyCompleted(scopedKey)
                throw typedBinderException(
                    MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED,
                    "shm_pool_exhausted reason=${e.reason} retryAfterMs=${e.retryAfterMs}",
                )
            } catch (t: Throwable) {
                activeInferenceOwners.remove(scopedKey)
                if (activeInferenceUids.remove(scopedKey) != null) {
                    rateLimiter.endInference(uid)
                }
                markRecentlyCompleted(scopedKey)
                throw t
            }
        } finally {
            if (!handedOff) {
                // M11: close any FDs we duped during the AIDL transaction so
                // they don't leak when validation/ownership/rate-limit rejects
                // the call before orchestrator.infer takes ownership.
                try { eventWriteEnd.close() } catch (_: Exception) {}
                try { image?.source?.close() } catch (_: Exception) {}
                try { audio?.source?.close() } catch (_: Exception) {}
            }
        }
    }

    /**
     * v0.4 multimodal inference. Successor to [infer] — accepts an ordered
     * list of [com.adsamcik.mindlayer.MediaPart] so future engines can
     * consume multiple images / video / documents without another wire-break.
     *
     * Today's engine constraint (see `MediaPart` KDoc): the validator
     * rejects multi-image, multi-audio, and any
     * [com.adsamcik.mindlayer.MediaPart.KIND_VIDEO] /
     * [com.adsamcik.mindlayer.MediaPart.KIND_DOCUMENT]. Within those limits
     * the binder picks the first image and first audio (preserving ordering
     * by extracting in list order) and dispatches to the existing
     * orchestrator. When litert-lm #1874 lifts multi-image, the orchestrator
     * gains an ordered-list path and the validator caps loosen — wire shape
     * doesn't change.
     */
    override fun inferMulti(
        meta: RequestMeta,
        media: List<com.adsamcik.mindlayer.MediaPart>?,
        eventWriteEnd: ParcelFileDescriptor,
    ) {
        val parts = media ?: emptyList()
        var handedOff = false
        try {
            val identity = authorizeCall()
            val uid = Binder.getCallingUid()
            val ownerToken = requireRegisteredClient()

            try {
                IpcInputValidator.validateRequestMeta(meta)
                IpcInputValidator.validateMediaParts(
                    parts,
                    maxPerPartBytes = MAX_MEDIA_BYTES,
                    maxParts = MAX_MEDIA_PARTS_PER_REQUEST,
                )
                for ((i, p) in parts.withIndex()) {
                    require(p.requestId == meta.requestId) {
                        "media[$i].requestId must equal RequestMeta.requestId"
                    }
                }
            } catch (e: IllegalArgumentException) {
                throw typedBinderException(
                    MindlayerErrorCode.INVALID_REQUEST,
                    "Invalid request: ${e.message}",
                )
            }

            // Caller must own the target session.
            requireOwnership(meta.sessionId)

            if (!rateLimiter.beginInference(uid)) {
                MindlayerLog.w(
                    TAG,
                    "Concurrent inference limit exceeded for ${identity.packageName}",
                )
                throw typedBinderException(
                    MindlayerErrorCode.CONCURRENT_LIMIT,
                    "Concurrent inference limit exceeded for ${identity.packageName}",
                )
            }

            MindlayerLog.d(
                TAG,
                "inferMulti request from ${identity.packageName} (parts=${parts.size})",
                requestId = meta.requestId,
                sessionId = meta.sessionId,
            )

            val scopedKey = inferenceKey(uid, meta.requestId)
            if (activeInferenceUids.putIfAbsent(scopedKey, uid) != null) {
                rateLimiter.endInference(uid)
                throw typedBinderException(
                    MindlayerErrorCode.DUPLICATE_REQUEST,
                    "Duplicate requestId: ${meta.requestId}",
                )
            }
            if (ownerToken != null) {
                activeInferenceOwners[scopedKey] = ownerToken
            }

            // Decompose ordered List<MediaPart> into the (image, audio) pair
            // the orchestrator currently consumes. Validator already enforces
            // ≤1 of each kind, so firstOrNull is exhaustive. Order between the
            // two collapses to image-first per legacy behavior; documented in
            // MediaPart KDoc as a temporary engine constraint.
            val imagePart = parts.firstOrNull { it.kind == com.adsamcik.mindlayer.MediaPart.KIND_IMAGE }
            val audioPart = parts.firstOrNull { it.kind == com.adsamcik.mindlayer.MediaPart.KIND_AUDIO }
            val image: ImageTransfer? = imagePart?.let { mediaPartToImageTransfer(it) }
            val audio: AudioTransfer? = audioPart?.let { mediaPartToAudioTransfer(it) }

            try {
                orchestrator.infer(scopedKey, meta, image, audio, eventWriteEnd) {
                    activeInferenceOwners.remove(scopedKey)
                    if (activeInferenceUids.remove(scopedKey) != null) {
                        rateLimiter.endInference(uid)
                    }
                    markRecentlyCompleted(scopedKey)
                }
                handedOff = true
            } catch (e: com.adsamcik.mindlayer.service.engine.ContextOverflowException) {
                // F-072: same translation as [infer]. inferMulti shares the
                // orchestrator dispatch path, so the synchronous gate fires
                // identically when MediaPart-derived (image,audio) push the
                // turn over the KV ceiling.
                activeInferenceOwners.remove(scopedKey)
                if (activeInferenceUids.remove(scopedKey) != null) {
                    rateLimiter.endInference(uid)
                }
                markRecentlyCompleted(scopedKey)
                throw typedBinderException(
                    MindlayerErrorCode.INPUT_EXCEEDS_CONTEXT,
                    e.wireMessage,
                )
            } catch (e: com.adsamcik.mindlayer.service.ipc.SharedMemoryPoolExhaustedException) {
                // F-076: same translation as [infer]. The bounds gate runs
                // inside the same orchestrator pre-flight regardless of
                // whether the request arrived via the legacy infer() path
                // or the v0.4 inferMulti() / MediaPart path.
                activeInferenceOwners.remove(scopedKey)
                if (activeInferenceUids.remove(scopedKey) != null) {
                    rateLimiter.endInference(uid)
                }
                markRecentlyCompleted(scopedKey)
                throw typedBinderException(
                    MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED,
                    "shm_pool_exhausted reason=${e.reason} retryAfterMs=${e.retryAfterMs}",
                )
            } catch (t: Throwable) {
                activeInferenceOwners.remove(scopedKey)
                if (activeInferenceUids.remove(scopedKey) != null) {
                    rateLimiter.endInference(uid)
                }
                markRecentlyCompleted(scopedKey)
                throw t
            }
        } finally {
            if (!handedOff) {
                try { eventWriteEnd.close() } catch (_: Exception) {}
                val sources = java.util.Collections.newSetFromMap(
                    java.util.IdentityHashMap<ParcelFileDescriptor, Boolean>(),
                )
                parts.forEach { sources.add(it.source) }
                sources.forEach { source ->
                    try { source.close() } catch (_: Exception) {}
                }
            }
        }
    }


    override fun inferDeferred(
        meta: RequestMeta,
        media: List<com.adsamcik.mindlayer.MediaPart>?,
    ): DeferredHandle {
        val identity = authorizeCall()
        val uid = Binder.getCallingUid()
        val ownerToken = requireRegisteredClient()
        val parts = media ?: emptyList()
        try {
            IpcInputValidator.validateRequestMeta(meta)
            IpcInputValidator.validateMediaParts(
                parts,
                maxPerPartBytes = MAX_MEDIA_BYTES,
                maxParts = MAX_MEDIA_PARTS_PER_REQUEST,
            )
        } catch (e: IllegalArgumentException) {
            throw typedBinderException(MindlayerErrorCode.INVALID_REQUEST, "Invalid request: ${e.message}")
        }
        requireOwnership(meta.sessionId)

        if (!rateLimiter.beginInference(uid)) {
            throw typedBinderException(
                MindlayerErrorCode.CONCURRENT_LIMIT,
                "Concurrent inference limit exceeded for ${identity.packageName}",
            )
        }

        val requestId = UUID.randomUUID().toString()
        val deferredMeta = meta.copy(requestId = requestId)
        val scopedKey = inferenceKey(uid, requestId)
        val handle = try {
            runBlocking { requireDeferredStore().create(uid, requestId, deferredMeta, parts.size) }
                ?: run {
                    rateLimiter.endInference(uid)
                    throw typedBinderException(
                        MindlayerErrorCode.DEFERRED_QUOTA_EXHAUSTED,
                        "deferred quota exhausted",
                    )
                }
        } catch (t: Throwable) {
            if (t is SecurityException) throw t
            rateLimiter.endInference(uid)
            throw t
        }

        if (activeInferenceUids.putIfAbsent(scopedKey, uid) != null) {
            rateLimiter.endInference(uid)
            throw typedBinderException(MindlayerErrorCode.DUPLICATE_REQUEST, "Duplicate requestId: $requestId")
        }
        if (ownerToken != null) activeInferenceOwners[scopedKey] = ownerToken

        val imagePart = parts.firstOrNull { it.kind == com.adsamcik.mindlayer.MediaPart.KIND_IMAGE }
        val audioPart = parts.firstOrNull { it.kind == com.adsamcik.mindlayer.MediaPart.KIND_AUDIO }
        val image = imagePart?.let { mediaPartToImageTransfer(it.copy(requestId = requestId)) }
        val audio = audioPart?.let { mediaPartToAudioTransfer(it.copy(requestId = requestId)) }
        val pipe = ParcelFileDescriptor.createPipe()
        val readEnd = pipe[0]
        val writeEnd = pipe[1]
        val released = AtomicBoolean(false)
        fun releaseSlot() {
            if (released.compareAndSet(false, true)) {
                activeInferenceOwners.remove(scopedKey)
                if (activeInferenceUids.remove(scopedKey) != null) {
                    rateLimiter.endInference(uid)
                }
                markRecentlyCompleted(scopedKey)
            }
        }

        // H-D1: mirror `inferMulti`'s `handedOff` pattern. The pipe + media
        // resources are owned by *this* binder thread until the dispatch
        // coroutine successfully launches via `scope.launch { ... }`; only
        // after that does the orchestrator coroutine become responsible
        // for `writeEnd` and the SharedMemory-staged source PFDs. On any
        // synchronous failure (preflight gate throws, putIfAbsent races,
        // submission-side OOM before `scope.launch` returns) we clean up
        // here. The matching SDK-side cleanup of caller-owned source PFDs
        // lives in `Mindlayer.chatDeferred()` (H-D2).
        var handedOff = false
        // M-D2: map known synchronous preflight exceptions to their typed
        // wire codes. Mirrors the translation `inferMulti` applies at
        // ServiceBinder.kt:1157-1184 — without this, callers see a
        // generic `INTERNAL` and lose the retry / truncation hints.
        try {
            try {
                orchestrator.infer(scopedKey, deferredMeta, image, audio, writeEnd) { releaseSlot() }
            } catch (e: com.adsamcik.mindlayer.service.engine.ContextOverflowException) {
                releaseSlot()
                runCatching {
                    runBlocking {
                        requireDeferredStore().completeFailed(
                            requestId,
                            uid,
                            MindlayerErrorCode.INPUT_EXCEEDS_CONTEXT,
                            MindlayerErrorCode.nameOf(MindlayerErrorCode.INPUT_EXCEEDS_CONTEXT),
                        )
                    }
                }
                throw typedBinderException(MindlayerErrorCode.INPUT_EXCEEDS_CONTEXT, e.wireMessage)
            } catch (e: com.adsamcik.mindlayer.service.ipc.SharedMemoryPoolExhaustedException) {
                releaseSlot()
                runCatching {
                    runBlocking {
                        requireDeferredStore().completeFailed(
                            requestId,
                            uid,
                            MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED,
                            MindlayerErrorCode.nameOf(MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED),
                        )
                    }
                }
                throw typedBinderException(
                    MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED,
                    "shm_pool_exhausted reason=${e.reason} retryAfterMs=${e.retryAfterMs}",
                )
            } catch (t: Throwable) {
                releaseSlot()
                runCatching {
                    runBlocking {
                        requireDeferredStore().completeFailed(
                            requestId,
                            uid,
                            MindlayerErrorCode.INTERNAL,
                            MindlayerErrorCode.nameOf(MindlayerErrorCode.INTERNAL) ?: t.safeLabel(),
                        )
                    }
                }
                throw t
            }

            scope.launch(Dispatchers.IO) {
                var terminalStatus = DeferredResult.FAILED
                var completionApplied = false
                try {
                    val collected = collectDeferredPipe(readEnd)
                    terminalStatus = collected.status
                    completionApplied = when (collected.status) {
                        DeferredResult.READY -> requireDeferredStore().completeReady(requestId, uid, collected.text, collected.metrics)
                        DeferredResult.CANCELLED -> requireDeferredStore().completeCancelled(requestId, uid)
                        else -> requireDeferredStore().completeFailed(
                            requestId,
                            uid,
                            collected.errorCodeInt.ifBlankCode(),
                            collected.errorCodeName ?: MindlayerErrorCode.nameOf(collected.errorCodeInt.ifBlankCode()),
                        )
                    }
                    if (completionApplied) {
                        logRepository?.logDeferredComplete(requestId, meta.sessionId, terminalStatus)
                        mlHealthRecorder?.recordDeferredCompletion()
                    } else {
                        MindlayerLog.i(TAG, "Dropping late deferred completion for requestId=$requestId")
                    }
                } catch (t: Throwable) {
                    releaseSlot()
                    val label = t.safeLabel()
                    terminalStatus = DeferredResult.FAILED
                    completionApplied = runCatching {
                        requireDeferredStore().completeFailed(
                            requestId,
                            uid,
                            MindlayerErrorCode.INTERNAL,
                            MindlayerErrorCode.nameOf(MindlayerErrorCode.INTERNAL) ?: label,
                        )
                    }.getOrDefault(false)
                    if (completionApplied) {
                        logRepository?.logDeferredComplete(requestId, meta.sessionId, terminalStatus)
                    } else {
                        MindlayerLog.i(TAG, "Dropping late deferred failure for requestId=$requestId")
                    }
                } finally {
                    releaseSlot()
                    try { readEnd.close() } catch (_: Exception) { }
                    if (completionApplied) {
                        evictionRegistry.notifyDeferredComplete(uid, requestId, terminalStatus)
                    }
                }
            }
            handedOff = true
        } finally {
            if (!handedOff) {
                try { writeEnd.close() } catch (_: Exception) {}
                try { readEnd.close() } catch (_: Exception) {}
                val sources = java.util.Collections.newSetFromMap(
                    java.util.IdentityHashMap<ParcelFileDescriptor, Boolean>(),
                )
                parts.forEach { sources.add(it.source) }
                sources.forEach { source ->
                    try { source.close() } catch (_: Exception) {}
                }
            }
        }
        logRepository?.logDeferredSubmit(requestId, meta.sessionId, parts.size)
        mlHealthRecorder?.recordDeferredSubmit()
        return handle
    }

    override fun fetchDeferredResult(requestId: String): DeferredResult {
        authorizeCall()
        try {
            IpcInputValidator.validateId(requestId, "requestId")
        } catch (e: IllegalArgumentException) {
            throw typedBinderException(MindlayerErrorCode.INVALID_REQUEST, "Invalid requestId: ${e.message}")
        }
        val uid = Binder.getCallingUid()
        val result = runBlocking { requireDeferredStore().fetch(uid, requestId) }
        // M-D8: when fetch transitions a row to EXPIRED, emit a distinct
        // log event so dashboard filters can distinguish "completed and
        // unfetched" from "never fetched in time".
        if (result.status == DeferredResult.EXPIRED) {
            logRepository?.logDeferredExpired(requestId)
        }
        logRepository?.logDeferredFetch(requestId, result.status)
        return result
    }

    override fun cancelDeferredInference(requestId: String): com.adsamcik.mindlayer.CancelResult {
        authorizeCall()
        try {
            IpcInputValidator.validateId(requestId, "requestId")
        } catch (e: IllegalArgumentException) {
            throw typedBinderException(MindlayerErrorCode.INVALID_REQUEST, "Invalid requestId: ${e.message}")
        }
        val uid = Binder.getCallingUid()
        val outcome = runBlocking { requireDeferredStore().cancel(uid, requestId) }
        if (outcome == com.adsamcik.mindlayer.CancelResult.CANCELLED) {
            orchestrator.cancelInference(inferenceKey(uid, requestId))
        }
        logRepository?.logDeferredCancel(requestId, outcome)
        return com.adsamcik.mindlayer.CancelResult(outcome = outcome)
    }

    override fun acknowledgeDeferredResult(requestId: String) {
        authorizeCall()
        try {
            IpcInputValidator.validateId(requestId, "requestId")
        } catch (e: IllegalArgumentException) {
            throw typedBinderException(MindlayerErrorCode.INVALID_REQUEST, "Invalid requestId: ${e.message}")
        }
        runBlocking { requireDeferredStore().acknowledge(Binder.getCallingUid(), requestId) }
    }

    private data class DeferredCollected(
        val status: Int,
        val text: String = "",
        val metrics: Bundle? = null,
        val errorCodeInt: Int = MindlayerErrorCode.INTERNAL,
        val errorCodeName: String? = MindlayerErrorCode.nameOf(MindlayerErrorCode.INTERNAL),
    )

    private fun Int.ifBlankCode(): Int = if (this == 0) MindlayerErrorCode.INTERNAL else this

    private fun collectDeferredPipe(readEnd: ParcelFileDescriptor): DeferredCollected {
        val json = Json { ignoreUnknownKeys = true }
        val text = StringBuilder()
        var metrics: Bundle? = null
        ParcelFileDescriptor.AutoCloseInputStream(readEnd).use { input ->
            while (true) {
                val header = ByteArray(4)
                var read = 0
                while (read < 4) {
                    val n = input.read(header, read, 4 - read)
                    if (n < 0) {
                        if (text.isNotEmpty()) return DeferredCollected(DeferredResult.READY, text.toString(), metrics)
                        throw EOFException("deferred pipe closed before terminal frame")
                    }
                    read += n
                }
                val len = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).int
                if (len <= 0 || len > 1_048_576) {
                    return DeferredCollected(DeferredResult.FAILED, errorCodeInt = MindlayerErrorCode.INTERNAL)
                }
                val payload = ByteArray(len)
                var offset = 0
                while (offset < len) {
                    val n = input.read(payload, offset, len - offset)
                    if (n < 0) throw EOFException("deferred pipe closed mid-frame")
                    offset += n
                }
                val obj = json.parseToJsonElement(payload.decodeToString()).jsonObject
                val type = obj["type"]?.jsonPrimitive?.content ?: continue
                val eventPayload = obj["payload"] as? JsonObject ?: JsonObject(emptyMap())
                when (type) {
                    com.adsamcik.mindlayer.shared.StreamEventType.TOKEN_DELTA -> {
                        eventPayload["text"]?.jsonPrimitive?.content?.let { text.append(it) }
                    }
                    com.adsamcik.mindlayer.shared.StreamEventType.TOKEN_DELTA_BATCH -> {
                        val values = eventPayload["texts"] as? JsonArray
                        values?.forEach { text.append(it.jsonPrimitive.content) }
                    }
                    com.adsamcik.mindlayer.shared.StreamEventType.METRICS -> {
                        metrics = Bundle().apply {
                            for ((key, value) in eventPayload) {
                                value.jsonPrimitive.intOrNull?.let { putInt(key, it) }
                                    ?: value.jsonPrimitive.longOrNull?.let { putLong(key, it) }
                            }
                        }
                    }
                    com.adsamcik.mindlayer.shared.StreamEventType.ERROR -> {
                        val codeInt = eventPayload["codeInt"]?.jsonPrimitive?.intOrNull ?: MindlayerErrorCode.INTERNAL
                        val codeName = eventPayload["code"]?.jsonPrimitive?.content
                            ?: MindlayerErrorCode.nameOf(codeInt)
                        return DeferredCollected(DeferredResult.FAILED, errorCodeInt = codeInt, errorCodeName = codeName)
                    }
                    com.adsamcik.mindlayer.shared.StreamEventType.DONE -> {
                        val finish = eventPayload["finish_reason"]?.jsonPrimitive?.content
                        return if (finish == "cancelled") {
                            DeferredCollected(DeferredResult.CANCELLED)
                        } else {
                            DeferredCollected(DeferredResult.READY, text.toString(), metrics)
                        }
                    }
                }
            }
        }
    }
    /**
     * Convert a [com.adsamcik.mindlayer.MediaPart] of [com.adsamcik.mindlayer.MediaPart.KIND_IMAGE]
     * to the legacy [ImageTransfer] shape consumed by the orchestrator. Truncates
     * `payloadBytes: Long` → `Int` — validator already capped this at [MAX_MEDIA_BYTES]
     * so the cast is safe.
     */
    private fun mediaPartToImageTransfer(p: com.adsamcik.mindlayer.MediaPart): ImageTransfer =
        ImageTransfer(
            requestId = p.requestId,
            width = p.width,
            height = p.height,
            pixelFormat = p.pixelFormat,
            rowStride = p.rowStride,
            payloadBytes = p.payloadBytes.toInt(),
            source = p.source,
            isSharedMemory = p.isSharedMemory,
            mimeType = p.mimeType,
        )

    private fun mediaPartToAudioTransfer(p: com.adsamcik.mindlayer.MediaPart): AudioTransfer =
        AudioTransfer(
            requestId = p.requestId,
            mimeType = p.mimeType ?: "audio/wav",
            source = p.source,
            isSharedMemory = p.isSharedMemory,
            durationMs = p.durationMs,
        )

    override fun cancelInference(requestId: String) {
        authorizeCall()
        try {
            IpcInputValidator.validateId(requestId, "requestId")
        } catch (e: IllegalArgumentException) {
            throw typedBinderException(
                MindlayerErrorCode.INVALID_REQUEST,
                "Invalid requestId: ${e.message}",
            )
        }
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid()) {
            requireRegisteredClient()
        }
        // Self-UID dashboard can cancel anything (support/diagnostics);
        // external callers may only cancel their OWN requests, even if the
        // id collides with another UID's in-flight request.
        val scopedKey: String = if (uid == Process.myUid()) {
            // Dashboard may not have its own scoped entry; look up any UID's
            // active mapping. If none exists, fall back to the public id (so
            // dashboard cancel still works for self-UID requests).
            activeInferenceUids.keys.firstOrNull { it.endsWith(":$requestId") }
                ?: inferenceKey(uid, requestId)
        } else {
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
            requireRequestOwner(key)
            key
        }
        MindlayerLog.d(TAG, "cancelInference request", requestId = sanitizeLogField(requestId))
        // Concurrency slot is released by the orchestrator's completion hook.
        orchestrator.cancelInference(scopedKey)
    }

    /**
     * v0.4 detailed cancel result. Same authz fence as [cancelInference] but
     * returns a tri-state [com.adsamcik.mindlayer.CancelResult.outcome] so
     * callers can distinguish CANCELLED / ALREADY_FINISHED / UNKNOWN.
     *
     * Anti-enumeration: an external caller probing another UID's requestId
     * always sees [com.adsamcik.mindlayer.CancelResult.UNKNOWN] — the
     * recently-completed cache is keyed by `(uid, requestId)` so no
     * cross-UID lookup ever hits.
     */
    override fun cancelInferenceV2(requestId: String): com.adsamcik.mindlayer.CancelResult {
        authorizeCall()
        try {
            IpcInputValidator.validateId(requestId, "requestId")
        } catch (e: IllegalArgumentException) {
            throw typedBinderException(
                MindlayerErrorCode.INVALID_REQUEST,
                "Invalid requestId: ${e.message}",
            )
        }
        val uid = Binder.getCallingUid()
        val isSelfUid = uid == Process.myUid()
        if (!isSelfUid) {
            requireRegisteredClient()
        }

        // Look up active first.
        val activeKey: String? = if (isSelfUid) {
            activeInferenceUids.keys.firstOrNull { it.endsWith(":$requestId") }
        } else {
            val key = inferenceKey(uid, requestId)
            if (activeInferenceUids.containsKey(key)) key else null
        }
        if (activeKey != null) {
            if (!isSelfUid) requireRequestOwner(activeKey)
            MindlayerLog.d(TAG, "cancelInferenceV2: cancelling", requestId = sanitizeLogField(requestId))
            orchestrator.cancelInference(activeKey)
            return com.adsamcik.mindlayer.CancelResult(
                outcome = com.adsamcik.mindlayer.CancelResult.CANCELLED,
            )
        }

        // Then check the recently-completed cache.
        val recentKey: String? = if (isSelfUid) {
            recentlyCompleted.keys.firstOrNull { it.endsWith(":$requestId") }
        } else {
            val key = inferenceKey(uid, requestId)
            if (isRecentlyCompleted(key)) key else null
        }
        return if (recentKey != null) {
            com.adsamcik.mindlayer.CancelResult(
                outcome = com.adsamcik.mindlayer.CancelResult.ALREADY_FINISHED,
            )
        } else {
            com.adsamcik.mindlayer.CancelResult(
                outcome = com.adsamcik.mindlayer.CancelResult.UNKNOWN,
            )
        }
    }

    // ---- Tool results -------------------------------------------------------

    override fun submitToolResult(requestId: String, result: ToolResult) {
        authorizeCall()
        try {
            IpcInputValidator.validateId(requestId, "requestId")
            IpcInputValidator.validateToolResult(result)
            require(result.requestId == requestId) {
                "ToolResult.requestId must equal AIDL requestId"
            }
        } catch (e: IllegalArgumentException) {
            throw typedBinderException(
                MindlayerErrorCode.INVALID_TOOL_RESULT,
                "Invalid ToolResult: ${e.message}",
            )
        }
        val uid = Binder.getCallingUid()
        val scopedKey: String = if (uid == Process.myUid()) {
            activeInferenceUids.keys.firstOrNull { it.endsWith(":$requestId") }
                ?: inferenceKey(uid, requestId)
        } else {
            val key = inferenceKey(uid, requestId)
            if (!activeInferenceUids.containsKey(key)) {
                throw typedBinderException(
                    MindlayerErrorCode.NO_ACTIVE_REQUEST,
                    "No active request owned by caller",
                )
            }
            requireRequestOwner(key)
            key
        }
        MindlayerLog.d(
            TAG,
            "submitToolResult for call ${result.callId.loggable()} tool=${sanitizeLogField(result.toolName)}",
            requestId = sanitizeLogField(requestId),
        )
        // H3a — route to the caller's per-uid slot. The orchestrator registered
        // the pending call under the session owner's uid, which equals the
        // caller's uid (verified by the activeInferenceUids check above for
        // external callers; equal by definition for self-UID dashboard).
        orchestrator.toolCallBridge.submitResult(
            scopedKey = scopedKey,
            callId = result.callId,
            toolName = result.toolName,
            resultJson = result.resultJson,
        )
    }

    /**
     * v0.4 detailed submitToolResult. Returns ACCEPTED when the result was
     * queued, NO_PENDING_CALL when the request is active but no tool call
     * is awaiting this callId, REQUEST_GONE when the request is no longer
     * tracked (terminated, never existed, or owned by another UID — see
     * [com.adsamcik.mindlayer.ToolSubmitResult.REQUEST_GONE] for the
     * anti-enumeration property).
     */
    override fun submitToolResultV2(
        requestId: String,
        result: ToolResult,
    ): com.adsamcik.mindlayer.ToolSubmitResult {
        authorizeCall()
        try {
            IpcInputValidator.validateId(requestId, "requestId")
            IpcInputValidator.validateToolResult(result)
            require(result.requestId == requestId) {
                "ToolResult.requestId must equal AIDL requestId"
            }
        } catch (e: IllegalArgumentException) {
            throw typedBinderException(
                MindlayerErrorCode.INVALID_TOOL_RESULT,
                "Invalid ToolResult: ${e.message}",
            )
        }
        val uid = Binder.getCallingUid()
        val isSelfUid = uid == Process.myUid()

        // Find the request in active map (UID-scoped lookup for external callers).
        val scopedKey: String? = if (isSelfUid) {
            activeInferenceUids.keys.firstOrNull { it.endsWith(":$requestId") }
        } else {
            val key = inferenceKey(uid, requestId)
            if (activeInferenceUids.containsKey(key)) key else null
        }

        if (scopedKey == null) {
            // Either gone or never owned by this caller. Anti-enumeration:
            // REQUEST_GONE conflates the two cases.
            return com.adsamcik.mindlayer.ToolSubmitResult(
                outcome = com.adsamcik.mindlayer.ToolSubmitResult.REQUEST_GONE,
            )
        }
        if (!isSelfUid) requireRequestOwner(scopedKey)

        // Active. Try to deliver via the existing bridge.
        val accepted = orchestrator.toolCallBridge.submitResult(
            scopedKey = scopedKey,
            callId = result.callId,
            toolName = result.toolName,
            resultJson = result.resultJson,
        )
        return com.adsamcik.mindlayer.ToolSubmitResult(
            outcome = if (accepted) {
                com.adsamcik.mindlayer.ToolSubmitResult.ACCEPTED
            } else {
                com.adsamcik.mindlayer.ToolSubmitResult.NO_PENDING_CALL
            },
        )
    }

    // ---- Recently-completed cache (v04-cancel-tool-status) ------------------

    /**
     * Mark a scoped key as recently completed. Called from the orchestrator
     * completion hooks in [infer] and [inferMulti] paths after the inference
     * terminates (success / error / cancel). The key remains visible for
     * [RECENTLY_COMPLETED_RETENTION_MS] ms; lookups beyond that window
     * return as if the request never existed.
     */
    private fun markRecentlyCompleted(scopedKey: String) {
        recentlyCompleted[scopedKey] = System.currentTimeMillis()
        sweepRecentlyCompletedIfNeeded()
    }

    /**
     * `true` if [scopedKey] was marked complete within the retention window.
     * Opportunistically sweeps stale entries every Nth call so the map
     * stays bounded.
     */
    private fun isRecentlyCompleted(scopedKey: String): Boolean {
        val finishedAt = recentlyCompleted[scopedKey] ?: return false
        val now = System.currentTimeMillis()
        if (now - finishedAt > RECENTLY_COMPLETED_RETENTION_MS) {
            recentlyCompleted.remove(scopedKey, finishedAt)
            return false
        }
        sweepRecentlyCompletedIfNeeded()
        return true
    }

    private fun sweepRecentlyCompletedIfNeeded() {
        val n = recentlyCompletedSweepCounter.incrementAndGet()
        if (n % RECENTLY_COMPLETED_SWEEP_INTERVAL != 0) return
        val cutoff = System.currentTimeMillis() - RECENTLY_COMPLETED_RETENTION_MS
        recentlyCompleted.entries.removeAll { it.value < cutoff }
    }

    /**
     * F-055: cross-process revoke. The dashboard (self-UID) calls this so
     * the `:ml` service can:
     *   1. Remove the entry from `entries.json` under the file lock.
     *   2. Tear down any sessions currently owned by the revoked UID
     *      (so an in-flight inference is killed, not just future calls).
     *   3. Log a SECURITY_DECISION audit row.
     *
     * External callers are rejected — only the dashboard process (self-UID)
     * may revoke. The PackageManager `getPackageUid` lookup may fail (app
     * uninstalled between approval and revoke); in that case we still revoke
     * the allowlist entry but skip the session tear-down because there's no
     * UID to scope it to.
     */
    override fun revokeApp(packageName: String) {
        authorizeCall()
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.myUid()) {
            // Preserve anti-enumeration after the standard auth gate has run.
            MindlayerLog.w(TAG, "revokeApp rejected: external uid=$callingUid")
            throw SecurityException("Not authorized")
        }
        // Inline package-name shape check — letters, digits, underscores,
        // dots; max 255 bytes (Android PackageManager limit). Reject empty
        // and obviously bogus values without echoing them in error text.
        if (packageName.isEmpty() || packageName.length > 255 ||
            !packageName.all { it.isLetterOrDigit() || it == '.' || it == '_' }) {
            throw SecurityException("Invalid packageName")
        }
        val store = allowlistStore

        // Look up the live entry (so we can log a sigPrefix even after revoke
        // erases the row) and resolve the UID before mutating state.
        val entry = store.list().firstOrNull { it.packageName == packageName }
        val sigPrefix = entry?.signingCertSha256?.take(8) ?: "unknown"
        val targetUid: Int? = try {
            @Suppress("DEPRECATION")
            context.packageManager.getPackageUid(packageName, 0)
        } catch (_: android.content.pm.PackageManager.NameNotFoundException) {
            null
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "revokeApp: getPackageUid failed: ${t.javaClass.simpleName}")
            null
        }

        store.revoke(packageName)

        if (targetUid != null) {
            // Cancel inferences and destroy sessions for the revoked UID.
            // Explicit user revoke is involuntary, so fire eviction notices.
            closeAllOwnedByRevokedUid(targetUid)
        }

        logRepository?.logSecurityDecision(
            action = "revoke",
            packageName = packageName,
            sigShaPrefix = sigPrefix,
            extra = if (targetUid != null) "uid=$targetUid" else "uid=unresolved",
        )
        MindlayerLog.i(TAG, "revokeApp: $packageName (uid=${targetUid ?: "unresolved"})")
    }

    private fun inferenceKey(uid: Int, requestId: String): String = "$uid:$requestId"

    /**
     * Hard cap on a single media payload accepted from a client. Matches
     * `SharedMemoryPool.MAX_MEDIA_BYTES` — kept here to validate before
     * the AIDL transaction returns.
     */
    private val MAX_MEDIA_BYTES: Int = 100 * 1024 * 1024

    // ---- Prewarm -----------------------------------------------------------

    override fun prewarm(backend: String?) {
        val identity = try {
            authorizeCall()
        } catch (e: SecurityException) {
            // `prewarm` is `oneway` so RemoteException doesn't propagate. We
            // still want the authz failure visible in our own log so that
            // brute-force / spamming behaviour from un-approved peers shows
            // up in diagnostics.
            MindlayerLog.w(TAG, "prewarm rejected at authz gate: ${e.message}")
            return
        }
        val safeBackend = try {
            IpcInputValidator.validateBackendName(backend)
        } catch (e: IllegalArgumentException) {
            MindlayerLog.w(
                TAG,
                "Rejecting prewarm with invalid backend from ${identity.packageName}: ${e.message}",
            )
            return
        }
        MindlayerLog.d(TAG, "prewarm: backend=$safeBackend")
        startEngineWarmup(
            preferredBackend = safeBackend,
            maxTokens = 4096,
        )
    }

    private fun startEngineWarmup(preferredBackend: String, maxTokens: Int) {
        if (engineManager.isInitialized) return
        // F-057: engineWarmupInFlight coalesces concurrent prewarms — only one
        // launcher per process at a time. EngineManager.initialize is mutex-
        // protected internally as additional defense.
        if (!engineWarmupInFlight.compareAndSet(false, true)) return
        scope.launch(Dispatchers.IO) {
            try {
                // LiteRT-LM #2028 process-restart workaround: if a prior
                // process recorded a restart intent (thermal switch or
                // memory-pressure unload), honour the persisted target
                // backend instead of the caller's preferredBackend. The
                // intent represents the state the prior process WANTED
                // to switch to but couldn't (because in-process Engine
                // recreate SIGSEGVs). EngineRestartStore enforces the
                // attempt cap so a wedged target backend can't loop us
                // forever; once the cap is hit, consume() returns null
                // and we fall through to the caller's preferredBackend.
                //
                // The `attemptCount > 0` guard rejects relaxed-mock
                // RestartIntent instances that some tests' mockk(relaxed)
                // EngineManager would auto-generate instead of null
                // (real persisted intents always have attemptCount >= 1).
                val intent = engineManager.consumePendingRestartIntent()
                    ?.takeIf { it.attemptCount > 0 }
                val backend = intent?.targetBackend ?: preferredBackend
                val tokens = intent?.maxTokens ?: maxTokens
                if (intent != null) {
                    MindlayerLog.i(
                        TAG,
                        "Honoring engine restart intent: reason=${intent.reason}, " +
                            "targetBackend=${intent.targetBackend ?: "<default>"}, " +
                            "attempt=${intent.attemptCount}",
                    )
                }
                engineManager.initialize(
                    preferredBackend = backend,
                    maxTokens = tokens,
                )
                // Successful init → clear any lingering intent so a later
                // unrelated init failure doesn't fall back to "attempt N+1
                // of the same target." Idempotent.
                if (intent != null) engineManager.clearPendingRestartIntent()
            } catch (e: Exception) {
                MindlayerLog.w(TAG, "Engine warmup failed: ${e.safeLabel()}")
            } finally {
                engineWarmupInFlight.set(false)
            }
        }
    }

    /**
     * v0.4 synchronous prewarm. Suspends the binder transaction (occupying a
     * binder thread) until either the engine is initialized or [timeoutMs]
     * elapses, then returns the actually-active backend. Useful when the
     * caller wants to surface init failures or know which fallback backend
     * was selected — `oneway prewarm` cannot do either.
     *
     * The caller-supplied [timeoutMs] is clamped to a safe upper bound
     * (`PREWARM_AWAIT_MAX_TIMEOUT_MS`) so a misbehaving caller cannot pin
     * a binder thread indefinitely. Engine init itself is mutex-protected
     * inside `EngineManager.initialize`, so concurrent prewarmAndAwait
     * calls coalesce on the same in-flight init job.
     */
    override fun prewarmAndAwait(backend: String?, timeoutMs: Long): String {
        val identity = authorizeCall()
        val safeBackend = try {
            IpcInputValidator.validateBackendName(backend)
        } catch (e: IllegalArgumentException) {
            throw typedBinderException(
                MindlayerErrorCode.INVALID_REQUEST,
                "Invalid backend: ${e.message}",
            )
        }
        val cappedTimeout = timeoutMs.coerceIn(
            PREWARM_AWAIT_MIN_TIMEOUT_MS,
            PREWARM_AWAIT_MAX_TIMEOUT_MS,
        )

        // Already initialized? Return the active backend immediately.
        if (engineManager.isInitialized) {
            MindlayerLog.d(
                TAG,
                "prewarmAndAwait: engine already initialized (backend=${engineManager.currentBackend})",
            )
            return engineManager.currentBackend
        }

        MindlayerLog.d(
            TAG,
            "prewarmAndAwait from ${identity.packageName} backend=$safeBackend timeout=${cappedTimeout}ms",
        )
        return try {
            runBlocking {
                kotlinx.coroutines.withTimeout(cappedTimeout) {
                    // LiteRT-LM #2028 process-restart workaround: see the
                    // matching block in startEngineWarmup() for the full
                    // rationale. Honour a persisted restart intent over
                    // the caller's safeBackend so a post-restart prewarm
                    // lands on the backend the prior process requested
                    // (not the caller's possibly-stale preference).
                    // attemptCount > 0 guard rejects relaxed-mock instances.
                    val intent = engineManager.consumePendingRestartIntent()
                        ?.takeIf { it.attemptCount > 0 }
                    val backend = intent?.targetBackend ?: safeBackend
                    val tokens = intent?.maxTokens ?: 4096
                    if (intent != null) {
                        MindlayerLog.i(
                            TAG,
                            "prewarmAndAwait honoring restart intent: reason=${intent.reason}, " +
                                "targetBackend=${intent.targetBackend ?: "<default>"}, " +
                                "attempt=${intent.attemptCount}",
                        )
                    }
                    engineManager.initialize(
                        preferredBackend = backend,
                        maxTokens = tokens,
                    )
                    if (intent != null) engineManager.clearPendingRestartIntent()
                    engineManager.currentBackend
                }
            }
        } catch (_: kotlinx.coroutines.TimeoutCancellationException) {
            // Timed out before init completed — return the current backend
            // (could still be "NONE" if init is mid-flight; caller polls
            // getStatus.isEngineLoaded to confirm).
            MindlayerLog.w(TAG, "prewarmAndAwait timed out after ${cappedTimeout}ms")
            engineManager.currentBackend
        } catch (e: com.adsamcik.mindlayer.service.engine.LowMemoryException) {
            // F-071: surface the typed LOW_MEMORY code from the
            // synchronous prewarm path too, otherwise an explicit
            // prewarmAndAwait caller would receive ENGINE_LOAD_FAILED
            // and lose the avail/required diagnostic numbers.
            MindlayerLog.w(
                TAG,
                "prewarmAndAwait refused: availMb=${e.availMb} requiredMb=${e.requiredMb}",
            )
            throw typedBinderException(
                MindlayerErrorCode.LOW_MEMORY,
                "Insufficient memory: availMb=${e.availMb} requiredMb=${e.requiredMb}",
            )
        } catch (e: Exception) {
            MindlayerLog.w(TAG, "prewarmAndAwait failed: ${e.message}")
            throw typedBinderException(
                MindlayerErrorCode.ENGINE_LOAD_FAILED,
                "Engine init failed: ${e.javaClass.simpleName}",
            )
        }
    }

    // ---- Status ------------------------------------------------------------

    override fun getStatus(): ServiceStatus {
        // F-064: cheap call — quarter-cost so dashboard polling doesn't
        // dominate the per-UID budget for external callers either.
        authorizeCall(cost = 0.25)
        return buildStatus()
    }

    private fun buildStatus(): ServiceStatus {
        val uid = Binder.getCallingUid()
        val isSelfUid = uid == Process.myUid()
        val thermalPolicy = thermalMonitor.currentPolicy.value
        val thermalSample = thermalMonitor.latestSample.value
        val memSnapshot = memoryBudget.currentSnapshot()
        val visibleActiveSessions = if (isSelfUid) {
            orchestrator.listSessions().size
        } else {
            orchestrator.listSessionsOwnedBy(uid).size
        }
        val visibleActiveInferences = if (isSelfUid) {
            service.activeInferenceCount
        } else {
            activeInferenceUids.values.count { it == uid }
        }

        // F-073: surface the telemetry-blind state via the existing
        // wire-stable `thermalBand: String` field. `ServiceStatus` is a
        // frozen Parcelable per `docs/AIDL_STABILITY.md`, so we encode
        // "telemetry unavailable" as a sentinel rather than adding a field.
        // SDK clients that pattern-match "HOT"/"CRITICAL" see an unrecognised
        // value and treat the device as healthy — which is correct, the
        // orchestrator is already applying the conservative policy on
        // their behalf.
        val encodedThermalBand = if (thermalPolicy.confidence == ThermalConfidence.INFERRED) {
            THERMAL_TELEMETRY_UNAVAILABLE
        } else {
            thermalPolicy.band.name
        }

        // L1: only the dashboard (self-UID) sees device-wide metrics that
        // could be used for cross-app workload inference / fingerprinting.
        // External callers see scoped session/inference counts and zeroed
        // memory/headroom fields.
        return if (isSelfUid) {
            ServiceStatus(
                isEngineLoaded = engineManager.isInitialized,
                engineWarming = engineWarmupInFlight.get(),
                activeSessionCount = visibleActiveSessions,
                activeInferenceCount = visibleActiveInferences,
                backend = engineManager.currentBackend,
                thermalBand = encodedThermalBand,
                isForeground = visibleActiveInferences > 0,
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
                activeSessionCount = visibleActiveSessions,
                activeInferenceCount = visibleActiveInferences,
                backend = engineManager.currentBackend,
                thermalBand = encodedThermalBand,
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
        val uid = Binder.getCallingUid()
        // F-005: external callers see only their own sessions/logs to
        // prevent the diagnostic dump from being used as a force-multiplier
        // for cross-UID attacks (it was previously the easiest way to
        // harvest victim sessionIds and requestIds).
        // F-019: cap concurrency + apply a deadline so a single hostile
        // (but authorized) caller cannot wedge multiple binder threads on
        // Room I/O even within the per-UID rate-limit budget.
        val scopeUid: Int? = if (uid == Process.myUid()) null else uid
        return runBlocking {
            withTimeout(2_000L) {
                diagnosticsLock.withLock {
                    diagnosticExporter.export(scopeUid)
                }
            }
        }
    }

    override fun getEngineInfo(): EngineInfo {
        // F-064: cheap call — quarter-cost.
        authorizeCall(cost = 0.25)
        return buildEngineInfo()
    }

    private fun buildEngineInfo(): EngineInfo {
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
        val latestThroughput = runBlocking {
            logRepository?.latestThroughput() ?: (0f to 0f)
        }

        return EngineInfo(
            modelId = modelId,
            modelSizeBytes = modelSize,
            backend = engineManager.currentBackend,
            maxTokens = 4096,
            initTimeSeconds = engineManager.initTimeSeconds,
            lastPrefillToksPerSec = latestThroughput.first,
            lastDecodeToksPerSec = latestThroughput.second,
        )
    }

    override fun getCapabilities(): com.adsamcik.mindlayer.ServiceCapabilities {
        // Cheap probe — quarter-cost so first-launch handshake doesn't burn
        // budget. Still gated by authorizeCall so un-approved peers go
        // through the standard pending-approval path rather than getting a
        // free fingerprinting endpoint.
        authorizeCall(cost = 0.25)
        val coord = embeddingCoordinator
        // FEATURE_EMBEDDINGS is conditional on TWO independent signals:
        //   1. A real embedding model is installed (defaultModelOrNull != null)
        //   2. The backend is production-ready (EmbeddingFeatureFlags.IS_PRODUCTION_READY)
        // Phase A intentionally ships #1 = sometimes, #2 = false. Advertising
        // FEATURE_EMBEDDINGS when #2 is false would tell capability-aware
        // SDKs that embed works, and every call would then throw — worse than
        // leaving the feature dark. When Phase D wires the real LiteRT
        // pipeline, IS_PRODUCTION_READY flips to true and the feature lights
        // up.
        val embeddingModel = coord?.defaultModelOrNull()
        val embeddingsLive = embeddingModel != null && coord.isProductionReady
        val baseFeatures = if (embeddingsLive) {
            SUPPORTED_FEATURES + com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_EMBEDDINGS
        } else {
            SUPPORTED_FEATURES
        }
        // Phase 2 #5 (p2-feature-flip): advertise FEATURE_OCR_SESSION
        // ONLY when the PaddleOCR engine is in PaddleOcrEngineState.Ready
        // (model bundle present + native delegates initialized).
        // Capability-aware SDKs then exercise the OCR API safely on
        // supported devices and degrade silently on devices that lack
        // the bundle.
        val effectiveFeatures = if (
            ocrSessionManager.isProductionReady &&
            ocrSessionManager.isEngineReady()
        ) {
            baseFeatures +
                com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_OCR_SESSION +
                com.adsamcik.mindlayer.ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT
        } else {
            baseFeatures
        }
        // android.os.SharedMemory was introduced in API 27 (O_MR1). On API
        // 26 the SHM path in EmbeddingCoordinator.embedBatchShm cannot be
        // taken at all — advertise maxEmbeddingBatchShm=0 so capability-aware
        // SDKs route to embedBatch or embedBatchDeferred instead.
        val shmSupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1
        return com.adsamcik.mindlayer.ServiceCapabilities(
            apiVersion = CURRENT_API_VERSION,
            supportedFeatures = effectiveFeatures,
            pipeProtocol = "mindlayer.stream.v1",
            maxFrameBytes = 1_048_576,
            maxToolRounds = com.adsamcik.mindlayer.service.engine.InferenceOrchestrator.MAX_TOOL_ROUNDS,
            maxToolArgsLen = com.adsamcik.mindlayer.service.engine.InferenceOrchestrator.MAX_TOOL_ARGS_LEN,
            maxRequestsPerMinute = RateLimiter.DEFAULT_RPM,
            maxConcurrentInferences = RateLimiter.DEFAULT_MAX_CONCURRENT,
            maxConcurrentSessions = memoryBudget.deviceTier.maxSessions,
            maxSessionExpirationMs = IpcInputValidator.MAX_SESSION_EXPIRATION_MS,
            // Effective cap (engine constraint), not the wire ceiling.
            // Today: at most 1 image + 1 audio per request. When litert-lm
            // #1874 lifts multi-image, this rises with the validator caps.
            maxMediaPartsPerRequest = MAX_MEDIA_PARTS_PER_REQUEST,
            maxTotalMediaBytesPerRequest = IpcInputValidator.MAX_TOTAL_MEDIA_BYTES_PER_REQUEST,
            maxEmbeddingBatchInline = if (embeddingsLive) coord.maxBatchInline else 0,
            maxEmbeddingBatchShm = if (embeddingsLive && shmSupported) coord.maxBatchShm else 0,
            maxEmbeddingBatchTotal = if (embeddingsLive) coord.maxBatchTotal else 0,
            maxEmbeddingInputBytes = if (embeddingsLive) coord.maxInputBytes else 0L,
            embeddingModelIds = if (embeddingsLive) listOf(embeddingModel.id) else emptyList(),
            embeddingDims = if (embeddingsLive) embeddingModel.supportedDims else emptyList(),
        )
    }

    /**
     * v0.4 typed diagnostics snapshot. Returns a small, programmatically
     * consumable struct for dashboard polling and external monitoring;
     * legacy [getDiagnostics] (string JSON dump) stays for human-readable
     * bug reports.
     *
     * Per-caller scoped: external callers see only their own session /
     * inference / error counts; self-UID dashboard sees aggregates.
     */
    override fun getDiagnosticsTyped(): com.adsamcik.mindlayer.DiagnosticsSnapshot {
        // Quarter-cost — typed diagnostics is a polling endpoint for
        // dashboards, not a heavy bug-report dump.
        authorizeCall(cost = 0.25)
        val uid = Binder.getCallingUid()
        val isSelfUid = uid == Process.myUid()

        val callerSessions = if (isSelfUid) {
            orchestrator.listSessions().size
        } else {
            orchestrator.listSessionsOwnedBy(uid).size
        }
        val activeForCaller = if (isSelfUid) {
            activeInferenceUids.size
        } else {
            activeInferenceUids.values.count { it == uid }
        }
        // Recent counts: today we approximate "last 5 minutes" with the
        // recently-completed cache (30 s retention). Best-effort signal —
        // log-DB-backed counts will land alongside `v04-eviction-callback`.
        val recentForCaller = if (isSelfUid) {
            recentlyCompleted.size + activeForCaller
        } else {
            val prefix = "$uid:"
            recentlyCompleted.keys.count { it.startsWith(prefix) } + activeForCaller
        }

        return com.adsamcik.mindlayer.DiagnosticsSnapshot(
            capturedAtMs = System.currentTimeMillis(),
            service = buildStatus(),
            engine = buildEngineInfo(),
            callerSessionCount = callerSessions,
            recentInferenceCount = recentForCaller,
            recentErrorCount = runBlocking {
                logRepository?.recentErrorCount(recentErrorWindowMs()) ?: 0
            },
            recentlyCompletedTrackedCount = recentlyCompleted.size,
        )
    }


    override fun embed(req: com.adsamcik.mindlayer.EmbeddingRequest): com.adsamcik.mindlayer.EmbeddingResult {
        authorizeCall(cost = 1.0)
        validateEmbeddingRequestOrThrow(req)
        val requestId = "emb-${Binder.getCallingUid()}-${UUID.randomUUID()}"
        return runBlocking { requireEmbeddingCoordinator().embed(Binder.getCallingUid(), req, requestId) }
    }

    override fun embedBatch(reqs: List<com.adsamcik.mindlayer.EmbeddingRequest>?): com.adsamcik.mindlayer.EmbeddingBatchResult {
        authorizeCall(cost = ((reqs?.size ?: 0) * 0.5).coerceAtMost(RateLimiter.MAX_COST))
        val list = reqs ?: emptyList()
        validateEmbeddingRequestsOrThrow(list, requireEmbeddingCoordinator().maxBatchInline)
        val requestId = "emb-${Binder.getCallingUid()}-${UUID.randomUUID()}"
        return runBlocking { requireEmbeddingCoordinator().embedBatch(Binder.getCallingUid(), list, requestId) }
    }

    override fun embedBatchShm(reqs: List<com.adsamcik.mindlayer.EmbeddingRequest>?): com.adsamcik.mindlayer.EmbeddingBatchTransfer {
        authorizeCall(cost = ((reqs?.size ?: 0) * 0.5).coerceAtMost(RateLimiter.MAX_COST))
        // android.os.SharedMemory (used by EmbeddingCoordinator.embedBatchShm
        // via SharedMemoryPool.acquireBlob) requires API 27. ServiceCapabilities
        // advertises maxEmbeddingBatchShm=0 on API 26 so capability-aware SDKs
        // never reach this path, but we add a runtime gate here as defense in
        // depth — without it, calling this method on API 26 would crash the
        // service with NoSuchMethodError on SharedMemory.create().
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O_MR1) {
            throw typedBinderException(
                MindlayerErrorCode.NOT_SUPPORTED,
                "embedBatchShm requires API 27+ (SharedMemory); use embedBatch or embedBatchDeferred",
            )
        }
        val list = reqs ?: emptyList()
        validateEmbeddingRequestsOrThrow(list, requireEmbeddingCoordinator().maxBatchShm)
        val requestId = "emb-${Binder.getCallingUid()}-${UUID.randomUUID()}"
        return runBlocking { requireEmbeddingCoordinator().embedBatchShm(Binder.getCallingUid(), list, requestId) }
    }

    override fun embedBatchDeferred(reqs: List<com.adsamcik.mindlayer.EmbeddingRequest>?): DeferredHandle {
        authorizeCall(cost = 0.5)
        val list = reqs ?: emptyList()
        validateEmbeddingRequestsOrThrow(list, requireEmbeddingCoordinator().maxBatchTotal)
        return runBlocking { requireEmbeddingCoordinator().embedBatchDeferred(Binder.getCallingUid(), list) }
    }

    override fun fetchEmbeddingBatchResult(requestId: String): com.adsamcik.mindlayer.VectorBlobHandle {
        authorizeCall(cost = 0.1)
        validateEmbeddingRequestIdOrThrow(requestId)
        return runBlocking { requireEmbeddingCoordinator().fetchEmbeddingBatchResult(Binder.getCallingUid(), requestId) }
    }

    override fun cancelEmbeddingBatch(requestId: String): com.adsamcik.mindlayer.CancelResult {
        authorizeCall(cost = 0.1)
        validateEmbeddingRequestIdOrThrow(requestId)
        val outcome = runBlocking { requireEmbeddingCoordinator().cancelEmbeddingBatch(Binder.getCallingUid(), requestId) }
        return com.adsamcik.mindlayer.CancelResult(outcome = outcome)
    }

    override fun acknowledgeEmbeddingBatchResult(requestId: String) {
        authorizeCall(cost = 0.1)
        validateEmbeddingRequestIdOrThrow(requestId)
        runBlocking { requireEmbeddingCoordinator().acknowledgeEmbeddingBatchResult(Binder.getCallingUid(), requestId) }
    }

    override fun cancelEmbed(requestId: String): com.adsamcik.mindlayer.CancelResult {
        authorizeCall(cost = 0.1)
        validateEmbeddingRequestIdOrThrow(requestId)
        val outcome = requireEmbeddingCoordinator().cancelEmbed(Binder.getCallingUid(), requestId)
        return com.adsamcik.mindlayer.CancelResult(outcome = outcome)
    }

    // ─────────────────────────────────────────────────────────────────────
    //  v0.8 multi-frame OCR — session lifecycle wired to OcrSessionManager
    //  (Phase 1 PR C3 + Phase 2 #1–#4 evidence + extraction pipeline).
    //  FEATURE_OCR_SESSION is now CONDITIONALLY advertised by
    //  getCapabilities() when OcrSessionManager.isEngineReady() returns
    //  true (Phase 2 #5, p2-feature-flip).
    // ─────────────────────────────────────────────────────────────────────

    override fun createOcrSession(cfg: com.adsamcik.mindlayer.OcrSessionConfig): String {
        authorizeCall(cost = 1.0)
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
            throw typedBinderException(code, msg)
        }
    }

    override fun pushOcrFrame(
        sessionId: String,
        frame: com.adsamcik.mindlayer.MediaPart,
        meta: com.adsamcik.mindlayer.OcrFrameMeta,
    ): com.adsamcik.mindlayer.OcrFrameAck {
        authorizeCall(cost = 0.15)
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
            throw typedBinderException(MindlayerErrorCode.INVALID_REQUEST, "Invalid OCR MediaPart: ${t.safeLabel()}")
        }
        if (!ocrSessionManager.isOwner(uid, sessionId)) {
            try { frame.source.close() } catch (_: Throwable) { /* fine */ }
            throw typedBinderException(
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
        } catch (e: com.adsamcik.mindlayer.service.ipc.SharedMemoryPoolExhaustedException) {
            try { frame.source.close() } catch (_: Throwable) { /* fine */ }
            MindlayerLog.w(
                TAG,
                "OCR Y-plane extraction resource exhausted: ${e.safeLabel()}",
                requestId = scopedKey,
                sessionId = sanitizeLogField(sessionId),
                throwable = null,
            )
            throw typedBinderException(
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

    override fun streamOcrEvents(
        sessionId: String,
        eventWriteEnd: android.os.ParcelFileDescriptor,
    ) {
        authorizeCall(cost = 0.25)
        IpcInputValidator.validateId(sessionId, "sessionId")
        val uid = Binder.getCallingUid()
        if (!ocrSessionManager.isOwner(uid, sessionId)) {
            try {
                eventWriteEnd.close()
            } catch (_: java.io.IOException) {
                // best-effort
            }
            throw typedBinderException(
                MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
                "Session not found or not owned by caller",
            )
        }
        // Phase 2 #2: attach the OCR_V1 event-stream writer to the
        // session. The writer takes ownership of the PFD (the
        // wrapped AutoCloseOutputStream closes it on writer close).
        val writer = com.adsamcik.mindlayer.service.ipc.OcrTokenStreamWriter(eventWriteEnd)
        val attached = ocrSessionManager.attachEventWriter(uid, sessionId, writer)
        if (!attached) {
            // Ownership flipped between isOwner check and attach —
            // close the writer (which closes the PFD).
            try {
                writer.close()
            } catch (_: Throwable) {
                // best-effort
            }
            throw typedBinderException(
                MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
                "Session not found or not owned by caller",
            )
        }
    }

    override fun getOcrSessionState(sessionId: String): com.adsamcik.mindlayer.OcrSessionState {
        authorizeCall(cost = 0.1)
        IpcInputValidator.validateId(sessionId, "sessionId")
        val uid = Binder.getCallingUid()
        if (!ocrSessionManager.isOwner(uid, sessionId)) {
            throw typedBinderException(
                MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
                "Session not found or not owned by caller",
            )
        }
        return try {
            ocrSessionManager.stateOf(uid, sessionId)
        } catch (e: IllegalStateException) {
            throw typedBinderException(
                MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
                "Session not found or not owned by caller",
            )
        }
    }

    override fun finalizeOcrSession(sessionId: String) {
        authorizeCall(cost = 1.0)
        IpcInputValidator.validateId(sessionId, "sessionId")
        val uid = Binder.getCallingUid()
        if (!ocrSessionManager.isOwner(uid, sessionId)) {
            throw typedBinderException(
                MindlayerErrorCode.SESSION_NOT_FOUND_OR_NOT_OWNED,
                "Session not found or not owned by caller",
            )
        }
        runBlocking { ocrSessionManager.finalize(uid, sessionId) }
    }

    override fun closeOcrSession(sessionId: String) {
        authorizeCall(cost = 0.1)
        IpcInputValidator.validateId(sessionId, "sessionId")
        val uid = Binder.getCallingUid()
        // Idempotent: closing a non-owned session is a no-op (mirrors
        // close-semantics-for-already-closed-streams). We do NOT
        // anti-enumerate here because close() returning silently for
        // unowned-sessions is indistinguishable from close()-of-closed.
        try {
            runBlocking { ocrSessionManager.close(uid, sessionId) }
        } catch (_: IllegalStateException) {
            // Session not found or not owned — silently no-op.
        }
    }

    override fun getOcrLimits(): com.adsamcik.mindlayer.OcrLimits {
        authorizeCall(cost = 0.1)
        // Returns the manager configured limits regardless of whether FEATURE_OCR_SESSION is advertised.
        return ocrSessionManager.getLimits()
    }

    /**
     * v0.9 single-image OCR. Sync binder transaction; runs the existing
     * PaddleOCR engine on a single [MediaPart] and, when requested,
     * forwards the recognized lines to the LLM extractor for structured
     * fields. Returns once both passes are done.
     *
     * Threading: the engine's per-instance mutex serialises this call
     * with any concurrent session pushes. There is no per-call setup
     * cost (no session record, no event pipe, no fusion accumulator).
     *
     * Wire errors:
     *  - [com.adsamcik.mindlayer.shared.MindlayerErrorCode.INVALID_REQUEST]
     *    on validator failure or MediaPart decode failure.
     *  - [com.adsamcik.mindlayer.shared.MindlayerErrorCode.SERVICE_UNAVAILABLE]
     *    when the OCR engine is not wired or not ready (model bundle
     *    missing, init still in flight, init failed).
     *  - [com.adsamcik.mindlayer.shared.MindlayerErrorCode.LOW_MEMORY]
     *    when the engine declined recognition for memory reasons.
     *  - [com.adsamcik.mindlayer.shared.MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED]
     *    when SharedMemory staging was rejected by the pool.
     */
    override fun ocrImage(
        image: com.adsamcik.mindlayer.MediaPart,
        options: com.adsamcik.mindlayer.OcrImageOptions,
    ): com.adsamcik.mindlayer.OcrImageResult {
        val totalStartedNs = System.nanoTime()
        authorizeCall(cost = 1.0)
        try {
            IpcInputValidator.validateOcrImageOptions(options)
        } catch (e: IllegalArgumentException) {
            try { image.source.close() } catch (_: Throwable) { /* fine */ }
            throw typedBinderException(
                MindlayerErrorCode.INVALID_REQUEST,
                "Invalid OcrImageOptions: ${e.message}",
            )
        }
        try {
            IpcInputValidator.validateImageTransfer(image, MAX_MEDIA_BYTES)
        } catch (t: Throwable) {
            try { image.source.close() } catch (_: Throwable) { /* fine */ }
            MindlayerLog.w(
                TAG,
                "ocrImage MediaPart rejected: ${t.safeLabel()}",
                throwable = null,
            )
            throw typedBinderException(
                MindlayerErrorCode.INVALID_REQUEST,
                "Invalid ocrImage MediaPart: ${t.safeLabel()}",
            )
        }

        val engine = paddleOcrEngine
        val pool = sharedMemoryPool
        if (engine == null || pool == null || !ocrSessionManager.isEngineReady()) {
            try { image.source.close() } catch (_: Throwable) { /* fine */ }
            throw typedBinderException(
                MindlayerErrorCode.SERVICE_UNAVAILABLE,
                "OCR engine not ready",
            )
        }

        val uid = Binder.getCallingUid()
        val scopedKey = "ocr-image:$uid:${System.nanoTime()}"

        val extracted = try {
            com.adsamcik.mindlayer.service.engine.MediaPartYPlaneExtractor.extractY(image, pool, scopedKey)
        } catch (e: SecurityException) {
            try { image.source.close() } catch (_: Throwable) { /* fine */ }
            MindlayerLog.w(
                TAG,
                "ocrImage Y-plane extraction rejected: ${e.safeLabel()}",
                requestId = scopedKey,
                throwable = null,
            )
            throw e
        } catch (e: com.adsamcik.mindlayer.service.ipc.SharedMemoryPoolExhaustedException) {
            try { image.source.close() } catch (_: Throwable) { /* fine */ }
            MindlayerLog.w(
                TAG,
                "ocrImage Y-plane staging exhausted: ${e.safeLabel()}",
                requestId = scopedKey,
                throwable = null,
            )
            throw typedBinderException(
                MindlayerErrorCode.TRANSIENT_RESOURCE_EXHAUSTED,
                "shm_pool_exhausted reason=${e.reason} retryAfterMs=${e.retryAfterMs}",
            )
        } catch (t: Throwable) {
            try { image.source.close() } catch (_: Throwable) { /* fine */ }
            MindlayerLog.w(
                TAG,
                "ocrImage Y-plane extraction failed: ${t.safeLabelWithDetail()}",
                requestId = scopedKey,
                throwable = null,
            )
            throw typedBinderException(
                MindlayerErrorCode.INVALID_REQUEST,
                "ocrImage decode failed: ${t.safeLabelWithDetail()}",
            )
        }

        val engineConfig = com.adsamcik.mindlayer.service.engine.OcrEngineConfig(
            emitBoundingBoxes = options.emitBoundingBoxes,
            maxLines = options.maxLines,
            orientationDisabled = options.orientationDisabled,
        )

        val ocrStartedNs = System.nanoTime()
        val ocrOutput = try {
            runBlocking {
                engine.recognise(
                    extracted.yPlane,
                    extracted.width,
                    extracted.height,
                    engineConfig,
                )
            }
        } catch (e: com.adsamcik.mindlayer.service.engine.LowMemoryException) {
            MindlayerLog.w(
                TAG,
                "ocrImage rejected: low memory availMb=${e.availMb} requiredMb=${e.requiredMb}",
                requestId = scopedKey,
            )
            throw typedBinderException(
                MindlayerErrorCode.LOW_MEMORY,
                "Insufficient memory: availMb=${e.availMb} requiredMb=${e.requiredMb}",
            )
        } catch (t: Throwable) {
            MindlayerLog.w(
                TAG,
                "ocrImage recognise failed: ${t.safeLabelWithDetail()}",
                requestId = scopedKey,
                throwable = null,
            )
            throw typedBinderException(
                MindlayerErrorCode.SERVICE_UNAVAILABLE,
                "OCR recognise failed: ${t.safeLabelWithDetail()}",
            )
        }
        val ocrDurationMs = (System.nanoTime() - ocrStartedNs) / 1_000_000L

        var extractionResult: com.adsamcik.mindlayer.service.engine.OcrExtractionResult? = null
        var llmDurationMs = 0L
        if (options.runLlmExtraction) {
            val schema = options.extractionSchemaJson
            check(!schema.isNullOrEmpty()) {
                // validateOcrImageOptions already enforces this; defense-in-depth.
                "runLlmExtraction requires non-empty extractionSchemaJson"
            }
            val evidence = com.adsamcik.mindlayer.service.engine.OcrEvidencePackage(
                sessionId = scopedKey,
                frameId = 0L,
                frameIndex = 0,
                mode = com.adsamcik.mindlayer.OcrSessionConfig.MODE_GENERAL_DOCUMENT,
                outputSchemaJson = schema,
                textLines = ocrOutput.lines,
                barcodeAnchors = emptyList(),
                frameQuality = 1.0,
            )
            val llmStartedNs = System.nanoTime()
            extractionResult = try {
                runBlocking { ocrLlmExtractor.extract(evidence) }
            } catch (t: Throwable) {
                // Per OcrLlmExtractor contract, implementations SHOULD return
                // empty rather than throw. If something does throw, fall back to
                // returning OCR-only result instead of poisoning the whole call.
                MindlayerLog.w(
                    TAG,
                    "ocrImage LLM extraction failed: ${t.safeLabel()}",
                    requestId = scopedKey,
                    throwable = null,
                )
                null
            }
            llmDurationMs = (System.nanoTime() - llmStartedNs) / 1_000_000L
        }

        val totalDurationMs = (System.nanoTime() - totalStartedNs) / 1_000_000L
        MindlayerLog.d(
            TAG,
            "ocrImage ok lines=${ocrOutput.lines.size} runLlm=${options.runLlmExtraction} " +
                "ocr=${ocrDurationMs}ms llm=${llmDurationMs}ms total=${totalDurationMs}ms",
            requestId = scopedKey,
        )

        return com.adsamcik.mindlayer.OcrImageResult(
            lines = ocrOutput.lines.map { line ->
                com.adsamcik.mindlayer.OcrImageLine(
                    text = line.text,
                    confidence = confidenceToWire(line.confidence),
                    boundingBox = line.boundingBox,
                    orientationDegrees = line.orientationDegrees,
                )
            },
            extractionFields = extractionResult?.fields?.map { field ->
                com.adsamcik.mindlayer.OcrImageExtractedField(
                    name = field.name,
                    value = field.value,
                    confidence = confidenceToWire(field.confidence),
                )
            }.orEmpty(),
            extractionJson = extractionResult?.rawJson,
            backend = ocrOutput.backend,
            ocrDurationMs = ocrDurationMs,
            llmDurationMs = llmDurationMs,
            totalDurationMs = totalDurationMs,
        )
    }

    /** Map the engine-side verbalized confidence to the wire-int scale. */
    private fun confidenceToWire(
        c: com.adsamcik.mindlayer.service.engine.OcrFieldFusion.Confidence,
    ): Int = when (c) {
        com.adsamcik.mindlayer.service.engine.OcrFieldFusion.Confidence.LOW ->
            com.adsamcik.mindlayer.OcrImageLine.CONFIDENCE_LOW
        com.adsamcik.mindlayer.service.engine.OcrFieldFusion.Confidence.MEDIUM ->
            com.adsamcik.mindlayer.OcrImageLine.CONFIDENCE_MEDIUM
        com.adsamcik.mindlayer.service.engine.OcrFieldFusion.Confidence.HIGH ->
            com.adsamcik.mindlayer.OcrImageLine.CONFIDENCE_HIGH
    }

    // ─────────────────────────────────────────────────────────────────────
    //  v0.8.1 health check — Phase 3 #8 (p3-health-check)
    //
    //  Lightweight liveness probe. Deliberately BYPASSES authorizeCall
    //  (no allowlist gate) so:
    //    - co-signed peers in pending-approval can confirm the service
    //      is alive without bumping their pending-approval rate-limit
    //      bucket; it is separately capped by a ping-only token bucket;
    //    - the dashboard can poll cheaply for an in-process indicator;
    //    - watchdog probes don't compete with real inference for the
    //      caller's rate budget.
    //
    //  The first-hop signature-permission gate (BIND_ML_SERVICE) still
    //  applies — only co-signed peers can bind in the first place. ping()
    //  intentionally surfaces NO caller-specific information (no UID
    //  echo, no per-caller-uptime, no per-caller-state) so a hostile
    //  un-approved peer cannot use it for fingerprinting.
    // ─────────────────────────────────────────────────────────────────────

    override fun ping(): com.adsamcik.mindlayer.HealthCheck {
        val uid = Binder.getCallingUid()
        if (uid != Process.myUid() && !rateLimiter.tryAcquirePing(uid)) {
            logRepository?.logRateLimitReject("ping", uid, 0.0)
            throw typedBinderException(MindlayerErrorCode.RATE_LIMITED, "Ping rate limit exceeded")
        }
        return com.adsamcik.mindlayer.HealthCheck(
            serverTimestampMs = System.currentTimeMillis(),
            serviceUptimeMs = android.os.SystemClock.elapsedRealtime() - service.createdAtMs,
            apiVersion = CURRENT_API_VERSION,
            llmEngineState = safeEngineState { engineManager.state.value },
            embeddingEngineState = safeEngineState { service.embeddingEngine.state.value },
            ocrEngineState = safeEngineState { service.paddleOcrEngine.state.value },
            extensionsJson = null,
        )
    }

    /**
     * Read an engine's sealed-class state, catching the
     * `UninitializedPropertyAccessException` that fires when the
     * lateinit field on the service hasn't been wired yet (test
     * fixtures, very early boot), and any unexpected throwable. Returns
     * `IDLE` in either case.
     */
    private inline fun safeEngineState(read: () -> Any?): Int {
        val state = try {
            read()
        } catch (_: UninitializedPropertyAccessException) {
            return com.adsamcik.mindlayer.HealthCheck.ENGINE_STATE_IDLE
        } catch (_: Throwable) {
            return com.adsamcik.mindlayer.HealthCheck.ENGINE_STATE_IDLE
        }
        return HealthCheckEngineStateMapper.map(state)
    }

    private fun callerAidlMethodName(): String =
        Thread.currentThread().stackTrace
            .firstOrNull { frame ->
                frame.className == ServiceBinder::class.java.name &&
                    frame.methodName !in setOf(
                        "authorizeCall",
                        "callerAidlMethodName",
                        "requireOwnership",
                        "requireRegisteredClient",
                    )
            }
            ?.methodName
            ?: "unknown"

    private fun recentErrorWindowMs(): Long =
        System.getProperty("mindlayer.diagnostics.recentErrorWindowMs")
            ?.toLongOrNull()
            ?.coerceAtLeast(0L)
            ?: 60_000L

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

    // ---- v0.4 eviction-callback subscription -------------------------------

    /**
     * Register an [com.adsamcik.mindlayer.IClientCallback] under the calling
     * UID. The callback fires once per involuntary session retirement
     * (memory pressure, expiration, caller-capacity eviction). Voluntary
     * tear-downs — caller-initiated `destroySession` and binder-death
     * cleanup — do not fire.
     *
     * Idempotent per-binder: registering the same [callback] instance twice
     * is a no-op. Bounded per-UID so a runaway reconnect loop cannot exhaust
     * service heap. Failures (binder dead, capacity exhausted) are silent;
     * the SDK should treat the subscribe as best-effort.
     *
     * No rate-limit cost: this is a setup call invoked once per connection.
     * The standard auth gate (signature permission + allowlist) still
     * applies via [authorizeCall].
     */
    override fun subscribeEvictionNotices(callback: com.adsamcik.mindlayer.IClientCallback?) {
        authorizeCall(cost = 0.0)
        val uid = Binder.getCallingUid()
        if (callback == null) {
            throw typedBinderException(
                com.adsamcik.mindlayer.shared.MindlayerErrorCode.INVALID_REQUEST,
                "callback required",
            )
        }
        evictionRegistry.register(uid, callback)
        // M-D3: replay completions that landed while the caller's binder
        // was dead. Pre-fix, a `notifyDeferredComplete` fired into a
        // disconnected binder was lost (`oneway` AIDL on a dead remote
        // is a silent no-op). On reconnect we re-dispatch every entry the
        // caller hasn't observed yet (`fetchedAtMs IS NULL` AND status !=
        // STILL_RUNNING). Matched on the SDK side by `replay = 1` on the
        // `_deferredCompletionFlow` so a `collect { … }` started just
        // after `chatDeferred(…)` also sees the latest completion.
        val store = deferredStore
        if (store != null) {
            scope.launch(Dispatchers.IO) {
                val pending = runCatching { store.completedPendingForUid(uid) }
                    .getOrDefault(emptyList())
                for (entry in pending) {
                    try {
                        callback.onDeferredInferenceComplete(entry.requestId, entry.statusCode)
                    } catch (_: android.os.RemoteException) {
                        // Caller's binder died again; the next subscribe
                        // will replay these.
                        return@launch
                    } catch (_: Throwable) {
                        // Don't let a misbehaving caller break replay for
                        // the rest of the queue.
                    }
                }
            }
        }
    }

    /**
     * Best-effort unregister. A trailing notification may still arrive if a
     * concurrent eviction snapshotted the registry list before this call
     * removed the entry; documented as eventual.
     */
    override fun unsubscribeEvictionNotices(callback: com.adsamcik.mindlayer.IClientCallback?) {
        authorizeCall(cost = 0.0)
        val uid = Binder.getCallingUid()
        if (callback == null) return
        evictionRegistry.unregister(uid, callback)
    }
}




