package com.adsamcik.mindlayer.service.logging

import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ClosedReceiveChannelException
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicLong

class LogRepository(
    private val dao: LogDao,
    dispatcher: CoroutineDispatcher = Dispatchers.IO,
    bufferCapacity: Int = BUFFER_CAPACITY,
) {

    companion object {
        private const val TAG = "LogRepo"
        private const val BUFFER_CAPACITY = 1024
        private const val BATCH_MAX = 128
    }

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)

    // Bounded queue — trySend never blocks; fails (and drops) when the buffer is full.
    private val queue = Channel<LogEntry>(bufferCapacity)
    private val droppedCount = AtomicLong(0)

    init {
        scope.launch { drainLoop() }
    }

    // Fire-and-forget log (non-blocking)
    fun log(entry: LogEntry) {
        val ok = queue.trySend(entry).isSuccess
        if (!ok) droppedCount.incrementAndGet()
    }

    private suspend fun drainLoop() {
        val batch = ArrayList<LogEntry>(BATCH_MAX)
        while (true) {
            // Block for at least one entry, then opportunistically drain more.
            val first = try {
                queue.receive()
            } catch (_: ClosedReceiveChannelException) {
                return
            }
            batch.add(first)
            while (batch.size < BATCH_MAX) {
                val next = queue.tryReceive().getOrNull() ?: break
                batch.add(next)
            }
            try {
                dao.insertAll(batch)
            } catch (t: Throwable) {
                MindlayerLog.w(TAG, "Failed to flush ${batch.size} log entries", throwable = t)
            }
            batch.clear()
        }
    }

    /** Number of log entries dropped due to backpressure since process start. */
    fun droppedLogCount(): Long = droppedCount.get()

    // Convenience builders
    fun logInferenceStart(requestId: String, sessionId: String, backend: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.REQUEST_START,
            requestId = requestId,
            sessionId = sessionId,
            backend = backend,
        ))
    }

    fun logInferenceComplete(
        requestId: String, sessionId: String, backend: String,
        durationMs: Long, tokensGenerated: Int, tokensPerSec: Float, prefillTps: Float?,
    ) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.REQUEST_COMPLETE,
            requestId = requestId, sessionId = sessionId, backend = backend,
            durationMs = durationMs, tokensGenerated = tokensGenerated,
            tokensPerSec = tokensPerSec, prefillTokensPerSec = prefillTps,
        ))
    }

    fun logInferenceError(requestId: String, sessionId: String?, errorMessage: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ERROR,
            event = LogEvent.REQUEST_ERROR,
            requestId = requestId, sessionId = sessionId,
            errorMessage = sanitizeErrorClass(errorMessage),
        ))
    }

    fun logThermalBandChange(fromBand: String, toBand: String, backend: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.THERMAL,
            event = LogEvent.BAND_CHANGE,
            thermalBand = toBand, backend = backend,
            extraJson = logExtraJson {
                put("from", fromBand)
                put("to", toBand)
            },
        ))
    }

    fun logSessionCreated(sessionId: String, backend: String, maxTokens: Int) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SESSION,
            event = LogEvent.SESSION_CREATED,
            sessionId = sessionId, backend = backend,
            extraJson = logExtraJson { put("maxTokens", maxTokens) },
        ))
    }

    fun logSessionDestroyed(sessionId: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SESSION,
            event = LogEvent.SESSION_DESTROYED,
            sessionId = sessionId,
        ))
    }

    fun logSessionEvicted(sessionId: String, reason: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.SESSION,
            event = LogEvent.SESSION_EVICTED,
            sessionId = sessionId,
            extraJson = logExtraJson { put("reason", reason) },
        ))
    }

    fun logMemoryPressure(pressure: String, availableMb: Long, totalMb: Long) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.MEMORY,
            event = LogEvent.PRESSURE_CHANGE,
            memoryAvailableMb = availableMb,
            memoryUsedMb = totalMb - availableMb,
            extraJson = logExtraJson { put("pressure", pressure) },
        ))
    }

    fun logEngineInit(backend: String, durationMs: Long, modelPath: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.ENGINE_INIT,
            backend = backend, durationMs = durationMs,
            extraJson = logExtraJson { put("modelPath", modelPath) },
        ))
    }

    fun logEngineShutdown(backend: String) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.ENGINE,
            event = LogEvent.ENGINE_SHUTDOWN,
            backend = backend,
        ))
    }

    /**
     * Log a user-message event. Persists metadata ONLY — never the user's
     * prompt text. This class of content is private to the caller app and
     * must not end up in the service's Room database.
     */
    fun logUserMessage(requestId: String, sessionId: String, tokenCount: Int) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.USER_MESSAGE,
            requestId = requestId,
            sessionId = sessionId,
            extraJson = logExtraJson { put("tokenCount", tokenCount) },
        ))
    }

    /**
     * Log a model-response event. Persists metadata ONLY — never the model
     * output text. Model output can echo or paraphrase the prompt and is
     * treated as equally sensitive.
     */
    fun logModelResponse(requestId: String, sessionId: String, tokenCount: Int) {
        log(LogEntry(
            timestampMs = System.currentTimeMillis(),
            category = LogCategory.INFERENCE,
            event = LogEvent.MODEL_RESPONSE,
            requestId = requestId,
            sessionId = sessionId,
            extraJson = logExtraJson { put("tokenCount", tokenCount) },
        ))
    }

    // Cleanup logs older than N days
    suspend fun cleanup(retentionDays: Int = 7) {
        val cutoff = System.currentTimeMillis() - (retentionDays * 24 * 60 * 60 * 1000L)
        dao.deleteOlderThan(cutoff)
    }

    fun shutdown() {
        queue.close()
        scope.cancel()
    }
}
