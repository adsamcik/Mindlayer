package com.adsamcik.mindlayer.sdk

import android.graphics.Bitmap
import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * F-078: API 26 (minSdk) Robolectric coverage for [MediaTransfer]'s
 * pipe-based fallback.
 *
 * On API 26 (Android 8.0) [android.os.SharedMemory] is unavailable
 * (introduced API 27). [MediaTransfer.fromBitmap] therefore takes the
 * `fromBitmapPipe` branch, compressing to PNG and pumping the bytes
 * through a [android.os.ParcelFileDescriptor] pipe.
 *
 * The previously-`@Ignore`'d test for this path is re-enabled here at
 * `@Config(sdk = [26])` so the actual API-26 branch executes under
 * Robolectric's API 26 framework jar.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class MediaTransferApi26Test {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
        every { Log.e(any(), any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `fromBitmap on API 26 uses pipe fallback`() {
        val bitmap = Bitmap.createBitmap(16, 16, Bitmap.Config.ARGB_8888)
        try {
            val transfer = MediaTransfer.fromBitmap("req-bmp-pipe", bitmap)

            try {
                assertFalse(
                    "API 26 has no SharedMemory; transfer must use the pipe path",
                    transfer.isSharedMemory,
                )
                assertEquals(
                    "Pipe path compresses raw pixels to PNG before sending",
                    "image/png",
                    transfer.mimeType,
                )
                assertEquals(16, transfer.width)
                assertEquals(16, transfer.height)
                assertNotNull("Pipe read-end PFD must be returned", transfer.source)
            } finally {
                transfer.source.close()
            }
        } finally {
            bitmap.recycle()
        }
    }

    @Test
    fun `fromAudioBytes on API 26 uses pipe fallback`() {
        val payload = ByteArray(64) { 0x55 }
        val transfer = MediaTransfer.fromAudioBytes("req-aud-pipe", payload, "audio/wav")

        try {
            assertFalse(transfer.isSharedMemory)
            assertEquals("audio/wav", transfer.mimeType)
            assertNotNull(transfer.source)
        } finally {
            transfer.source.close()
        }
    }
}
