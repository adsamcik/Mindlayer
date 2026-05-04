package com.adsamcik.mindlayer.sdk

import android.graphics.Bitmap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicBoolean

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
    @Volatile private var sessionId: String? = null
    private val closeStarted = AtomicBoolean(false)
    private val mutex = Mutex()

    /**
     * Tracks handles that are currently in-flight (returned from [chatStream] or
     * being collected inside [collectHandle]). Guarded by synchronization on itself.
     * [close] snapshots and clears this set, then cancels each handle.
     */
    private val inFlight: MutableSet<InferenceHandle> = Collections.synchronizedSet(mutableSetOf())

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
     * The handle is registered as in-flight and will be cancelled if [close] is
     * called before collection completes.
     *
     * Note: the session is created eagerly (suspending) before returning
     * the handle. Use the suspend [chat] overloads for simpler usage.
     */
    suspend fun chatStream(text: String): InferenceHandle {
        val sid = ensureSession()
        return client.chat(sid, text).also { synchronized(inFlight) { inFlight.add(it) } }
    }

    /**
     * Stream a text + image response.
     *
     * The handle is registered as in-flight and will be cancelled if [close] is
     * called before collection completes.
     */
    suspend fun chatStream(text: String, image: Bitmap): InferenceHandle {
        val sid = ensureSession()
        return client.chatWithImage(sid, text, image).also { synchronized(inFlight) { inFlight.add(it) } }
    }

    /**
     * Close this conversation and release resources.
     *
     * Thread-safe and idempotent. Cancels any in-flight inference handles (including
     * those returned by [chatStream] that the caller may still be collecting), then
     * destroys the server-side session.
     *
     * **Synchronous and deterministic.** When `close()` returns, both the cancel
     * IPC for each in-flight handle and the destroy IPC for the session have been
     * dispatched (best-effort if the service is disconnected). This honors the
     * [AutoCloseable] contract — callers can rely on resource release without
     * having to poll, sleep, or join a background job.
     *
     * **Threading.** `close()` performs blocking Binder calls on the calling
     * thread (typically microseconds-to-milliseconds per call). Avoid invoking
     * it from the Android main thread under StrictMode; prefer a background
     * dispatcher when called from UI code.
     *
     * **Disconnect handling.** If the service binder is currently disconnected,
     * cleanup is silently skipped — the service-side session is cleaned up
     * automatically by the per-session expiration timer (see
     * [ConversationConfig.expiration]).
     */
    override fun close() {
        if (!closeStarted.compareAndSet(false, true)) return
        val handles = synchronized(inFlight) { inFlight.toList().also { inFlight.clear() } }
        val sid = sessionId.also { sessionId = null }

        // Cancel handles first so the service stops generating tokens before we
        // tear down the session. Both calls are best-effort — they no-op when no
        // service binder is currently connected.
        handles.forEach { runCatching { it.cancelSync() } }

        if (sid != null) {
            try {
                client.connection.getService()?.destroySession(sid)
            } catch (_: Exception) {
                // Service died mid-call; session will be cleaned up server-side
                // on its expiration timer.
            }
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
        check(!closeStarted.get()) { "Conversation is closed" }
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
            if (closeStarted.get()) throw e
            mutex.withLock { sessionId = null }
            val newSid = ensureSession()
            block(newSid)
        } catch (e: MindlayerException) {
            if (e.code == "SESSION_NOT_FOUND" || e.code == "SESSION_EVICTED" || e.code == "SESSION_EXPIRED") {
                if (closeStarted.get()) throw e
                mutex.withLock { sessionId = null }
                val newSid = ensureSession()
                block(newSid)
            } else {
                throw e
            }
        }
    }

    private suspend fun collectHandle(handle: InferenceHandle): String {
        synchronized(inFlight) { inFlight.add(handle) }
        try {
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
        } finally {
            synchronized(inFlight) { inFlight.remove(handle) }
        }
    }
}
