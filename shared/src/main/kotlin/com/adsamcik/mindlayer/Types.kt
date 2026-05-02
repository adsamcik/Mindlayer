package com.adsamcik.mindlayer

import android.os.Parcelable
import android.os.ParcelFileDescriptor
import kotlinx.parcelize.Parcelize

@Parcelize
data class HistoryTurn(
    /**
     * One of `com.adsamcik.mindlayer.shared.Role`'s constants
     * (`"user"` / `"model"` / `"tool"` / `"system"`). Validated at the
     * AIDL boundary; invalid values are rejected with a typed
     * `INVALID_SESSION_CONFIG` error. Note: the wire spelling is `"model"`
     * for assistant responses — `"assistant"` is **not** valid.
     */
    val role: String,
    val text: String,
) : Parcelable {
    override fun toString(): String =
        "HistoryTurn(role=$role, text=<redacted:${text.length}>)"
}

@Parcelize
data class SessionConfig(
    val sessionId: String? = null,
    val systemPrompt: String? = null,
    val maxTokens: Int = 4096,
    val backend: String = "GPU",
    val samplerTopK: Int = 40,
    val samplerTopP: Float = 0.95f,
    val samplerTemperature: Float = 0.7f,
    val toolsJson: String? = null,
    val extraContextJson: String? = null,
    val initialHistory: List<HistoryTurn>? = null,
    /** Session expiration in milliseconds. Default: 14 days. */
    val expirationMs: Long = 14L * 24 * 60 * 60 * 1000,
) : Parcelable {
    override fun toString(): String =
        "SessionConfig(sessionId=$sessionId, systemPrompt=${if (systemPrompt == null) "null" else "<redacted:${systemPrompt.length}>"}, " +
            "maxTokens=$maxTokens, backend=$backend, samplerTopK=$samplerTopK, samplerTopP=$samplerTopP, " +
            "samplerTemperature=$samplerTemperature, toolsJson=${if (toolsJson == null) "null" else "<redacted:${toolsJson.length}>"}, " +
            "extraContextJson=${if (extraContextJson == null) "null" else "<redacted:${extraContextJson.length}>"}, " +
            "initialHistoryCount=${initialHistory?.size ?: 0}, expirationMs=$expirationMs)"
}

@Parcelize
data class RequestMeta(
    val requestId: String,
    val sessionId: String,
    val textContent: String? = null,
    /**
     * Vestigial — only validated against `com.adsamcik.mindlayer.shared.Role`
     * by `IpcInputValidator`, never read by the service. Defaults to
     * `"user"` because that is the only sensible value at the request
     * boundary today (tool results flow through `submitToolResult`, not
     * `infer`). Frozen on the wire; do not repurpose.
     */
    val role: String = "user",
    /**
     * Vestigial — declared on the wire but not consumed anywhere in the
     * service. Reserved for a future per-request priority hint; until
     * then the field is wire-stable and should be left at the default.
     * Frozen; do not repurpose.
     */
    val priority: Int = 0,
) : Parcelable {
    override fun toString(): String =
        "RequestMeta(requestId=$requestId, sessionId=$sessionId, role=$role, " +
            "textContent=${if (textContent == null) "null" else "<redacted:${textContent.length}>"}, priority=$priority)"
}

@Parcelize
data class ImageTransfer(
    val requestId: String,
    val width: Int,
    val height: Int,
    val pixelFormat: Int,
    val rowStride: Int,
    val payloadBytes: Int,
    val source: ParcelFileDescriptor,
    val isSharedMemory: Boolean = true,
    val mimeType: String? = null,
) : Parcelable

@Parcelize
data class AudioTransfer(
    val requestId: String,
    val mimeType: String = "audio/wav",
    val source: ParcelFileDescriptor,
    val isSharedMemory: Boolean = false,
    val durationMs: Long? = null,
) : Parcelable

@Parcelize
data class ToolResult(
    val requestId: String,
    val callId: String,
    val toolName: String,
    val resultJson: String,
) : Parcelable {
    override fun toString(): String =
        "ToolResult(requestId=$requestId, callId=$callId, toolName=$toolName, " +
            "resultJson=<redacted:${resultJson.length}>)"
}

@Parcelize
data class ServiceStatus(
    val isEngineLoaded: Boolean,
    val activeSessionCount: Int,
    val activeInferenceCount: Int,
    val backend: String,
    val thermalBand: String,
    val isForeground: Boolean,
    val uptimeMs: Long,
    val memoryPressure: String = "NORMAL",
    val availableRamMb: Long = 0,
    val totalRamMb: Long = 0,
    val maxSessions: Int = 0,
    val headroom: Float? = null,
) : Parcelable

@Parcelize
data class EngineInfo(
    /** Identifier of the single model Mindlayer selected on this device. */
    val modelId: String,
    val modelSizeBytes: Long,
    val backend: String,
    val maxTokens: Int,
    val initTimeSeconds: Float,
    val lastPrefillToksPerSec: Float,
    val lastDecodeToksPerSec: Float,
) : Parcelable

@Parcelize
data class SessionInfo(
    val sessionId: String,
    val backend: String,
    val maxTokens: Int,
    val currentTokenCount: Int,
    val turnCount: Int,
    val createdAtMs: Long,
    val lastAccessedAtMs: Long,
    val isStreaming: Boolean,
    /** Session expiration duration in milliseconds. */
    val expirationMs: Long = 14L * 24 * 60 * 60 * 1000,
    /** Absolute wall-clock time when this session expires. */
    val expiresAtMs: Long = safeExpiresAtMs(createdAtMs, expirationMs),
) : Parcelable

private fun safeExpiresAtMs(createdAtMs: Long, expirationMs: Long): Long {
    if (expirationMs <= 0) return createdAtMs
    val remaining = Long.MAX_VALUE - createdAtMs
    return if (expirationMs > remaining) Long.MAX_VALUE else createdAtMs + expirationMs
}
