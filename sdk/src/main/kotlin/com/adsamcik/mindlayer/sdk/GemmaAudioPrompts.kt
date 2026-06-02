package com.adsamcik.mindlayer.sdk

/**
 * Canonical prompt templates for Gemma 4's audio capabilities.
 *
 * Source: https://ai.google.dev/gemma/docs/capabilities/audio
 *
 * The strings here are the prompt formats Google recommends for getting
 * consistent automatic-speech-recognition (ASR) output from Gemma 4. Using
 * them — instead of a hand-written prompt — keeps two things stable:
 *
 *  1. **Output formatting.** The recommended prompt asks for no newlines
 *     and digit-form numbers ("1.7", not "one point seven"; "3", not
 *     "three"). Hand-rolled prompts tend to omit one of those instructions
 *     and the model drifts toward verbose number spelling.
 *  2. **Language behaviour.** Gemma 4 E2B / E4B were trained for
 *     multilingual ASR. The "in `{LANGUAGE}` into `{LANGUAGE}` text"
 *     phrasing matches the training distribution; the "in its original
 *     language" phrasing (used when no language is supplied) is also
 *     directly from the docs page.
 *
 * Lives in `:sdk` rather than `:shared` on purpose — the service path
 * does not inject this prompt; it stays raw text on the AIDL boundary.
 * Putting the canonical text in the SDK keeps that contract honest.
 */
object GemmaAudioPrompts {

    /**
     * Canonical ASR prompt as shown on the Gemma audio capabilities page.
     *
     * @param language Human-readable language name (e.g. `"English"`,
     *   `"German"`, `"Czech"`) — passed through verbatim and inserted into
     *   the recommended `"in {LANGUAGE} into {LANGUAGE} text"` phrasing.
     *   Pass `null` (the default) to use the "in its original language"
     *   variant, which lets the model autodetect the spoken language.
     *
     * The instruction list is exactly the two bullets from the docs page:
     * no newlines in the output, and digits-not-words for numbers. They
     * are stable wire text — do not edit lightly; doing so will diverge
     * the model output from Google's reference behaviour and silently
     * change downstream parsing.
     */
    fun transcriptionPrompt(language: String? = null): String {
        val opening = if (language.isNullOrBlank()) {
            "Transcribe the following speech segment in its original language."
        } else {
            "Transcribe the following speech segment in $language into $language text."
        }
        return buildString {
            append(opening)
            append('\n')
            append('\n')
            append("Follow these specific instructions for formatting the answer:\n")
            append("* Only output the transcription, with no newlines.\n")
            append(
                "* When transcribing numbers, write the digits, i.e. write 1.7 " +
                    "and not one point seven, and write 3 instead of three.",
            )
        }
    }
}
