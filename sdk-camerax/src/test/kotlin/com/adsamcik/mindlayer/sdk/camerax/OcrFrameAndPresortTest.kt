package com.adsamcik.mindlayer.sdk.camerax

import com.adsamcik.mindlayer.OcrFrameMeta
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class OcrFrameTest {

    @Test fun `fromYPlane builds a defensive copy`() {
        val src = ByteArray(64) { it.toByte() }
        val frame = OcrFrame.fromYPlane(
            frameId = 1L,
            captureTimeMs = 100L,
            yPlane = src,
            width = 8,
            height = 8,
        )
        assertFalse("Should defensive-copy (not same reference)", src === frame.bytes)
        // Mutate src; frame should be unchanged.
        src[0] = 99
        assertEquals(0.toByte(), frame.bytes[0])
    }

    @Test fun `fromYPlane rejects mismatched dimensions`() {
        assertThrows(IllegalArgumentException::class.java) {
            OcrFrame.fromYPlane(1L, 0L, ByteArray(50), 10, 10)
        }
    }

    @Test fun `OcrFrame rejects non-quadrant rotation`() {
        assertThrows(IllegalArgumentException::class.java) {
            OcrFrame(
                frameId = 1L,
                captureTimeMs = 0L,
                width = 8,
                height = 8,
                rotationDegrees = 45,
                qualityHint = 0,
                bytes = ByteArray(64),
            )
        }
    }

    @Test fun `OcrFrame accepts all four allowed rotations`() {
        for (rot in listOf(0, 90, 180, 270)) {
            OcrFrame(
                frameId = 1L,
                captureTimeMs = 0L,
                width = 8,
                height = 8,
                rotationDegrees = rot,
                qualityHint = 0,
                bytes = ByteArray(64),
            )
        }
    }

    @Test fun `toFrameMeta produces expected parcelable`() {
        val frame = OcrFrame.fromYPlane(
            frameId = 42L,
            captureTimeMs = 1_700_000_000L,
            yPlane = ByteArray(64),
            width = 8,
            height = 8,
            rotationDegrees = 90,
            qualityHint = OcrFrameMeta.QUALITY_GOOD,
        )
        val meta = frame.toFrameMeta()
        assertEquals(42L, meta.frameId)
        assertEquals(1_700_000_000L, meta.captureTimeMs)
        assertEquals(90, meta.rotationDegrees)
        assertEquals(OcrFrameMeta.QUALITY_GOOD, meta.qualityHint)
    }

    @Test fun `toString redacts pixel data`() {
        val frame = OcrFrame.fromYPlane(1L, 0L, ByteArray(64) { (it % 256).toByte() }, 8, 8)
        val s = frame.toString()
        assertFalse("toString must not contain raw byte values", s.contains("bytes=[B"))
        assertTrue("toString should mention size", s.contains("bytes=64"))
    }

    @Test fun `equals + hashCode include bytes`() {
        val a = OcrFrame.fromYPlane(1L, 0L, ByteArray(64), 8, 8)
        val b = OcrFrame.fromYPlane(1L, 0L, ByteArray(64), 8, 8)
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        val c = OcrFrame.fromYPlane(1L, 0L, ByteArray(64) { 1 }, 8, 8)
        assertNotEquals(a, c)
    }
}

class OcrFramePresortTest {

    @Test fun `score rejects black frame as TOO_DARK`() {
        val r = OcrFramePresort.score(ByteArray(64 * 64) { 0 }, 64, 64)
        assertEquals(OcrFrameMeta.QUALITY_TOO_DARK, r.hint)
    }

    @Test fun `score rejects uniform mid-grey as BLURRY`() {
        val r = OcrFramePresort.score(ByteArray(64 * 64) { 128.toByte() }, 64, 64)
        assertEquals(OcrFrameMeta.QUALITY_BLURRY, r.hint)
    }

    @Test fun `score accepts high-contrast text-like frame as GOOD`() {
        val frame = textLikeFrame()
        val r = OcrFramePresort.score(frame, 64, 64)
        assertEquals(
            "Synthetic text-like frame should pass presort: $r",
            OcrFrameMeta.QUALITY_GOOD,
            r.hint,
        )
        assertTrue(r.isGood)
    }

    @Test fun `score detects duplicate via hash hamming`() {
        val frame = textLikeFrame(seed = 5)
        val r1 = OcrFramePresort.score(frame, 64, 64)
        val r2 = OcrFramePresort.score(frame, 64, 64, previousDHash = r1.dHash)
        assertEquals(OcrFrameMeta.QUALITY_DUPLICATE, r2.hint)
    }

    @Test fun `unrelated frames are not duplicates`() {
        val a = textLikeFrame(seed = 1)
        val b = textLikeFrame(seed = 2)
        val rA = OcrFramePresort.score(a, 64, 64)
        val rB = OcrFramePresort.score(b, 64, 64, previousDHash = rA.dHash)
        assertNotEquals(OcrFrameMeta.QUALITY_DUPLICATE, rB.hint)
    }

    @Test fun `client thresholds mirror service thresholds for the values that matter`() {
        // PR C1's service-side OcrFrameQualityPresort.Thresholds carry the
        // same values — if they drift we want this test to scream so a
        // future tuning pass syncs both sides.
        assertEquals(150.0, OcrFramePresort.Thresholds.BLUR_VARIANCE_MIN, 0.0)
        assertEquals(25, OcrFramePresort.Thresholds.LUMA_MEAN_MIN)
        assertEquals(240, OcrFramePresort.Thresholds.LUMA_MEAN_MAX)
        assertEquals(5, OcrFramePresort.Thresholds.DUPLICATE_HAMMING_MAX)
        assertEquals(8.0, OcrFramePresort.Thresholds.SOBEL_MEAN_MIN, 0.0)
    }

    @Test fun `hamming computes XOR popcount`() {
        assertEquals(0, OcrFramePresort.hamming(0uL, 0uL))
        assertEquals(64, OcrFramePresort.hamming(0xAAAAAAAAAAAAAAAAuL, 0x5555555555555555uL))
    }

    @Test fun `lumaMean of uniform frame returns that value`() {
        assertEquals(100, OcrFramePresort.lumaMean(ByteArray(100) { 100 }))
    }

    @Test fun `laplacianVariance of uniform frame is zero`() {
        assertEquals(0.0, OcrFramePresort.laplacianVariance(ByteArray(100) { 128.toByte() }, 10, 10), 0.01)
    }

    private fun textLikeFrame(width: Int = 64, height: Int = 64, seed: Int = 0): ByteArray {
        val rng = Random(seed)
        val out = ByteArray(width * height)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val rowBand = (y / 4) % 2
                val base = if (rowBand == 0) 235 else 25
                val jitter = if (rowBand == 0) rng.nextInt(-15, 16) else rng.nextInt(-10, 11)
                out[y * width + x] = (base + jitter).coerceIn(0, 255).toByte()
            }
        }
        return out
    }
}
