package com.adsamcik.mindlayer.sdk

import android.graphics.Bitmap
import java.io.File

/**
 * Convenience wrapper that binds a [Mindlayer] client to a single session,
 * removing the need to pass `sessionId` on every call.
 *
 * ```
 * val session = mindlayer.session(sessionId)
 * val handle = session.chat("Hello!")
 * handle.events.collect { event -> ... }
 * ```
 */
class MindlayerSession internal constructor(
    private val client: Mindlayer,
    val sessionId: String,
) {
    /** Send a text message and return an [InferenceHandle]. */
    suspend fun chat(text: String): InferenceHandle =
        client.chat(sessionId, text)

    /** Send a text + bitmap message and return an [InferenceHandle]. */
    suspend fun chatWithImage(text: String, bitmap: Bitmap): InferenceHandle =
        client.chatWithImage(sessionId, text, bitmap)

    /** Send a text + audio file message and return an [InferenceHandle]. */
    suspend fun chatWithAudio(text: String, audioFile: File): InferenceHandle =
        client.chatWithAudio(sessionId, text, audioFile)

    /** @see Mindlayer.chatOnce */
    suspend fun chatOnce(text: String): String =
        client.chatOnce(sessionId, text)

    /** @see Mindlayer.chatWithImageOnce */
    suspend fun chatWithImageOnce(text: String, bitmap: Bitmap): String =
        client.chatWithImageOnce(sessionId, text, bitmap)

    /** @see Mindlayer.chatWithAudioOnce */
    suspend fun chatWithAudioOnce(text: String, audioFile: File): String =
        client.chatWithAudioOnce(sessionId, text, audioFile)

    /** @see Mindlayer.chatTextFlow */
    fun chatTextFlow(text: String): kotlinx.coroutines.flow.Flow<String> =
        client.chatTextFlow(sessionId, text)

    /** @see Mindlayer.chatFullTextFlow */
    fun chatFullTextFlow(text: String): kotlinx.coroutines.flow.Flow<String> =
        client.chatFullTextFlow(sessionId, text)

    /** Submit a tool result for continued inference using the ToolCall callId. */
    suspend fun submitToolResult(
        requestId: String,
        callId: String,
        toolName: String,
        resultJson: String,
    ) = client.submitToolResult(requestId, callId, toolName, resultJson)

    /** Cancel an in-flight inference. */
    suspend fun cancelInference(requestId: String) =
        client.cancelInference(requestId)

    /** Delete this session and release its resources on the service. */
    suspend fun delete() = client.destroySession(sessionId)
}
