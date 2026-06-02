package com.adsamcik.mindlayer

/**
 * Documented capabilities and limits of Gemma 4's on-device audio frontend
 * as served by Mindlayer's LiteRT-LM engine.
 *
 * Source: https://ai.google.dev/gemma/docs/capabilities/audio
 *
 * # Two contracts, one model
 *
 * Two distinct contracts come out of that page. They must not be conflated:
 *
 *  1. **Model frontend contract** — what the Universal Speech Model encoder
 *     consumes internally: mono, 16 kHz, 32-bit float waveform samples in
 *     `[-1, 1]`, framed every 32 ms. This describes the *decoded* form
 *     after preprocessing.
 *  2. **Mindlayer IPC contract** — what callers actually send across the
 *     AIDL boundary: an `AudioTransfer` / `MediaPart` carrying an
 *     **encoded** audio file (WAV / MP3 / OGG / FLAC / AAC / MP4). The
 *     service stages the bytes and hands the file path to
 *     `Content.AudioFile(...)`; LiteRT-LM owns decoding and resampling
 *     down to the frontend contract above.
 *
 * SDK callers therefore do **not** need to resample, downmix, or convert
 * to float32 themselves — they send their existing encoded clip and the
 * engine handles the rest. The frontend constants below are documentation
 * for callers who want to *pre-trim* or *pre-mix* their input for
 * size/latency reasons; they are not validated on the wire.
 *
 * The duration cap ([MAX_DURATION_MS]) and per-second token cost
 * ([TOKENS_PER_SECOND]) **are** load-bearing and are enforced
 * (cap by `IpcInputValidator`; cost by the service-side context budget).
 */
object GemmaAudioSpec {

    /**
     * Sample rate the Universal Speech Model encoder consumes internally.
     * Documentation only — Mindlayer IPC accepts arbitrary sample rates
     * in the encoded container and lets the engine resample.
     */
    const val SAMPLE_RATE_HZ: Int = 16_000

    /**
     * Number of channels the frontend consumes (mono). Stereo callers
     * may pre-downmix (average L+R) or let the engine handle it — both
     * paths are valid; pre-downmixing just halves the staged file size.
     */
    const val CHANNELS: Int = 1

    /**
     * Frame size at the encoder front. Cited as 32 ms in the Google page
     * and 40 ms in the Gemma technical report; the public docs page
     * takes precedence for the SDK contract.
     */
    const val FRAME_DURATION_MS: Int = 32

    /**
     * Bit depth of the decoded float-PCM samples consumed by the encoder.
     * Samples are normalized to `[-1, 1]`. Documentation only.
     */
    const val BITS_PER_SAMPLE: Int = 32

    /**
     * Hard maximum clip length Gemma 4 accepts in a single audio
     * attachment, in milliseconds. Sending longer audio over IPC will be
     * rejected by `IpcInputValidator.validateAudioTransfer` /
     * `validateAudioPart` when `durationMs` is reported.
     *
     * Callers with longer recordings must chunk the audio themselves and
     * submit one inference per chunk (or use a higher-level batching
     * helper layered on top of `infer { … }`).
     */
    const val MAX_DURATION_MS: Long = 30_000L

    /**
     * Soft token cost per second of audio at the encoder default frame
     * rate (~1 token per 40 ms ≈ 25 tokens/second on Gemma 4; Gemma 3n
     * is approximately a quarter of this). Used by the service-side
     * context-budget accountant — see `estimateTokens` for the rounding
     * convention.
     *
     * This is documentation in `:shared` so SDKs can do client-side
     * budget arithmetic before binding to the service; the authoritative
     * service-side estimator lives in
     * `service.engine.AUDIO_TOKENS_PER_SECOND_ESTIMATE` which re-exports
     * this value.
     */
    const val TOKENS_PER_SECOND: Int = 25

    /**
     * Estimated token cost for an audio clip of [durationMs]. Rounded up
     * to whole seconds so a 100 ms clip still costs at least one second
     * of budget — matches the conservative service-side estimator.
     */
    fun estimateTokens(durationMs: Long): Int {
        if (durationMs <= 0L) return 0
        val seconds = ((durationMs + 999L) / 1000L).coerceAtLeast(0L).toInt()
        return seconds * TOKENS_PER_SECOND
    }
}
