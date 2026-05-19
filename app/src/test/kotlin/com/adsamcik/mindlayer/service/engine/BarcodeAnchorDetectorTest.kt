package com.adsamcik.mindlayer.service.engine

import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.MultiFormatWriter
import com.google.zxing.common.BitMatrix
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.EnumMap

/**
 * Pure-JVM unit tests for [BarcodeAnchorDetector].
 *
 * Strategy: use ZXing's own [MultiFormatWriter] to render a known
 * payload into a [BitMatrix], convert the matrix to an 8-bit Y-plane
 * (0 for dark modules, 255 for light), feed it through the detector,
 * and assert the decoded value + format + bbox. This exercises the
 * real decode path end-to-end without needing Android framework or
 * a camera capture.
 */
class BarcodeAnchorDetectorTest {

    private val detector = BarcodeAnchorDetector()

    @Test fun `decodes a QR code from a synthetic Y-plane`() {
        val payload = "https://example.com/p/9981273"
        val (yPlane, w, h) = renderToYPlane(payload, BarcodeFormat.QR_CODE, 256, 256)
        val anchors = detector.decode(yPlane, w, h, frameId = 42L)
        assertEquals(1, anchors.size)
        val a = anchors[0]
        assertEquals("QR_CODE", a.format)
        assertEquals(payload, a.value)
        assertEquals(42L, a.frameId)
        // QR has 4 finder/alignment points → 4-corner bbox.
        assertNotNull("QR should yield a bounding box", a.boundingBox)
        assertEquals(8, a.boundingBox!!.size)
        for (v in a.boundingBox) {
            assertTrue("bbox coords should be in [0,1], got $v", v in 0f..1f)
        }
    }

    @Test fun `decodes an EAN-13 from a synthetic Y-plane`() {
        val payload = "9780201379624" // ISBN-13 with valid checksum
        val (yPlane, w, h) = renderToYPlane(payload, BarcodeFormat.EAN_13, 400, 120)
        val anchors = detector.decode(yPlane, w, h, frameId = 7L)
        assertEquals(1, anchors.size)
        assertEquals("EAN_13", anchors[0].format)
        assertEquals(payload, anchors[0].value)
        // EAN-13 returns 2 result points → axis-aligned bbox fallback.
        assertNotNull(anchors[0].boundingBox)
        assertEquals(8, anchors[0].boundingBox!!.size)
    }

    @Test fun `decodes a Code-128 from a synthetic Y-plane`() {
        val payload = "PKG-2026-05-19-XYZ"
        val (yPlane, w, h) = renderToYPlane(payload, BarcodeFormat.CODE_128, 400, 100)
        val anchors = detector.decode(yPlane, w, h, frameId = 1L)
        assertEquals(1, anchors.size)
        assertEquals("CODE_128", anchors[0].format)
        assertEquals(payload, anchors[0].value)
    }

    @Test fun `returns empty list when frame contains no barcode`() {
        // All-grey 200x200 Y-plane (no detectable pattern).
        val grey = ByteArray(200 * 200) { 128.toByte() }
        val anchors = detector.decode(grey, 200, 200, frameId = 99L)
        assertTrue("no barcode → empty list", anchors.isEmpty())
    }

    @Test fun `returns empty list for degenerate dimensions`() {
        assertTrue(detector.decode(ByteArray(0), 0, 0).isEmpty())
        assertTrue(detector.decode(ByteArray(100), -1, 10).isEmpty())
        assertTrue(detector.decode(ByteArray(100), 10, -1).isEmpty())
    }

    @Test fun `returns empty list when yPlane is too small for declared dimensions`() {
        // 100x100 declared but only 50 bytes given.
        val anchors = detector.decode(ByteArray(50), 100, 100, frameId = 1L)
        assertTrue("underflow buffer → empty list", anchors.isEmpty())
    }

    @Test fun `tolerates over-allocated yPlane (extra trailing bytes)`() {
        val payload = "TEST123"
        val (yPlane, w, h) = renderToYPlane(payload, BarcodeFormat.CODE_128, 400, 80)
        val padded = yPlane.copyOf(yPlane.size + 1024) // extra slack
        val anchors = detector.decode(padded, w, h, frameId = 0L)
        assertEquals(1, anchors.size)
        assertEquals(payload, anchors[0].value)
    }

