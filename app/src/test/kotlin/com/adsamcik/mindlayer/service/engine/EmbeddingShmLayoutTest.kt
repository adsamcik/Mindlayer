package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.EmbeddingResult
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-JVM tests for [EmbeddingShmLayout].
 *
 * # What this file covers — and what it does NOT
 *
 * These tests pin the binary layout invariants of the inline-
 * embedding blob — offsets, endianness, header size, payload size,
 * dimension-mismatch handling, overflow handling. They run against a
 * plain [ByteBuffer]; no Android framework dependency, no
 * Robolectric, no fake [android.os.SharedMemory].
 *
 * Real [android.os.SharedMemory] FD creation, mmap, protection,
 * parceling, and lifetime are covered by
 * [com.adsamcik.mindlayer.service.engine.EmbeddingShmLayoutInstrumentedTest]
 * under ``app/src/androidTest/``. **Do NOT** add tests here that
 * claim to validate real SHM behavior — the previous attempt was
 * @Ignore'd because Robolectric cannot faithfully simulate
 * SharedMemory and the test was creating a coverage mirage.
 */
class EmbeddingShmLayoutTest {

    // ── Constants are wire-stable ────────────────────────────────────────

    @Test fun `LAYOUT_BYTE_ORDER is LITTLE_ENDIAN`() {
        assertEquals(ByteOrder.LITTLE_ENDIAN, EmbeddingShmLayout.LAYOUT_BYTE_ORDER)
    }

    @Test fun `HEADER_SIZE_BYTES is 8`() {
        assertEquals(8, EmbeddingShmLayout.HEADER_SIZE_BYTES)
    }

    @Test fun `BYTES_PER_FLOAT is 4`() {
        assertEquals(4, EmbeddingShmLayout.BYTES_PER_FLOAT)
    }

    // ── checkedBlobSize ──────────────────────────────────────────────────

    @Test fun `checkedBlobSize for zero count and dim returns header only`() {
        assertEquals(EmbeddingShmLayout.HEADER_SIZE_BYTES, EmbeddingShmLayout.checkedBlobSize(0, 0))
    }

    @Test fun `checkedBlobSize matches 8 plus count times dim times 4 formula`() {
        // 2 vectors of dim 768: 8 + 2 * 768 * 4 = 6152.
        assertEquals(8 + 2 * 768 * 4, EmbeddingShmLayout.checkedBlobSize(2, 768))
    }

    @Test fun `checkedBlobSize rejects negative count`() {
        assertWireCode(MindlayerErrorCode.INVALID_REQUEST) {
            EmbeddingShmLayout.checkedBlobSize(-1, 4)
        }
    }

    @Test fun `checkedBlobSize rejects negative dim`() {
        assertWireCode(MindlayerErrorCode.INVALID_REQUEST) {
            EmbeddingShmLayout.checkedBlobSize(4, -1)
        }
    }

    @Test fun `checkedBlobSize rejects overflow above Int MAX_VALUE`() {
        // 2_000_000_000 * 4 = 8_000_000_000 > Int.MAX_VALUE (2_147_483_647)
        assertWireCode(MindlayerErrorCode.INVALID_REQUEST) {
            EmbeddingShmLayout.checkedBlobSize(2, Int.MAX_VALUE / 2)
        }
    }

    // ── writeLayout — empty / single vector ──────────────────────────────

    @Test fun `writeLayout on empty list writes count 0 dim 0 header`() {
        val buf = ByteBuffer.allocate(EmbeddingShmLayout.HEADER_SIZE_BYTES)
        EmbeddingShmLayout.writeLayout(buf, emptyList())
        assertEquals(0, buf.int) // count
        assertEquals(0, buf.int) // dim
    }

    @Test fun `writeLayout writes count then dim then float vectors LE`() {
        val results = listOf(
            result(floatArrayOf(1f, 2f), dim = 2, tag = "a"),
            result(floatArrayOf(3f, 4f), dim = 2, tag = "b"),
        )
        val size = EmbeddingShmLayout.checkedBlobSize(results.size, 2)
        val buf = ByteBuffer.allocate(size)
        EmbeddingShmLayout.writeLayout(buf, results)
        // Buffer is flipped — position is 0, limit is size.
        assertEquals(0, buf.position())
        assertEquals(size, buf.limit())
        assertEquals(2, buf.int) // count
        assertEquals(2, buf.int) // dim
        assertEquals(1f, buf.float, 0f)
        assertEquals(2f, buf.float, 0f)
        assertEquals(3f, buf.float, 0f)
        assertEquals(4f, buf.float, 0f)
    }

