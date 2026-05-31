package com.adsamcik.mindlayer.sdk

import android.graphics.Bitmap
import java.io.File

/**
 * Active inference session (Spike E §0 surface).
 *
 * Obtained from [Mindlayer.openSession] or scoped by [Mindlayer.withSession].
 * Implements [AutoCloseable] so `use { }` performs a fire-and-forget close;
 * [closeAsync] awaits the AIDL acknowledgement.
 *
 * Nothing implements this interface in C1 — behaviour lands in C2.
 */
@MindlayerDsl
interface MindlayerSession : AutoCloseable {
    val id: String

    suspend fun ask(prompt: String): String
    suspend fun describe(prompt: String, image: Bitmap): String
    suspend fun infer(build: InferenceRequest.Builder.() -> Unit): InferenceHandle

    /** Suspending close — awaits the AIDL ack. [close] is fire-and-forget for `use`. */
    suspend fun closeAsync()
}

/**
 * Convenience wrapper that binds a [MindlayerImpl] client to a single session,
 * removing the need to pass `sessionId` on every call.
 *
 * This is the existing legacy session convenience type (formerly the public
 * `MindlayerSession` concrete class), renamed to `SessionHandle` and made
 * `internal` so the public [MindlayerSession] name is free for the Spike-E
 * interface above. Its `client` is typed [MindlayerImpl] so it can reach the
 * `@Deprecated(HIDDEN)` legacy chat overrides and the impl-only carrier methods.
 */
internal class SessionHandle internal constructor(
    private val client: MindlayerImpl,
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

    /** Submit a deferred text message and fetch the result later. */
    suspend fun chatDeferred(text: String): com.adsamcik.mindlayer.DeferredHandle =
        client.chatDeferred(sessionId, text)

    /** @see MindlayerImpl.chatOnce */
    suspend fun chatOnce(text: String): String =
        client.chatOnce(sessionId, text)

    /** @see MindlayerImpl.chatWithImageOnce */
    suspend fun chatWithImageOnce(text: String, bitmap: Bitmap): String =
        client.chatWithImageOnce(sessionId, text, bitmap)

    /** @see MindlayerImpl.chatWithAudioOnce */
    suspend fun chatWithAudioOnce(text: String, audioFile: File): String =
        client.chatWithAudioOnce(sessionId, text, audioFile)

    /** @see MindlayerImpl.chatTextFlow */
    fun chatTextFlow(text: String): kotlinx.coroutines.flow.Flow<String> =
        client.chatTextFlow(sessionId, text)

    /** @see MindlayerImpl.chatFullTextFlow */
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