    @Test fun `format-restricted detector ignores excluded symbologies`() {
        val qrOnly = BarcodeAnchorDetector(formats = setOf(BarcodeFormat.QR_CODE))
        val payload = "9780201379624"
        val (yPlane, w, h) = renderToYPlane(payload, BarcodeFormat.EAN_13, 400, 120)
        val anchors = qrOnly.decode(yPlane, w, h, frameId = 0L)
        assertTrue("QR-only detector should not decode EAN-13", anchors.isEmpty())
    }

    @Test fun `frameId is propagated to the anchor`() {
        val payload = "FRAME-ID-CHECK"
        val (yPlane, w, h) = renderToYPlane(payload, BarcodeFormat.CODE_128, 400, 100)
        val anchors = detector.decode(yPlane, w, h, frameId = 1234567890L)
        assertEquals(1, anchors.size)
        assertEquals(1234567890L, anchors[0].frameId)
    }

    @Test fun `BarcodeAnchor with bbox roundtrips equals + hashCode`() {
        val a = BarcodeAnchor(
            format = "QR_CODE",
            value = "hello",
            boundingBox = floatArrayOf(0.1f, 0.2f, 0.9f, 0.2f, 0.9f, 0.8f, 0.1f, 0.8f),
            frameId = 1L,
        )
        val b = BarcodeAnchor(
            format = "QR_CODE",
            value = "hello",
            boundingBox = floatArrayOf(0.1f, 0.2f, 0.9f, 0.2f, 0.9f, 0.8f, 0.1f, 0.8f),
            frameId = 1L,
        )
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
    }

    @Test fun `BarcodeAnchor with mismatched bbox is not equal`() {
        val a = BarcodeAnchor(format = "QR_CODE", value = "x", boundingBox = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))
        val b = BarcodeAnchor(format = "QR_CODE", value = "x", boundingBox = floatArrayOf(0f, 0f, 0.5f, 0f, 0.5f, 0.5f, 0f, 0.5f))
        assertTrue("differing bboxes => not equal", a != b)
    }

    @Test fun `BarcodeAnchor null vs non-null bbox is not equal`() {
        val a = BarcodeAnchor(format = "QR_CODE", value = "x", boundingBox = null)
        val b = BarcodeAnchor(format = "QR_CODE", value = "x", boundingBox = floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))
        assertTrue("null vs non-null bbox => not equal", a != b)
    }

    @Test(expected = IllegalArgumentException::class)
    fun `BarcodeAnchor rejects wrong-sized bbox`() {
        BarcodeAnchor(format = "QR_CODE", value = "x", boundingBox = floatArrayOf(0f, 0f, 1f, 0f))
    }

    @Test fun `BarcodeAnchor with null bbox accepted`() {
        val a = BarcodeAnchor(format = "QR_CODE", value = "x", boundingBox = null)
        assertNull(a.boundingBox)
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    /**
     * Render a payload through ZXing's encoder and pack the resulting
     * black-and-white bit matrix into an 8-bit Y-plane (255 light /
     * 0 dark) so the detector can roundtrip it without needing an
     * Android Bitmap.
     */
    private fun renderToYPlane(
        payload: String,
        format: BarcodeFormat,
        width: Int,
        height: Int,
    ): Triple<ByteArray, Int, Int> {
        val hints = EnumMap<EncodeHintType, Any>(EncodeHintType::class.java).apply {
            this[EncodeHintType.MARGIN] = 8
        }
        val matrix: BitMatrix = MultiFormatWriter().encode(payload, format, width, height, hints)
        val w = matrix.width
        val h = matrix.height
        val out = ByteArray(w * h)
        for (y in 0 until h) {
            val rowOff = y * w
            for (x in 0 until w) {
                out[rowOff + x] = if (matrix.get(x, y)) 0.toByte() else 255.toByte()
            }
        }
        return Triple(out, w, h)
    }
}
