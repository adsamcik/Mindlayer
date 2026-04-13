package com.mindlayer

import android.os.Parcelable
import android.os.ParcelFileDescriptor
import kotlinx.parcelize.Parcelize

@Parcelize
data class HistoryTurn(
    val role: String,   // "user" | "model" | "tool"
    val text: String,
) : Parcelable

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
    /** Target model ID. `null` means use the default model. */
    val modelId: String? = null,
) : Parcelable

@Parcelize
data class RequestMeta(
    val requestId: String,
    val sessionId: String,
    val textContent: String? = null,
    val role: String = "user",
    val priority: Int = 0,
) : Parcelable

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
    val toolName: String,
    val resultJson: String,
) : Parcelable

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
    @Deprecated("Internal tuning parameter, use Mindlayer's default backend selection")
    val recommendedBackend: String = "GPU",
    @Deprecated("Internal tuning parameter, not consumer-facing")
    val burstSeconds: Int = 12,
    @Deprecated("Internal tuning parameter, not consumer-facing")
    val restSeconds: Int = 0,
    @Deprecated("Internal tuning parameter, not consumer-facing")
    val chunkTokens: Int = 128,
    val headroom: Float? = null,
) : Parcelable

@Parcelize
data class EngineInfo(
    @Deprecated("Internal file path, not consumer-facing. Use modelId from listModels() instead.")
    val modelPath: String,
    val modelSizeBytes: Long,
    val backend: String,
    val maxTokens: Int,
    val initTimeSeconds: Float,
    val lastPrefillToksPerSec: Float,
    val lastDecodeToksPerSec: Float,
    val modelId: String = modelPath.substringAfterLast("/").removeSuffix(".litertlm"),
) : Parcelable

@Parcelize
data class ModelInfoParcel(
    val id: String,
    val displayName: String,
    val sizeBytes: Long,
    val isDefault: Boolean,
    val isLoaded: Boolean,
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
) : Parcelable