    @Test fun `writeLayout sets byte order to LITTLE_ENDIAN even when input is BE`() {
        // Verify the layout is endian-stable regardless of how the
        // caller initialised the buffer.
        val results = listOf(result(floatArrayOf(1.5f), dim = 1, tag = "x"))
        val buf = ByteBuffer.allocate(EmbeddingShmLayout.checkedBlobSize(1, 1))
        buf.order(ByteOrder.BIG_ENDIAN) // intentionally wrong
        EmbeddingShmLayout.writeLayout(buf, results)
        assertEquals(ByteOrder.LITTLE_ENDIAN, buf.order())
        assertEquals(1, buf.int)
        assertEquals(1, buf.int)
        assertEquals(1.5f, buf.float, 0f)
    }

    // ── writeLayout — dimension mismatch rejection ───────────────────────

    @Test fun `writeLayout rejects mixed dim`() {
        val results = listOf(
            result(floatArrayOf(1f, 2f), dim = 2, tag = "a"),
            result(floatArrayOf(3f, 4f, 5f), dim = 3, tag = "b"),
        )
        // Compute a buffer big enough for the larger dim to defeat
        // a "buffer overflow" red herring — we want the dim-mismatch
        // exception, not BufferOverflowException.
        val buf = ByteBuffer.allocate(EmbeddingShmLayout.HEADER_SIZE_BYTES + 2 * 3 * 4)
        assertWireCode(MindlayerErrorCode.INVALID_REQUEST) {
            EmbeddingShmLayout.writeLayout(buf, results)
        }
    }

    @Test fun `writeLayout rejects vector size mismatching declared dim`() {
        val results = listOf(
            // Constructed with dim=2 but vector has 3 elements.
            EmbeddingResult(
                tag = "bad",
                vector = floatArrayOf(1f, 2f, 3f),
                dim = 2,
                modelId = "m",
                tokenCount = 0,
                truncated = false,
                backend = "CPU",
                durationMs = 0,
            ),
        )
        val buf = ByteBuffer.allocate(EmbeddingShmLayout.HEADER_SIZE_BYTES + 1 * 3 * 4)
        assertWireCode(MindlayerErrorCode.INVALID_REQUEST) {
            EmbeddingShmLayout.writeLayout(buf, results)
        }
    }

    // ── Round-trip — exact bytes ─────────────────────────────────────────

    @Test fun `round-trip produces expected bytes for a known input`() {
        val results = listOf(
            result(floatArrayOf(0f, 1f, -1f), dim = 3, tag = "x"),
        )
        val buf = ByteBuffer.allocate(EmbeddingShmLayout.checkedBlobSize(1, 3))
        EmbeddingShmLayout.writeLayout(buf, results)
        val bytes = ByteArray(buf.limit())
        buf.get(bytes)
        // LE int32 1 -> 01 00 00 00; LE int32 3 -> 03 00 00 00;
        // float 0.0 -> 00 00 00 00; 1.0 -> 00 00 80 3F; -1.0 -> 00 00 80 BF
        val expected = byteArrayOf(
            0x01, 0x00, 0x00, 0x00,
            0x03, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x80.toByte(), 0x3F,
            0x00, 0x00, 0x80.toByte(), 0xBF.toByte(),
        )
        assertEquals(expected.toList(), bytes.toList())
    }

    // ── Helpers ──────────────────────────────────────────────────────────

    private fun result(vector: FloatArray, dim: Int, tag: String): EmbeddingResult = EmbeddingResult(
        tag = tag,
        vector = vector,
        dim = dim,
        modelId = "test-model",
        tokenCount = 0,
        truncated = false,
        backend = "CPU",
        durationMs = 0,
    )

    private inline fun assertWireCode(expectedCode: Int, block: () -> Unit) {
        try {
            block()
            error("Expected SecurityException with wire code $expectedCode but block completed.")
        } catch (e: SecurityException) {
            val actual = MindlayerErrorCode.codeFromWireMessage(e.message)
            assertNotNull("SecurityException did not carry a wire-prefixed code: ${e.message}", actual)
            assertEquals(expectedCode, actual)
        }
    }
}
