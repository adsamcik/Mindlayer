package com.adsamcik.mindlayer.service.engine

import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Pure-JVM-testable serializer for the inline-embedding SharedMemory
 * blob layout.
 *
 * Extracted from `EmbeddingCoordinator` so:
 *   1. JVM tests can verify offsets / endianness / bounds / error
 *      cases against a plain [ByteBuffer] without needing Robolectric
 *      to fake [android.os.SharedMemory] (which it cannot do
 *      faithfully — see the @Ignore history on the old test).
 *   2. Real-SharedMemory verification lives in an instrumented test
 *      under `app/src/androidTest/...` that exercises this same
 *      helper against a [android.os.SharedMemory] mapping.
 *   3. Both `writeTransfer` (SHM-backed path) and `writeBlobFile`
 *      (file-backed deferred path) call this helper and share the
 *      same layout invariants.
 *
 * # Wire layout (little-endian, 8-byte header + payload)
 *
 * | Offset | Size | Field |
 * |---|---|---|
 * | 0 | 4 | `count` (int32) — number of vectors |
 * | 4 | 4 | `dim` (int32) — dimensions per vector |
 * | 8 | `count * dim * 4` | tightly-packed float32 vectors |
 *
 * Total bytes: `8 + count * dim * 4`. [checkedBlobSize] enforces the
 * `Int.MAX_VALUE` ceiling.
 *
 * # Invariants
 *
 *  - All numbers are LITTLE_ENDIAN ([LAYOUT_BYTE_ORDER]).
 *  - All vectors must share the same `dim` as the first vector;
 *    mismatch throws `MindlayerErrorCode.INVALID_REQUEST`.
 *  - The buffer is flipped after writing so consumers can read from
 *    position 0.
 */
object EmbeddingShmLayout {

    /** Wire-stable byte order for the SHM blob. Do not change. */
    val LAYOUT_BYTE_ORDER: ByteOrder = ByteOrder.LITTLE_ENDIAN

    /** Wire-stable 8-byte header size (count: int32 + dim: int32). */
    const val HEADER_SIZE_BYTES: Int = 8

    /** Bytes per float32 element in the vector payload. */
    const val BYTES_PER_FLOAT: Int = 4

    /**
     * Compute the total blob size for `count * dim` float32 values
     * plus the 8-byte header. Throws
     * [MindlayerErrorCode.INVALID_REQUEST] when the result would
     * overflow `Int.MAX_VALUE` or any input is negative.
     */
    fun checkedBlobSize(count: Int, dim: Int): Int {
        val size = HEADER_SIZE_BYTES.toLong() + count.toLong() * dim.toLong() * BYTES_PER_FLOAT.toLong()
        if (count < 0 || dim < 0 || size > Int.MAX_VALUE) {
            throw SecurityException(
                MindlayerErrorCode.wireMessage(
                    MindlayerErrorCode.INVALID_REQUEST,
                    "embedding blob too large",
                ),
            )
        }
        return size.toInt()
    }

    /**
     * Write the inline-embedding SHM layout into [buffer].
     *
     * The buffer must already be sized at least [checkedBlobSize] and
     * positioned at 0. This call sets the buffer's byte order to
     * [LAYOUT_BYTE_ORDER], writes the header + vectors, and flips the
     * buffer so consumers can read from position 0.
     *
     * @throws SecurityException with [MindlayerErrorCode.INVALID_REQUEST]
     *   wire prefix when any result's `dim` or `vector.size` differs
     *   from the first result's `dim`.
     */
    fun writeLayout(buffer: ByteBuffer, results: List<com.adsamcik.mindlayer.EmbeddingResult>) {
        buffer.order(LAYOUT_BYTE_ORDER)
        val dim = results.firstOrNull()?.dim ?: 0
        buffer.putInt(results.size)
        buffer.putInt(dim)
        for (result in results) {
            if (result.dim != dim || result.vector.size != dim) {
                throw SecurityException(
                    MindlayerErrorCode.wireMessage(
                        MindlayerErrorCode.INVALID_REQUEST,
                        "mixed embedding dimensions",
                    ),
                )
            }
            result.vector.forEach { buffer.putFloat(it) }
        }
        buffer.flip()
    }
}
