package com.adsamcik.mindlayer.sdk

import android.graphics.Bitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

/**
 * A multi-turn conversation with the LLM. Manages session lifecycle,
 * connection state, and memory automatically.
 *
 * Create via [Mindlayer.conversation] or use [Mindlayer.chat] for one-offs.
 *
 * ```kotlin
 * val conv = mindlayer.conversation {
 *     systemPrompt("You are a helpful assistant.")
 * }
 * val response = conv.chat("Hello!")
 * val followUp = conv.chat("Tell me more")
 * conv.close()
 * ```
 *
 * Implements [AutoCloseable] for use-with-resources patterns.
 */
class Conversation internal constructor(
    private val client: Mindlayer,
    private val config: ConversationConfig,
) : AutoCloseable {
    private var sessionId: String? = null
    private var closed = false
    private val mutex = Mutex()

    /**
     * Send a text message and get the complete response.
     * Automatically creates the session on first call.
     * Thread-safe — concurrent calls are serialized.
     */
    suspend fun chat(text: String): String =
        withSession { sid -> client.chatOnce(sid, text) }

    /**
     * Send a text + image message and get the complete response.
     */
    suspend fun chat(text: String, image: Bitmap): String =
        withSession { sid -> client.chatWithImageOnce(sid, text, image) }

    /**
     * Send a text + audio message and get the complete response.
     */
    suspend fun chat(text: String, audio: File): String =
        withSession { sid ->
            // Audio uses the streaming API, collect to string
            val handle = client.chatWithAudio(sid, text, audio)
            collectHandle(handle)
        }

    /**
     * Stream a text response (for real-time UI updates).
     * Returns an [InferenceHandle] with the event flow.
     *
     * Note: the session is created eagerly (suspending) before returning
     * the handle. Use the suspend [chat] overloads for simpler usage.
     */
    suspend fun chatStream(text: String): InferenceHandle {
        val sid = ensureSession()
        return client.chat(sid, text)
    }

    /**
     * Stream a text + image response.
     */
    suspend fun chatStream(text: String, image: Bitmap): InferenceHandle {
        val sid = ensureSession()
        return client.chatWithImage(sid, text, image)
    }

    /**
     * Close this conversation and release resources.
     * Safe to call multiple times.
     */
    override fun close() {
        if (closed) return
        closed = true
        val sid = sessionId ?: return
        sessionId = null
        try {
            // Best-effort cleanup — service may already be disconnected
            val service = client.connection.getService() ?: return
            service.destroySession(sid)
        } catch (_: Exception) {
            // Session will be cleaned up server-side on timeout
        }
    }

    /**
     * Get the full turn history of this conversation.
     * Returns all completed turns in chronological order.
     */
    suspend fun history(): List<TurnPreview> {
        val sid = sessionId ?: return emptyList()
        return client.getHistory(sid)
    }

    // -- Internals ----------------------------------------------------------------

    private suspend fun ensureSession(): String = mutex.withLock {
        check(!closed) { "Conversation is closed" }
        sessionId?.let { return it }

        client.awaitConnected()
        val sid = client.createSession {
            config.systemPrompt?.let { systemPrompt(it) }
            maxTokens(config.maxTokens)
            temperature(config.temperature)
            topK(config.topK)
            topP(config.topP)
            expirationMs(config.expiration.inWholeMilliseconds)
        }
        sessionId = sid
        sid
    }

    /**
     * Run [block] with a valid session ID.
     * If the call fails due to session eviction, recreate and retry once.
     * Only retries on [android.os.RemoteException] or [MindlayerException]
     * (service-level errors), not protocol/logic errors.
     */
    private suspend fun <T> withSession(block: suspend (String) -> T): T {
        val sid = ensureSession()
        return try {
            block(sid)
        } catch (e: android.os.RemoteException) {
            if (closed) throw e
            mutex.withLock { sessionId = null }
            val newSid = ensureSession()
            block(newSid)
        } catch (e: MindlayerException) {
            if (e.code == "SESSION_NOT_FOUND" || e.code == "SESSION_EVICTED" || e.code == "SESSION_EXPIRED") {
                if (closed) throw e
                mutex.withLock { sessionId = null }
                val newSid = ensureSession()
                block(newSid)
            } else {
                throw e
            }
        }
    }

    private suspend fun collectHandle(handle: InferenceHandle): String {
        val accumulator = StringBuilder()
        var result: String? = null
        handle.events.collect { event ->
            when (event) {
                is MindlayerEvent.TextDelta -> accumulator.append(event.text)
                is MindlayerEvent.Done -> {
                    result = event.fullText ?: accumulator.toString()
                }
                is MindlayerEvent.Error -> throw MindlayerException(
                    message = event.message,
                    code = event.code,
                )
                is MindlayerEvent.ToolCall -> throw MindlayerException(
                    message = Mindlayer.TOOL_CALL_IN_ONESHOT_MSG,
                    code = "UNSUPPORTED_TOOL_CALL",
                )
                else -> { /* Started, Metrics — ignored */ }
            }
        }
        return result ?: throw IllegalStateException(
            "Inference stream ended without a Done event"
        )
    }
}
