package com.adsamcik.mindlayer

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins the documented audio frontend constants from the Google Gemma
 * audio capabilities page and the rounding behaviour of
 * `estimateTokens`. The numbers themselves are part of Mindlayer's
 * public contract (advertised by `FEATURE_AUDIO_INPUT`; used by the
 * service-side budget gate); regressing them silently would change
 * behaviour invisible to API consumers until an inference is rejected.
 */
class GemmaAudioSpecTest {

    @Test
    fun `documented constants match the upstream Google page`() {
        assertEquals("sample rate", 16_000, GemmaAudioSpec.SAMPLE_RATE_HZ)
        assertEquals("mono", 1, GemmaAudioSpec.CHANNELS)
        assertEquals("frame duration ms", 32, GemmaAudioSpec.FRAME_DURATION_MS)
        assertEquals("float32 bit depth", 32, GemmaAudioSpec.BITS_PER_SAMPLE)
        assertEquals("max clip duration ms", 30_000L, GemmaAudioSpec.MAX_DURATION_MS)
        assertEquals("tokens per second", 25, GemmaAudioSpec.TOKENS_PER_SECOND)
    }

    @Test
    fun `estimateTokens returns zero for non-positive durations`() {
        assertEquals(0, GemmaAudioSpec.estimateTokens(0L))
        assertEquals(0, GemmaAudioSpec.estimateTokens(-1L))
        assertEquals(0, GemmaAudioSpec.estimateTokens(Long.MIN_VALUE))
    }

    @Test
    fun `estimateTokens rounds up to whole seconds`() {
        // 100 ms ≈ 0.1 s → ceil to 1 s × 25 = 25 tokens
        assertEquals(25, GemmaAudioSpec.estimateTokens(100L))
        // Exactly 1 s = 1 × 25 = 25
        assertEquals(25, GemmaAudioSpec.estimateTokens(1_000L))
        // 1001 ms ≈ 1.001 s → ceil to 2 s × 25 = 50
        assertEquals(50, GemmaAudioSpec.estimateTokens(1_001L))
    }

    @Test
    fun `estimateTokens at MAX_DURATION_MS equals 750 tokens`() {
        // 30 s × 25 tok/s = 750. This is also the budget-fallback used
        // by the service when durationMs is null, so the number is part
        // of the budget contract.
        assertEquals(750, GemmaAudioSpec.estimateTokens(GemmaAudioSpec.MAX_DURATION_MS))
    }

    @Test
    fun `estimateTokens scales linearly above the cap for diagnostic use`() {
        // The validator enforces the 30 s cap, but the estimator does not
        // — it's intentionally pure arithmetic so callers can pre-flight
        // budget math on theoretical clip lengths.
        val tenMinutes = 10L * 60L * 1000L
        assertTrue(
            "estimator does not silently clamp",
            GemmaAudioSpec.estimateTokens(tenMinutes) > 750,
        )
    }
}
