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
}

