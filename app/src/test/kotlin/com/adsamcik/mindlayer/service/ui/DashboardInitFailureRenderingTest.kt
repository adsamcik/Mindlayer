package com.adsamcik.mindlayer.service.ui

import com.adsamcik.mindlayer.service.engine.InitFailure
import com.adsamcik.mindlayer.service.logging.LogCategory
import com.adsamcik.mindlayer.service.logging.LogEntry
import com.adsamcik.mindlayer.service.logging.LogEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * F-077: dashboard-side rendering and serialise/deserialise contract for
 * [InitFailure].
 *
 * Two surface areas:
 *  1. [parseInitFailureLogRow] — re-hydrates a typed [InitFailure] from
 *     a `LogEvent.INIT_FAILURE_CATEGORIZED.key` row in the log database.
 *     This is the cross-process bridge: the engine logs the row in
 *     `:ml`, the dashboard reads it from the shared Room file in the
 *     main process. The wire format is `extraJson.failureCategory` +
 *     `backend` + `errorMessage`. A round-trip test pins the contract
 *     against [com.adsamcik.mindlayer.service.logging.LogRepository.logInitFailureCategorized].
 *  2. [describeInitFailure] — variant → `(tone, message)` mapping for
 *     the StatusSection callout. Each variant has a specific message
 *     and remediation; the table is the contract.
 */
class DashboardInitFailureRenderingTest {

    // ---- describeInitFailure (variant → message) ---------------------------

    @Test
    fun `LowMemory renders ERROR with free up memory remediation`() {
        val (tone, message) = describeInitFailure(InitFailure.LowMemory, currentBackend = "NONE")
        assertEquals(DashboardMessageTone.ERROR, tone)
        assertTrue("Message should mention memory: $message", message.contains("memory", ignoreCase = true))
        assertTrue("Message should include retry remediation: $message", message.contains("retry", ignoreCase = true))
    }

    @Test
    fun `ModelMissing renders ERROR with install AI Pack remediation`() {
        val (tone, message) = describeInitFailure(InitFailure.ModelMissing, currentBackend = "NONE")
        assertEquals(DashboardMessageTone.ERROR, tone)
        assertTrue("Message should mention model: $message", message.contains("Model", ignoreCase = true))
        assertTrue("Message should include AI Pack remediation: $message", message.contains("AI Pack", ignoreCase = true))
    }

    @Test
    fun `IntegrityMismatch renders ERROR with reinstall remediation`() {
        val (tone, message) = describeInitFailure(InitFailure.IntegrityMismatch, currentBackend = "NONE")
        assertEquals(DashboardMessageTone.ERROR, tone)
        assertTrue("Message should mention corruption: $message", message.contains("corrupt", ignoreCase = true))
        assertTrue("Message should include reinstall remediation: $message", message.contains("reinstall", ignoreCase = true))
    }

    @Test
    fun `BackendUnavailable on CPU fallback renders WARNING with running on label`() {
        // GPU failed, CPU is running — informational warning, not error.
        val (tone, message) = describeInitFailure(
            InitFailure.BackendUnavailable("GPU", "RuntimeException"),
            currentBackend = "CPU",
        )
        assertEquals(DashboardMessageTone.WARNING, tone)
        assertTrue("Message should mention GPU: $message", message.contains("GPU"))
        assertTrue("Message should mention CPU fallback: $message", message.contains("CPU"))
        assertTrue("Message should include safeLabel: $message", message.contains("RuntimeException"))
    }

    @Test
    fun `BackendUnavailable with no recovery renders ERROR`() {
        // Whole chain failed (currentBackend === "NONE") — escalate to error.
        val (tone, message) = describeInitFailure(
            InitFailure.BackendUnavailable("CPU", "OutOfMemoryError"),
            currentBackend = "NONE",
        )
        assertEquals(DashboardMessageTone.ERROR, tone)
        assertTrue("Message should mention failed backend: $message", message.contains("CPU"))
        assertTrue("Message should include safeLabel: $message", message.contains("OutOfMemoryError"))
    }

    @Test
    fun `BackendUnavailable on same backend renders ERROR (no recovery)`() {
        // Edge case: failure recorded for "CPU" and currentBackend is "CPU"
        // — this would mean the backend reported failure but somehow remained
        // current; treat as ERROR because no actual fallback happened.
        val (tone, _) = describeInitFailure(
            InitFailure.BackendUnavailable("CPU", "RuntimeException"),
            currentBackend = "CPU",
        )
        assertEquals(DashboardMessageTone.ERROR, tone)
    }

    @Test
    fun `NativeError renders ERROR with safeLabel`() {
        val (tone, message) = describeInitFailure(
            InitFailure.NativeError("UnsatisfiedLinkError"),
            currentBackend = "NONE",
        )
        assertEquals(DashboardMessageTone.ERROR, tone)
        assertTrue("Message should mention native runtime: $message", message.contains("Native", ignoreCase = true))
        assertTrue("Message should include safeLabel: $message", message.contains("UnsatisfiedLinkError"))
    }

    // ---- parseInitFailureLogRow round-trip ---------------------------------

