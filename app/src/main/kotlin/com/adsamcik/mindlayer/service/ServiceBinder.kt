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
import com.adsamcik.mindlayer.service.engine.SessionOwnerToken
import com.adsamcik.mindlayer.service.engine.ThermalMonitor
import com.adsamcik.mindlayer.service.logging.DiagnosticExporter
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.loggable
import com.adsamcik.mindlayer.service.security.AllowlistStore
import com.adsamcik.mindlayer.service.security.CallerIdentity
import com.adsamcik.mindlayer.service.security.CallerVerifier
import com.adsamcik.mindlayer.service.security.IpcInputValidator
import com.adsamcik.mindlayer.service.security.RateLimiter
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import java.io.File
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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
    private val allowlistStore: AllowlistStore? = AllowlistStore(service),
    private val rateLimiter: RateLimiter = RateLimiter(),
    private val logRepository: LogRepository? = null,
) : IMindlayerService.Stub() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

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
         * Logical API version surfaced via [getCapabilities]. Bumped whenever
         * a new method is appended to the AIDL interface. v1 was the pre-
         * `inferMulti` surface; v2 added [inferMulti].
         */
        const val CURRENT_API_VERSION = 2

        /**
         * Maximum [com.adsamcik.mindlayer.MediaPart] entries accepted in a
         * single [inferMulti] call. Today's engine consumes at most one
         * image + one audio (validator enforces this) so the effective cap
         * is `2`; once litert-lm #1874 lifts multi-image, the validator
         * loosens and this constant rises without a wire-level change.
         */
        const val MAX_MEDIA_PARTS_PER_REQUEST = 2

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
        )
    }

    private data class ClientRegistration(
        override val ownerUid: Int,
        val registrationId: String,
    ) : SessionOwnerToken

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
        val identity = callerVerifier.identify(context, uid)
            ?: run {
                // F-033: no identity at all. We have no (pkg, sig) to bucket
                // on except the calling UID — throttle hostile flooders that
                // can't even produce a valid identity before we waste any more
                // work on them.
                if (!rateLimiter.tryAcquireRejected(uid)) {
                    MindlayerLog.w(TAG, "Reject flood from uid=$uid (no identity)")
                    throw SecurityException("Rate limit exceeded")
                }
                throw SecurityException(
                    "Caller identity could not be verified (uid=$uid)"
                )
            }

        val store = allowlistStore
        if (store != null && !store.isAllowed(identity.packageName, identity.signingCertSha256)) {
            // F-033: throttle BEFORE recordPending so a hostile caller cannot
            // saturate FileLock + fsync `pending.json` indefinitely. The
            // rejected bucket is independent of the main-traffic bucket so a
            // legitimate caller's first-launch race is unaffected.
            if (!rateLimiter.tryAcquireRejected(uid)) {
                MindlayerLog.w(TAG, "Reject flood: ${identity.packageName} (uid=$uid)")
                throw SecurityException("Rate limit exceeded")
            }
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

        if (!rateLimiter.tryAcquire(uid, cost)) {
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
                "Ownership violation: uid=$uid tried to touch session owned by $owner",
                sessionId = sessionId,
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
    }

    // ---- Session management ------------------------------------------------

    override fun createSession(config: SessionConfig): String {
        val identity = authorizeCall()
        val uid = Binder.getCallingUid()
        val ownerToken = requireRegisteredClient()
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
        } catch (e: IllegalArgumentException) {
            throw typedBinderException(
                MindlayerErrorCode.INVALID_SESSION_CONFIG,
                "Invalid SessionConfig: ${e.message}",
            )
        }
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
        MindlayerLog.d(TAG, "destroySession: $sessionId", sessionId = sessionId)
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
        val identity = authorizeCall()
        val uid = Binder.getCallingUid()
        val ownerToken = requireRegisteredClient()

        // Validate every client-supplied identifier and string at AIDL
        // ingress so the downstream code never sees malformed values.
        try {
            IpcInputValidator.validateRequestMeta(meta)
            image?.let { IpcInputValidator.validateImageTransfer(it, MAX_MEDIA_BYTES) }
            audio?.let { IpcInputValidator.validateAudioTransfer(it) }
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
            "infer request from ${identity.packageName}",
            requestId = meta.requestId,
            sessionId = meta.sessionId,
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
            }
        } catch (t: Throwable) {
            activeInferenceOwners.remove(scopedKey)
            if (activeInferenceUids.remove(scopedKey) != null) {
                rateLimiter.endInference(uid)
            }
            throw t
        }
    }

    /**
     * v0.4 multimodal inference. Successor to [infer] — accepts an ordered
     * list of [com.adsamcik.mindlayer.MediaPart] so future engines can
     * consume multiple images / video / documents without another wire-break.
     *
     * Today's engine constraint (see `MediaPart` KDoc): the validator
     * rejects multi-image, multi-audio, and any [com.adsamcik.mindlayer.MediaPart.KIND_VIDEO]
     * / [com.adsamcik.mindlayer.MediaPart.KIND_DOCUMENT]. Within those
     * limits the binder picks the first image and first audio (preserving
     * ordering by extracting in list order) and dispatches to the existing
     * orchestrator. When litert-lm #1874 lifts multi-image, the orchestrator
     * gains an ordered-list path and the validator caps loosen — wire shape
     * doesn't change.
     */
    override fun inferMulti(
        meta: RequestMeta,
        media: List<com.adsamcik.mindlayer.MediaPart>?,
        eventWriteEnd: ParcelFileDescriptor,
    ) {
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
            }
        } catch (t: Throwable) {
            activeInferenceOwners.remove(scopedKey)
            if (activeInferenceUids.remove(scopedKey) != null) {
                rateLimiter.endInference(uid)
            }
            throw t
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
                    requestId = requestId,
                )
                return
            }
            requireRequestOwner(key)
            key
        }
        MindlayerLog.d(TAG, "cancelInference request", requestId = requestId)
        // Concurrency slot is released by the orchestrator's completion hook.
        orchestrator.cancelInference(scopedKey)
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
            "submitToolResult for call ${result.callId.loggable()} tool=${result.toolName}",
            requestId = requestId,
        )
        orchestrator.toolCallBridge.submitResult(
            scopedKey = scopedKey,
            callId = result.callId,
            toolName = result.toolName,
            resultJson = result.resultJson,
        )
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
        val callingUid = Binder.getCallingUid()
        if (callingUid != Process.myUid()) {
            // Don't even leak the existence of the method to external callers.
            MindlayerLog.w(TAG, "revokeApp rejected: external uid=$callingUid")
            throw SecurityException("revokeApp: self-UID only")
        }
        // Inline package-name shape check — letters, digits, underscores,
        // dots; max 255 bytes (Android PackageManager limit). Reject empty
        // and obviously bogus values without echoing them in error text.
        if (packageName.isEmpty() || packageName.length > 255 ||
            !packageName.all { it.isLetterOrDigit() || it == '.' || it == '_' }) {
            throw SecurityException("Invalid packageName")
        }
        val store = allowlistStore
        if (store == null) {
            MindlayerLog.w(TAG, "revokeApp called with no allowlistStore configured")
            return
        }

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
            // closeAllOwnedBy returns the destroyed session ids for logging.
            onClientDisconnected(targetUid)
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
        // Coalesce concurrent prewarms — the engine is mutex-protected
        // internally but we don't need to spawn more than one waiting
        // launcher per UID (defends against `oneway` flooding).
        val existing = prewarmJob.get()
        if (existing != null && existing.isActive) {
            MindlayerLog.d(TAG, "prewarm already in progress; coalescing")
            return
        }
        MindlayerLog.d(TAG, "prewarm: backend=$safeBackend")
        val job = scope.launch {
            try {
                engineManager.initialize(
                    preferredBackend = safeBackend,
                    maxTokens = 4096,
                )
            } catch (e: Exception) {
                MindlayerLog.w(TAG, "Prewarm failed: ${e.message}")
            }
        }
        prewarmJob.set(job)
    }

    /** Coalesces concurrent prewarms (F-057). */
    private val prewarmJob = AtomicReference<kotlinx.coroutines.Job?>(null)

    // ---- Status ------------------------------------------------------------

    override fun getStatus(): ServiceStatus {
        // F-064: cheap call — quarter-cost so dashboard polling doesn't
        // dominate the per-UID budget for external callers either.
        authorizeCall(cost = 0.25)
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

        return ServiceStatus(
            isEngineLoaded = engineManager.isInitialized,
            activeSessionCount = visibleActiveSessions,
            activeInferenceCount = visibleActiveInferences,
            backend = engineManager.currentBackend,
            thermalBand = thermalPolicy.band.name,
            isForeground = visibleActiveInferences > 0,
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

    override fun getCapabilities(): com.adsamcik.mindlayer.ServiceCapabilities {
        // Cheap probe — quarter-cost so first-launch handshake doesn't burn
        // budget. Still gated by authorizeCall so un-approved peers go
        // through the standard pending-approval path rather than getting a
        // free fingerprinting endpoint.
        authorizeCall(cost = 0.25)
        return com.adsamcik.mindlayer.ServiceCapabilities(
            apiVersion = CURRENT_API_VERSION,
            supportedFeatures = SUPPORTED_FEATURES,
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
