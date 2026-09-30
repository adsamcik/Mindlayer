package com.adsamcik.mindlayer.service.logging

import android.content.Context
import dev.tracebox.Tracebox
import dev.tracebox.TraceboxConfiguration
import dev.tracebox.api.CaptureKind
import dev.tracebox.api.LogLevel
import dev.tracebox.api.LogCategory as TraceboxLogCategory
import dev.tracebox.api.LogTemplate
import dev.tracebox.api.PerformanceMeasurement
import dev.tracebox.api.TraceboxLogger
import dev.tracebox.api.TraceboxPolicy
import dev.tracebox.api.public
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonObject

/** Local, bounded evidence. Room remains the source of dashboard usage metrics. */
internal object MindlayerDiagnostics {
    val captureKinds = setOf(CaptureKind.JVM_CRASH, CaptureKind.HANDLED_EXCEPTION, CaptureKind.ANR)
    val defaultPolicy = TraceboxPolicy.standard().copy(captures = captureKinds)

    fun processRole(processName: String?, packageName: String): Int? = when (processName) {
        packageName -> 1
        "$packageName:ml" -> 3 // Role 2 is reserved by Tracebox.
        else -> null
    }

    fun configuration(role: Int): TraceboxConfiguration = TraceboxConfiguration.Builder()
        .setProcessRole(role)
        .setInitialPolicy(defaultPolicy)
        .setNativeCaptureEnabled(false)
        .setPersistRequestedProfile(true)
        .build()

    fun install(context: Context, role: Int) {
        Tracebox.install(context, configuration(role))
    }

    fun record(entry: LogEntry, logger: TraceboxLogger = Tracebox.log) {
        recordPerformance(entry, logger)
        val level = when {
            entry.category == LogCategory.ERROR || entry.event == LogEvent.INIT_FAILURE_CATEGORIZED.key -> LogLevel.ERROR
            entry.event in frequentEvents -> LogLevel.DEBUG
            else -> LogLevel.INFO
        }
        if (!logger.isEnabled(level)) return
        logger.log(
            level,
            eventTemplate,
            public(entry.event.takeIf { it in knownEvents } ?: "unknown"),
            public(correlationId(entry.requestId)),
            public(correlationId(entry.sessionId)),
            public(entry.backend.takeIf { it in backends }),
            public(entry.durationMs), public(entry.tokensGenerated), public(entry.tokensPerSec), public(entry.prefillTokensPerSec),
            public(entry.thermalBand.takeIf { it in thermalBands }),
            public(entry.memoryAvailableMb), public(entry.memoryUsedMb),
            public(entry.errorMessage != null),
            public(safeContext(entry.extraJson)),
        )
    }

    private fun recordPerformance(entry: LogEntry, logger: TraceboxLogger) {
        val request = correlationId(entry.requestId) ?: return
        when (entry.event) {
            LogEvent.REQUEST_START.key -> {
                if (!logger.isEnabled(LogLevel.INFO, TraceboxLogCategory.PERFORMANCE)) return
                val measurement = logger.performanceStart(
                    timingTemplate, public(request), public(correlationId(entry.sessionId)),
                    public(entry.backend.takeIf { it in backends }),
                )
                val displaced = synchronized(timings) {
                    val previous = timings.remove(request)
                    val oldest = if (timings.size >= MAX_TIMINGS) timings.remove(timings.keys.first()) else null
                    timings[request] = measurement
                    listOfNotNull(previous, oldest)
                }
                displaced.forEach { it.cancelled() }
            }
            LogEvent.REQUEST_COMPLETE.key -> synchronized(timings) { timings.remove(request) }?.success()
            LogEvent.REQUEST_ERROR.key -> synchronized(timings) { timings.remove(request) }?.failure()
            LogEvent.REQUEST_CANCEL.key -> synchronized(timings) { timings.remove(request) }?.cancelled()
        }
    }

    // Correlation accepts generated UUIDs only. Sanitizing arbitrary content is insufficient
    // for privacy: it could leave a prompt intact while only removing its punctuation.
    internal fun correlationId(value: String?): String? = value?.takeIf(uuid::matches)

    internal fun safeContext(value: String?): String? {
        if (value == null || value.length > 16_384) return null
        val source = runCatching { Json.parseToJsonElement(value).jsonObject }.getOrNull() ?: return null
        val filtered = buildJsonObject {
            numericKeys.forEach { key ->
                val item = source[key] as? JsonPrimitive ?: return@forEach
                if (!item.isString && item.doubleOrNull?.isFinite() == true) put(key, item)
            }
            enumValues.forEach { (key, allowed) ->
                val item = source[key] as? JsonPrimitive ?: return@forEach
                if (item.contentOrNull in allowed) put(key, item)
            }
        }
        return filtered.takeIf { it.isNotEmpty() }?.toString()
    }

    private val uuid = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
    private val eventTemplate = LogTemplate.of(
        "Mindlayer event={} request={} session={} backend={} duration_ms={} tokens={} decode_tps={} prefill_tps={} thermal={} free_mb={} used_mb={} error={} context={}",
    )
    private const val MAX_TIMINGS = 128
    private val timings = linkedMapOf<String, PerformanceMeasurement>()
    private val timingTemplate = LogTemplate.of("Mindlayer inference request={} session={} backend={}")
    private val knownEvents = LogEvent.entries.map { it.key }.toSet()
    private val backends = setOf("CPU", "GPU", "NPU")
    private val thermalBands = setOf("COOL", "WARM", "HOT", "CRITICAL")
    private val frequentEvents = setOf(LogEvent.OCR_FRAME_PROCESSED.key, LogEvent.USER_MESSAGE.key, LogEvent.MODEL_RESPONSE.key)
    private val numericKeys = setOf(
        "status", "outcome", "statusCode", "errorCode", "mediaCount", "batchSize", "vectorCount",
        "requiredMb", "availableMb", "requiredBytes", "availableBytes", "timeoutMs", "frameBytes",
        "maxFrameBytes", "pendingCount", "ownedNow", "cap", "len", "size", "tokenCount", "maxTokens",
    )
    private val enumValues = mapOf(
        "feature" to setOf("chat", "embeddings", "ocr"),
        "kind" to setOf("chat", "embedding", "embeddings", "ocr"),
        "failureCategory" to setOf("LowMemory", "BackendUnavailable", "ModelMissing", "IntegrityMismatch", "NativeError"),
    )
}
