package com.adsamcik.mindlayer.service.engine

import android.os.ParcelFileDescriptor
import com.adsamcik.mindlayer.MediaPart
import com.adsamcik.mindlayer.service.ipc.SharedMemoryPool
import com.adsamcik.mindlayer.service.security.IpcInputValidator
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Binder transport rejection coverage for the v0.8 OCR raw-Y-plane path.
 *
 * Pairs with [MediaPartYPlaneExtractorTest] (happy-path RGBA→Y) to pin the
 * service-side defense-in-depth checks that catch malformed
 * [com.adsamcik.mindlayer.MediaPart] payloads BEFORE the engine sees them.
 * Covers each rejection branch in
 * [MediaPartYPlaneExtractor.extractRawYPlane] (and the kind/size
 * preconditions in [MediaPartYPlaneExtractor.extractY]):
 *
 *  - non-image `MediaPart.kind`
 *  - oversized raw Y-plane (24 MP cap)
 *  - width / height / rowStride layout errors
 *  - width × height arithmetic overflow
 *  - payloadBytes smaller than the row layout requires
 *  - payloadBytes claiming > Int.MAX_VALUE bytes (Robolectric integer overflow guard)
 *  - PFD closed BEFORE the service reads (short read → wire error)
 *  - source PFD is always closed on every rejection path
 *
 * The encoded-image path (JPEG / PNG / WEBP via SharedMemoryPool.stageImage)
 * needs a live `SharedMemory` mapping and lives in the instrumented suite;
 * the audit's SHM-exhaustion item is exercised by
 * [com.adsamcik.mindlayer.service.ipc.SharedMemoryPoolBoundsTest]
 * with the live pool. Here we only assert the wrapper's contract.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OcrBinderTransportRejectionTest {

    private fun ratePipe(): Array<ParcelFileDescriptor> = ParcelFileDescriptor.createPipe()

    private fun rawYPart(
        source: ParcelFileDescriptor,
        width: Int,
        height: Int,
        rowStride: Int = width,
        payloadBytes: Long = (width.toLong() * height.toLong()),
        kind: Int = MediaPart.KIND_IMAGE,
        mimeType: String? = IpcInputValidator.OCR_RAW_Y_PLANE_MIME,
    ): MediaPart = MediaPart(
        requestId = "ocr-bin-tx",
        kind = kind,
        mimeType = mimeType,
        source = source,
        isSharedMemory = false,
        payloadBytes = payloadBytes,
        width = width,
        height = height,
        rowStride = rowStride,
    )

    private fun assertWireError(block: () -> Unit): SecurityException {
        val ex = assertThrows(SecurityException::class.java) { block() }
        // Wire-prefixed message must encode INVALID_REQUEST (= 3001) so
        // the SDK can map the failure back to a typed error via
        // [MindlayerErrorCode.codeFromWireMessage].
        val expectedCode = MindlayerErrorCode.INVALID_REQUEST
        val parsed = MindlayerErrorCode.codeFromWireMessage(ex.message)
        assertEquals(
            "wire-error must encode INVALID_REQUEST ($expectedCode), was: ${ex.message}",
            expectedCode,
            parsed,
        )
        return ex
    }

    // ── Kind / MIME gating ──────────────────────────────────────────────

    @Test fun `extractY rejects non-image MediaPart kind`() {
        val pipe = ratePipe()
        try {
            val part = rawYPart(
                source = pipe[0],
                width = 16, height = 16,
                kind = MediaPart.KIND_AUDIO,
                mimeType = "audio/wav",
            )
            assertWireError {
                MediaPartYPlaneExtractor.extractY(
                    part = part,
                    sharedMemoryPool = mockk<SharedMemoryPool>(relaxed = true),
                    scopedKey = "ocr:1:tx",
                )
            }
        } finally {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }
    }

    // ── Raw Y-plane layout guards ───────────────────────────────────────

    @Test fun `raw Y-plane rejects negative width and closes the source`() {
        val pipe = ratePipe()
        try {
            val part = rawYPart(pipe[0], width = -1, height = 10)
            assertWireError {
                MediaPartYPlaneExtractor.extractY(
                    part, mockk<SharedMemoryPool>(relaxed = true), "ocr:1:tx",
                )
            }
            assertFalse(
                "PFD source must be closed on every rejection path",
                pipe[0].fileDescriptor.valid(),
            )
        } finally {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }
    }

    @Test fun `raw Y-plane rejects negative height and closes the source`() {
        val pipe = ratePipe()
        try {
            val part = rawYPart(pipe[0], width = 10, height = -1)
            assertWireError {
                MediaPartYPlaneExtractor.extractY(
                    part, mockk<SharedMemoryPool>(relaxed = true), "ocr:1:tx",
                )
            }
            assertFalse(pipe[0].fileDescriptor.valid())
        } finally {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }
    }

    @Test fun `raw Y-plane rejects rowStride smaller than width`() {
        val pipe = ratePipe()
        try {
            val part = rawYPart(pipe[0], width = 16, height = 8, rowStride = 4)
            assertWireError {
                MediaPartYPlaneExtractor.extractY(
                    part, mockk<SharedMemoryPool>(relaxed = true), "ocr:1:tx",
                )
            }
            assertFalse(pipe[0].fileDescriptor.valid())
        } finally {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }
    }

    @Test fun `raw Y-plane rejects width times height arithmetic overflow`() {
        val pipe = ratePipe()
        try {
            // 65_536 * 65_536 overflows Int but Math.multiplyExact() on
            // longs surfaces it — extractRawYPlane wraps with try/catch
            // and throws a wire error.
            val part = rawYPart(
                pipe[0], width = 65_536, height = 65_536, rowStride = 65_536,
                payloadBytes = 4_294_967_296L,
            )
            assertWireError {
                MediaPartYPlaneExtractor.extractY(
                    part, mockk<SharedMemoryPool>(relaxed = true), "ocr:1:tx",
                )
            }
            assertFalse(pipe[0].fileDescriptor.valid())
        } finally {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }
    }

    @Test fun `raw Y-plane rejects oversized payload above 24MP cap`() {
        val pipe = ratePipe()
        try {
            // 8000 * 4000 = 32 MP > MAX_Y_PIXELS = 24 MP.
            val part = rawYPart(
                pipe[0],
                width = 8_000, height = 4_000, rowStride = 8_000,
                payloadBytes = 32_000_000L,
            )
            assertWireError {
                MediaPartYPlaneExtractor.extractY(
                    part, mockk<SharedMemoryPool>(relaxed = true), "ocr:1:tx",
                )
            }
            assertFalse(pipe[0].fileDescriptor.valid())
        } finally {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }
    }

    @Test fun `raw Y-plane rejects payloadBytes claiming larger than Int MAX`() {
        val pipe = ratePipe()
        try {
            val part = rawYPart(
                pipe[0],
                width = 16, height = 16, rowStride = 16,
                payloadBytes = Long.MAX_VALUE,
            )
            assertWireError {
                MediaPartYPlaneExtractor.extractY(
                    part, mockk<SharedMemoryPool>(relaxed = true), "ocr:1:tx",
                )
            }
            assertFalse(pipe[0].fileDescriptor.valid())
        } finally {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }
    }

    @Test fun `raw Y-plane rejects payload smaller than the row layout requires`() {
        val pipe = ratePipe()
        try {
            // Claim 16x16 worth of bytes but advertise only 8 — well below
            // the minimum (rowStride * (height-1) + width).
            val part = rawYPart(
                pipe[0],
                width = 16, height = 16, rowStride = 16,
                payloadBytes = 8L,
            )
            assertWireError {
                MediaPartYPlaneExtractor.extractY(
                    part, mockk<SharedMemoryPool>(relaxed = true), "ocr:1:tx",
                )
            }
            assertFalse(pipe[0].fileDescriptor.valid())
        } finally {
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
        }
    }

    // ── PFD-state edges ─────────────────────────────────────────────────

    @Test fun `raw Y-plane PFD whose write end is closed before service reads surfaces a wire error`() {
        // Write end never produced any bytes. PFD pipe will read EOF
        // immediately → readExactlyAndClose throws a wire error.
        val pipe = ratePipe()
        runCatching { pipe[1].close() }
        try {
            val part = rawYPart(
                pipe[0],
                width = 8, height = 4, rowStride = 8,
                payloadBytes = 32L,
            )
            assertWireError {
                MediaPartYPlaneExtractor.extractY(
                    part, mockk<SharedMemoryPool>(relaxed = true), "ocr:1:tx",
                )
            }
            // The source PFD is consumed via AutoCloseInputStream, so it
            // must be invalid after the failure (no FD leak).
            assertFalse(pipe[0].fileDescriptor.valid())
        } finally {
            runCatching { pipe[0].close() }
        }
    }

    @Test fun `raw Y-plane happy path decodes exactly width times height bytes`() {
        // Sanity check the read path so the negative tests above don't
        // accidentally rely on a broken happy path. We pre-write the
        // payload into the pipe BEFORE handing it to extractY so the
        // service-side read never blocks under Robolectric (which does
        // not honour pipe blocking semantics consistently when many
        // other tests are concurrently churning FDs).
        val pipe = ratePipe()
        val payload = ByteArray(16 * 16) { (it and 0xFF).toByte() }
        ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
            out.write(payload)
            out.flush()
        }
        // pipe[1] is now closed → read end will EOF after delivering
        // the buffered payload.
        try {
            val part = rawYPart(pipe[0], width = 16, height = 16, rowStride = 16, payloadBytes = 256L)
            val extracted = MediaPartYPlaneExtractor.extractY(
                part, mockk<SharedMemoryPool>(relaxed = true), "ocr:1:tx",
            )
            assertEquals(16, extracted.width)
            assertEquals(16, extracted.height)
            assertEquals(256, extracted.yPlane.size)
        } finally {
            assertFalse("source PFD must be closed after extractY", pipe[0].fileDescriptor.valid())
        }
    }
}
