package com.adsamcik.mindlayer.sdk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the canonical Gemma ASR prompt text. The exact wording matters —
 * the model output formatting (no newlines, digits-not-words) depends on
 * it, and downstream parsing depends on the formatting. Changing the
 * prompt is allowed but must be a deliberate decision tracked in
 * `docs/engine/AUDIO.md`, not a drive-by edit.
 *
 * Source the text from `:sdk` only — `:shared` deliberately does not
 * carry the prompt to keep the service path "raw text" on the wire.
 */
class GemmaAudioPromptsTest {

    @Test
    fun `null language renders the original-language variant`() {
        val prompt = GemmaAudioPrompts.transcriptionPrompt(null)
        assertTrue(
            "prompt opens with the original-language phrasing: $prompt",
            prompt.startsWith(
                "Transcribe the following speech segment in its original language.",
            ),
        )
        // No language placeholder should leak through.
        assertFalse("no LANGUAGE placeholder leak", prompt.contains("{LANGUAGE}"))
        assertFalse("no double quote of null", prompt.contains("null"))
    }

    @Test
    fun `blank language is treated as null`() {
        val prompt = GemmaAudioPrompts.transcriptionPrompt("   ")
        // Same opening as the null path.
        assertEquals(
            GemmaAudioPrompts.transcriptionPrompt(null),
            prompt,
        )
    }

    @Test
    fun `named language renders the in-LANGUAGE-into-LANGUAGE variant`() {
        val prompt = GemmaAudioPrompts.transcriptionPrompt("English")
        assertTrue(
            "prompt mentions English twice (in / into): $prompt",
            prompt.startsWith(
                "Transcribe the following speech segment in English into English text.",
            ),
        )
        assertNotEquals(
            "English variant differs from null variant",
            GemmaAudioPrompts.transcriptionPrompt(null),
            prompt,
        )
    }

    @Test
    fun `formatting bullets match upstream docs verbatim`() {
        val prompt = GemmaAudioPrompts.transcriptionPrompt("German")
        // These two bullets are the load-bearing part of the prompt —
        // they shape model output. Don't reword without updating
        // docs/engine/AUDIO.md and the contract tests that depend on the
        // model's no-newline behaviour.
        assertTrue(
            "no-newlines bullet present: $prompt",
            prompt.contains("* Only output the transcription, with no newlines."),
        )
        assertTrue(
            "digits bullet present: $prompt",
            prompt.contains(
                "When transcribing numbers, write the digits, i.e. write 1.7 " +
                    "and not one point seven, and write 3 instead of three.",
            ),
        )
    }
}
