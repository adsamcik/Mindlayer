package com.mindlayer.sdk

import android.graphics.Bitmap
import kotlinx.coroutines.flow.Flow
import java.io.File

/**
 * Convenience wrapper that binds a [Mindlayer] client to a single session,
 * removing the need to pass `sessionId` on every call.
 *
 * ```
 * val session = mindlayer.session(sessionId)
 * session.chat("Hello!").collect { event -> ... }
 * ```
 */
class MindlayerSession internal constructor(
    private val client: Mindlayer,
    val sessionId: String,
) {
    /** Send a text message and stream events. */
    fun chat(text: String): Flow<MindlayerEvent> =
        client.chat(sessionId, text)

    /** Send a text + bitmap message and stream events. */
    fun chatWithImage(text: String, bitmap: Bitmap): Flow<MindlayerEvent> =
        client.chatWithImage(sessionId, text, bitmap)

    /** Send a text + audio file message and stream events. */
    fun chatWithAudio(text: String, audioFile: File): Flow<MindlayerEvent> =
        client.chatWithAudio(sessionId, text, audioFile)

    /** @see Mindlayer.chatOnce */
    suspend fun chatOnce(text: String): String =
        client.chatOnce(sessionId, text)

    /** @see Mindlayer.chatWithImageOnce */
    suspend fun chatWithImageOnce(text: String, bitmap: Bitmap): String =
        client.chatWithImageOnce(sessionId, text, bitmap)

    /** Submit a tool result for continued inference. */
    suspend fun submitToolResult(
        requestId: String,
        toolName: String,
        resultJson: String,
    ) = client.submitToolResult(requestId, toolName, resultJson)

    /** Cancel an in-flight inference. */
    suspend fun cancelInference(requestId: String) =
        client.cancelInference(requestId)

    /** Destroy this session on the server. */
    suspend fun destroy() = client.destroySession(sessionId)
}
