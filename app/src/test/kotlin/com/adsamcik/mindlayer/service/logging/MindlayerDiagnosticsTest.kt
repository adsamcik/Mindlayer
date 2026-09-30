package com.adsamcik.mindlayer.service.logging

import dev.tracebox.api.CaptureKind
import dev.tracebox.api.LogArgument
import dev.tracebox.api.LogLevel
import dev.tracebox.api.LogTemplate
import dev.tracebox.api.PerformanceMeasurement
import dev.tracebox.api.PrivacyConfiguration
import dev.tracebox.api.TraceboxLogger
import org.junit.Assert.*
import org.junit.Test

class MindlayerDiagnosticsTest {
    @Test
    fun `main and inference roles are unique and unknown processes are excluded`() {
        assertEquals(1, MindlayerDiagnostics.processRole("app", "app"))
        assertEquals(3, MindlayerDiagnostics.processRole("app:ml", "app"))
        assertNull(MindlayerDiagnostics.processRole("app:tracebox_handler", "app"))
        assertNull(MindlayerDiagnostics.processRole(null, "app"))
        assertFalse(MindlayerDiagnostics.configuration(3).nativeCaptureEnabled)
        assertFalse(CaptureKind.OS_EXIT in MindlayerDiagnostics.defaultPolicy.captures)
    }

    @Test
    fun `records useful correlation and numeric evidence while rejecting content`() {
        val logger = RecordingDiagnosticsLogger()
        val request = "00000000-0000-0000-0000-000000000001"
        MindlayerDiagnostics.record(LogEntry(
            timestampMs = 1L, category = LogCategory.ERROR, event = LogEvent.REQUEST_ERROR.key,
            requestId = request, sessionId = "private-prompt", backend = "private-backend",
            durationMs = 123, tokensGenerated = 42, errorMessage = "private-error",
            extraJson = """{"status":1003,"feature":"chat","failureCategory":"ModelMissing","prompt":"private-prompt","reason":"private-reason","size":"private-size"}""",
        ), logger)
        val recorded = logger.text()
        assertTrue(recorded.contains(request))
        assertTrue(recorded.contains("123"))
        assertTrue(recorded.contains("1003"))
        assertTrue(recorded.contains("ModelMissing"))
        assertFalse(recorded.contains("private-"))
        assertEquals(LogLevel.ERROR, logger.levels.single())
    }

    @Test
    fun `disabled logging does not render or forward metadata`() {
        val logger = RecordingDiagnosticsLogger(enabled = false)
        MindlayerDiagnostics.record(LogEntry(timestampMs = 1, category = LogCategory.ERROR, event = LogEvent.REQUEST_ERROR.key), logger)
        assertTrue(logger.arguments.isEmpty())
    }

    @Test
    fun `malformed nested and oversized context is rejected`() {
        assertNull(MindlayerDiagnostics.safeContext("{broken"))
        assertNull(MindlayerDiagnostics.safeContext("""{"status":{"secret":"content"},"feature":"private"}"""))
        assertNull(MindlayerDiagnostics.safeContext("x".repeat(16_385)))
    }

    @Test
    fun `request timing finishes on success failure and cancellation`() {
        val logger = RecordingDiagnosticsLogger()
        listOf(LogEvent.REQUEST_COMPLETE, LogEvent.REQUEST_ERROR, LogEvent.REQUEST_CANCEL).forEachIndexed { index, outcome ->
            val request = "00000000-0000-0000-0000-00000000000${index + 2}"
            MindlayerDiagnostics.record(LogEntry(timestampMs = 1, category = LogCategory.INFERENCE,
                event = LogEvent.REQUEST_START.key, requestId = request), logger)
            MindlayerDiagnostics.record(LogEntry(timestampMs = 2, category = LogCategory.INFERENCE,
                event = outcome.key, requestId = request), logger)
        }
        assertEquals(listOf("success", "failure", "cancelled"), logger.timingOutcomes)
    }
}

internal class RecordingDiagnosticsLogger(private val enabled: Boolean = true) : TraceboxLogger {
    val levels = mutableListOf<LogLevel>()
    val arguments = mutableListOf<LogArgument>()
    val timingOutcomes = mutableListOf<String>()
    override fun isEnabled(level: LogLevel, category: dev.tracebox.api.LogCategory) = enabled
    override fun log(level: LogLevel, template: LogTemplate, vararg arguments: LogArgument) {
        if (enabled) {
            levels += level
            this.arguments += arguments
        }
    }
    override fun error(throwable: Throwable, template: LogTemplate, vararg arguments: LogArgument) =
        log(LogLevel.ERROR, template, *arguments)
    override fun performanceStart(template: LogTemplate, vararg arguments: LogArgument): PerformanceMeasurement =
        object : PerformanceMeasurement {
            override fun success() { timingOutcomes += "success" }
            override fun failure() { timingOutcomes += "failure" }
            override fun cancelled() { timingOutcomes += "cancelled" }
        }
    fun text(): String = arguments.joinToString(" ") { PrivacyConfiguration.defaults().render(it).text }
}
