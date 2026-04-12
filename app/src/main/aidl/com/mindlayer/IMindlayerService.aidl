package com.mindlayer;

import com.mindlayer.SessionConfig;
import com.mindlayer.RequestMeta;
import com.mindlayer.ImageTransfer;
import com.mindlayer.AudioTransfer;
import com.mindlayer.ToolResult;
import com.mindlayer.ServiceStatus;
import com.mindlayer.EngineInfo;
import com.mindlayer.SessionInfo;

interface IMindlayerService {
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

    // History replay — inject a completed turn without triggering inference
    void replayTurn(String sessionId, String role, String text);

    // Service status
    ServiceStatus getStatus();
    EngineInfo getEngineInfo();

    // Diagnostic dump for troubleshooting
    String getDiagnostics();

    // Pre-warm the engine in the background (fire-and-forget)
    oneway void prewarm(String backend);
}
