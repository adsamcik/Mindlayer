package com.adsamcik.mindlayer.service.logging

import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Privacy fitness guard: the persisted log schema [LogEntry] must carry
 * METADATA ONLY — never prompt text, model output, recognized OCR text, or any
 * other user/model content. The service-side privacy invariant depends on this:
 * if a future change adds a `prompt` / `responseText` / `ocrText` column to the
 * Room schema, this guard fails the build before any content can be persisted.
 *
 * `errorMessage` is allowed because it is `safeLabel()`-only (exception
 * class-name, never prompt text); `extraJson` is allowed as the flexible
 * metadata field — the no-content rule for its CONTENTS is enforced in the
 * `LogRepository` builders, not the schema.
 */
class LogEntryPrivacyGuardTest {

    @Test
    fun `LogEntry schema declares no prompt or model-output content fields`() {
        val fieldNames = LogEntry::class.java.declaredFields.map { it.name.lowercase() }

        // Substrings that would indicate user/model content leaking into the
        // persisted schema. Deliberately does NOT include bare "message"
        // (errorMessage is safeLabel-only) or "json" (extraJson is metadata).
        val deniedSubstrings = listOf(
            "prompt", "content", "response", "completion", "output",
            "usertext", "usermessage", "modelresponse", "answer", "transcript",
            "ocrtext", "recognized", "evidence",
        )

        val violations = fieldNames.filter { field ->
            deniedSubstrings.any { field.contains(it) }
        }
        assertTrue(
            "LogEntry must persist metadata only — never prompt text or model output. " +
                "Suspect content-bearing field(s): $violations (all fields=$fieldNames)",
            violations.isEmpty(),
        )
    }
}
