package com.adsamcik.mindlayer.service.engine.mock

/**
 * DEBUG-only [LlmMockGenerator] for the "CI mock engines" mode.
 *
 * Produces a short, deterministic, `[mock]`-tagged reply that reflects the
 * request modality (text / image / audio / combinations) so a consumer app's
 * CI can assert it streamed a real response over the pipe — and never mistake
 * mock output for a real model generation.
 *
 * Privacy: the caller's prompt is NOT logged or persisted. A length-bounded,
 * newline-flattened echo is included only in the streamed reply body (which is
 * the caller's own content coming back to them), never in a log line.
 */
internal class MockLlmGenerator : LlmMockGenerator {

    override fun reply(textContent: String?, hasImage: Boolean, hasAudio: Boolean): String {
        val modality = when {
            hasImage && hasAudio -> "an image + audio"
            hasImage -> "an image"
            hasAudio -> "audio"
            else -> "a text"
        }
        val echo = textContent
            ?.replace('\n', ' ')
            ?.trim()
            ?.take(ECHO_LIMIT)
            .orEmpty()
        val echoClause = if (echo.isNotBlank()) " You said: \"$echo\"." else ""
        return "[mock] Received $modality request.$echoClause This is a synthetic " +
            "CI response from Mindlayer mock engines; no real model was loaded."
    }

    private companion object {
        const val ECHO_LIMIT = 80
    }
}
