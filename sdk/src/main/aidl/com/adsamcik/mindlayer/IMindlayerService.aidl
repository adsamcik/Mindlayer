package com.adsamcik.mindlayer;

import com.adsamcik.mindlayer.SessionConfig;
import com.adsamcik.mindlayer.RequestMeta;
import com.adsamcik.mindlayer.ImageTransfer;
import com.adsamcik.mindlayer.AudioTransfer;
import com.adsamcik.mindlayer.MediaPart;
import com.adsamcik.mindlayer.ToolResult;
import com.adsamcik.mindlayer.ServiceStatus;
import com.adsamcik.mindlayer.EngineInfo;
import com.adsamcik.mindlayer.SessionInfo;
import com.adsamcik.mindlayer.ServiceCapabilities;
import com.adsamcik.mindlayer.CancelResult;
import com.adsamcik.mindlayer.ToolSubmitResult;
import com.adsamcik.mindlayer.DiagnosticsSnapshot;
import com.adsamcik.mindlayer.DeferredHandle;
import com.adsamcik.mindlayer.DeferredResult;
import com.adsamcik.mindlayer.IClientCallback;

interface IMindlayerService {
    // Client liveness — caller passes a Binder token; service linkToDeath's on it
    // so that if the calling process dies, all sessions it owns are torn down.
    // Idempotent per calling UID; clients should call this once after connect.
    void registerClient(IBinder clientToken);

    // Session management
    String createSession(in SessionConfig config);
    void destroySession(String sessionId);
    SessionInfo getSessionInfo(String sessionId);
    List<SessionInfo> listSessions();

    // Inference — client creates pipe, passes write end to service
    // Service writes length-prefixed JSON events to the pipe
    void infer(in RequestMeta meta, in ImageTransfer image,
               in AudioTransfer audio, in ParcelFileDescriptor eventWriteEnd);
    void cancelInference(String requestId);

    // Function calling — inject tool results back
    void submitToolResult(String requestId, in ToolResult result);

    // Service status
    ServiceStatus getStatus();
    EngineInfo getEngineInfo();

    // Diagnostic dump for troubleshooting
    String getDiagnostics();

    // Pre-warm the engine in the background (fire-and-forget)
    oneway void prewarm(String backend);

    // v0.4: synchronous prewarm. Suspends the binder transaction until the
    // engine is initialized (or the wall-clock timeout expires) and returns
    // the actually-active backend (which may differ from the requested one
    // when the requested backend was unavailable and a fallback kicked in).
    // Capability-gated via ServiceCapabilities.FEATURE_PREWARM_AWAIT.
    String prewarmAndAwait(String backend, long timeoutMs);

    // Revoke approval for an installed caller package and tear down any
    // sessions it currently owns. Self-UID only (the dashboard process)
    // — external callers are rejected at the ServiceBinder authz gate.
    void revokeApp(String packageName);

    // Capability handshake. SDK calls this once after registerClient and
    // caches the result. Old SDKs that don't know about the method ignore
    // it; new SDKs against old services catch NoSuchMethodError and fall
    // back to ServiceCapabilities.v0Baseline.
    ServiceCapabilities getCapabilities();

    // v0.4 multimodal: ordered list of media attachments. Successor to
    // infer() — the legacy method stays for compatibility. Capability-gated
    // via ServiceCapabilities.FEATURE_MEDIA_LIST.
    void inferMulti(in RequestMeta meta, in List<MediaPart> media,
                    in ParcelFileDescriptor eventWriteEnd);

    // v0.4 detailed cancel/submit results. Capability-gated via
    // ServiceCapabilities.FEATURE_DETAILED_CANCEL. Anti-enumeration:
    // UNKNOWN/REQUEST_GONE collapse "never existed" with "owned by
    // another UID" so cross-UID information doesn't leak.
    CancelResult cancelInferenceV2(String requestId);
    ToolSubmitResult submitToolResultV2(String requestId, in ToolResult result);

    // v0.4 typed diagnostics snapshot for programmatic consumers
    // (dashboard polling, external monitoring). Capability-gated via
    // ServiceCapabilities.FEATURE_TYPED_DIAGNOSTICS. Legacy
    // getDiagnostics() stays as the human-readable bug-report surface.
    DiagnosticsSnapshot getDiagnosticsTyped();

    // v0.4 eviction callback. SDK registers an IClientCallback once after
    // connect; service fires onSessionEvicted for every session retired
    // while the callback is registered (memory pressure, expiration, OOM
    // kill, explicit revoke). Callback's binder is linkToDeath'd so a
    // crashed client doesn't keep stale callbacks alive.
    // Capability-gated via ServiceCapabilities.FEATURE_EVICTION_CALLBACK.
    void subscribeEvictionNotices(IClientCallback callback);
    void unsubscribeEvictionNotices(IClientCallback callback);

    // v0.6 deferred inference: submit now, fetch result later, and receive
    // push completion through IClientCallback.onDeferredInferenceComplete.
    // Capability-gated via ServiceCapabilities.FEATURE_DEFERRED_INFERENCE.
    DeferredHandle inferDeferred(in RequestMeta meta, in List<MediaPart> media);
    DeferredResult fetchDeferredResult(String requestId);
    CancelResult cancelDeferredInference(String requestId);
    void acknowledgeDeferredResult(String requestId);
}