    @Test
    fun `parses LowMemory row back to LowMemory variant`() {
        val row = makeRow(category = "LowMemory")
        assertEquals(InitFailure.LowMemory, parseInitFailureLogRow(row))
    }

    @Test
    fun `parses ModelMissing row back to ModelMissing variant`() {
        val row = makeRow(category = "ModelMissing")
        assertEquals(InitFailure.ModelMissing, parseInitFailureLogRow(row))
    }

    @Test
    fun `parses IntegrityMismatch row back to IntegrityMismatch variant`() {
        val row = makeRow(category = "IntegrityMismatch")
        assertEquals(InitFailure.IntegrityMismatch, parseInitFailureLogRow(row))
    }

    @Test
    fun `parses BackendUnavailable row preserving backend and safeLabel`() {
        val row = makeRow(
            category = "BackendUnavailable",
            backend = "GPU",
            errorMessage = "RuntimeException",
        )
        val parsed = parseInitFailureLogRow(row) as? InitFailure.BackendUnavailable
        assertNotNull("Expected BackendUnavailable, got $parsed", parsed)
        assertEquals("GPU", parsed!!.backend)
        assertEquals("RuntimeException", parsed.safeLabel)
    }

    @Test
    fun `parses NativeError row preserving safeLabel`() {
        val row = makeRow(
            category = "NativeError",
            errorMessage = "UnsatisfiedLinkError",
        )
        val parsed = parseInitFailureLogRow(row) as? InitFailure.NativeError
        assertNotNull("Expected NativeError, got $parsed", parsed)
        assertEquals("UnsatisfiedLinkError", parsed!!.safeLabel)
    }

    @Test
    fun `parser returns null for null extraJson`() {
        val row = LogEntry(
            timestampMs = 0L,
            category = LogCategory.ENGINE,
            event = LogEvent.INIT_FAILURE_CATEGORIZED.key,
            extraJson = null,
        )
        assertNull(parseInitFailureLogRow(row))
    }

    @Test
    fun `parser returns null for unknown category`() {
        val row = makeRow(category = "FutureUnknownVariant")
        assertNull(
            "Unknown variants must return null so the dashboard can degrade gracefully",
            parseInitFailureLogRow(row),
        )
    }

    @Test
    fun `parser returns null for malformed extraJson`() {
        val row = LogEntry(
            timestampMs = 0L,
            category = LogCategory.ENGINE,
            event = LogEvent.INIT_FAILURE_CATEGORIZED.key,
            extraJson = "{ this is not valid json",
        )
        assertNull(
            "Malformed JSON must not crash the parser — dashboard should hide the card instead",
            parseInitFailureLogRow(row),
        )
    }

    @Test
    fun `parser returns null when failureCategory key is missing`() {
        val row = LogEntry(
            timestampMs = 0L,
            category = LogCategory.ENGINE,
            event = LogEvent.INIT_FAILURE_CATEGORIZED.key,
            extraJson = """{"otherField":"value"}""",
        )
        assertNull(parseInitFailureLogRow(row))
    }

    @Test
    fun `parser handles BackendUnavailable with empty backend gracefully`() {
        // Defensive: if a malformed row has BackendUnavailable category but
        // null backend column, we still produce a sealed-class instance
        // (with empty strings) rather than null. Dashboard renders it as
        // "[empty] backend failed (…)" — visible degradation, not a hidden bug.
        val row = LogEntry(
            timestampMs = 0L,
            category = LogCategory.ENGINE,
            event = LogEvent.INIT_FAILURE_CATEGORIZED.key,
            backend = null,
            errorMessage = null,
            extraJson = """{"failureCategory":"BackendUnavailable"}""",
        )
        val parsed = parseInitFailureLogRow(row) as? InitFailure.BackendUnavailable
        assertNotNull(parsed)
        assertEquals("", parsed!!.backend)
        assertEquals("", parsed.safeLabel)
    }

    @Test
    fun `backend decision parser preserves feature-specific attempted chain`() {
        val row = LogEntry(
            timestampMs = 0L,
            category = LogCategory.ENGINE,
            event = LogEvent.BACKEND_DECISION.key,
            backend = "CPU",
            extraJson = """{"feature":"ocr","reason":"thermal","attempted":[{"backend":"GPU","reason":"hot"},{"backend":"CPU","reason":"fallback"}]}""",
        )

        val parsed = parseBackendDecisionLogRow(row)

        assertNotNull(parsed)
        assertEquals("ocr", parsed!!.featureName)
        assertEquals("CPU", parsed.backend)
        assertEquals("thermal", parsed.reason)
        assertEquals("GPU:hot -> CPU:fallback", parsed.attemptedSummary)
    }

    // ---- helpers -----------------------------------------------------------

    private fun makeRow(
        category: String,
        backend: String? = null,
        errorMessage: String? = null,
    ): LogEntry = LogEntry(
        timestampMs = 0L,
        category = LogCategory.ENGINE,
        event = LogEvent.INIT_FAILURE_CATEGORIZED.key,
        backend = backend,
        errorMessage = errorMessage,
        extraJson = """{"failureCategory":"$category"}""",
    )
}
