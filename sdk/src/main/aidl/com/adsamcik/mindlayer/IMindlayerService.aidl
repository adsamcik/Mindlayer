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
import com.adsamcik.mindlayer.EmbeddingRequest;
import com.adsamcik.mindlayer.EmbeddingResult;
import com.adsamcik.mindlayer.EmbeddingBatchResult;
import com.adsamcik.mindlayer.EmbeddingBatchTransfer;
import com.adsamcik.mindlayer.VectorBlobHandle;
import com.adsamcik.mindlayer.OcrSessionConfig;
import com.adsamcik.mindlayer.OcrFrameMeta;
import com.adsamcik.mindlayer.OcrFrameAck;
import com.adsamcik.mindlayer.OcrSessionState;
import com.adsamcik.mindlayer.OcrLimits;
import com.adsamcik.mindlayer.OcrImageOptions;
import com.adsamcik.mindlayer.OcrImageResult;
import com.adsamcik.mindlayer.HealthCheck;
import com.adsamcik.mindlayer.IClientCallback;

interface IMindlayerService {
    // Client liveness ? caller passes a Binder token; service linkToDeath's on it
    // so that if the calling process dies, all sessions it owns are torn down.
    // Idempotent per calling UID; clients should call this once after connect.
    void registerClient(IBinder clientToken);

    // Session management
    String createSession(in SessionConfig config);
    void destroySession(String sessionId);
    SessionInfo getSessionInfo(String sessionId);
    List<SessionInfo> listSessions();

    // Inference ? client creates pipe, passes write end to service
    // Service writes length-prefixed JSON events to the pipe
    void infer(in RequestMeta meta, in ImageTransfer image,
               in AudioTransfer audio, in ParcelFileDescriptor eventWriteEnd);
    void cancelInference(String requestId);

    // Function calling ? inject tool results back
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
    // ? external callers are rejected at the ServiceBinder authz gate.
    void revokeApp(String packageName);

    // Capability handshake. SDK calls this once after registerClient and
    // caches the result. Old SDKs that don't know about the method ignore
    // it; new SDKs against old services catch NoSuchMethodError and fall
    // back to ServiceCapabilities.v0Baseline.
    ServiceCapabilities getCapabilities();

    // v0.4 multimodal: ordered list of media attachments. Successor to
    // infer() ? the legacy method stays for compatibility. Capability-gated
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

    // v0.7 embeddings: text-only via EmbeddingGemma-300M.
    // Capability-gated via ServiceCapabilities.FEATURE_EMBEDDINGS.
    EmbeddingResult embed(in EmbeddingRequest req);
    EmbeddingBatchResult embedBatch(in List<EmbeddingRequest> reqs);
    EmbeddingBatchTransfer embedBatchShm(in List<EmbeddingRequest> reqs);
    DeferredHandle embedBatchDeferred(in List<EmbeddingRequest> reqs);
    VectorBlobHandle fetchEmbeddingBatchResult(String requestId);
    CancelResult cancelEmbeddingBatch(String requestId);
    void acknowledgeEmbeddingBatchResult(String requestId);
    CancelResult cancelEmbed(String requestId);

    // v0.8 multi-frame OCR / parsing.
    //
    // Long-lived per-caller OCR session that accepts a stream of camera frames
    // (or any image), runs service-side presort + PaddleOCR (via the same
    // LiteRT runtime as Gemma) + cross-frame fusion, and emits typed events
    // on a caller-owned PFD pipe attached via streamOcrEvents(...).
    //
    // Architectural notes:
    //  - Frames reuse MediaPart so SharedMemory zero-copy transfer is in place.
    //  - Strategy A (reset-per-frame) KV — only viable on LiteRT-LM 0.10.x
    //    Kotlin (no truncate/logprobs/max_output_tokens in the public API).
    //  - LiteRT is the single inference runtime; ONNX Runtime is excluded for
    //    architectural reasons (would compete with LiteRT-LM for CPU/GPU/RAM).
    //  - The end-of-scan Gemma extraction is async/background — runs only on
    //    accepted frames after presort convergence, not continuously.
    //
    // Capability-gated via ServiceCapabilities.FEATURE_OCR_SESSION.
    // Numeric caps advertised via getOcrLimits() (separate parcelable; the
    // ServiceCapabilities parcelable itself is wire-frozen).
    String createOcrSession(in OcrSessionConfig cfg);
    OcrFrameAck pushOcrFrame(String sessionId, in MediaPart frame, in OcrFrameMeta meta);
    void streamOcrEvents(String sessionId, in ParcelFileDescriptor eventWriteEnd);
    OcrSessionState getOcrSessionState(String sessionId);
    void finalizeOcrSession(String sessionId);
    void closeOcrSession(String sessionId);
    OcrLimits getOcrLimits();

    // v0.9 single-image OCR — one-shot synchronous OCR for callers that
    // have a single image and just want recognized text (gallery picker,
    // sharesheet target, "scan this receipt" one-shot, screenshot text
    // extraction). Bypasses the session pipeline ceremony (createSession /
    // pushFrame / streamEvents / finalize) so there is no per-call setup
    // cost, no event-pipe wiring, and no multi-frame fusion.
    //
    // The same PaddleOCR engine that powers pushOcrFrame runs here — the
    // engine layer's per-instance mutex serialises ocrImage calls with
    // session pushes, so the two APIs share throughput rather than
    // competing on the native delegate.
    //
    // When OcrImageOptions.runLlmExtraction is true, the service also runs
    // the structured-extraction Gemma pass against the recognized lines
    // and returns the fields + raw JSON in the same response. This adds
    // the LLM decode latency (~2-5s) to the call. The non-LLM path
    // (the default) returns OCR-only in ~1-2s on real hardware.
    //
    // Capability-gated via ServiceCapabilities.FEATURE_OCR_IMAGE_ONESHOT.
    // Old services that don't know about the method raise
    // NoSuchMethodError / AbstractMethodError on the binder stub;
    // capability-aware SDKs check the flag before calling.
    OcrImageResult ocrImage(in MediaPart image, in OcrImageOptions options);

    // v0.8.1 health check. Lightweight liveness probe — returns the
    // service's wall-clock + uptime + per-engine state + apiVersion.
    // Deliberately bypasses the allowlist gate (a co-signed peer in
    // pending-approval can still confirm the service is alive) and
    // charges zero rate-limit cost. Capability-gated via
    // ServiceCapabilities.FEATURE_HEALTH_CHECK. Old services that don't
    // know about the method raise NoSuchMethodError / AbstractMethodError
    // on the binder stub — capability-aware SDKs catch and fall back to
    // getStatus() (heavier) or assume the service is alive when this
    // call simply doesn't exist.
    HealthCheck ping();
}


