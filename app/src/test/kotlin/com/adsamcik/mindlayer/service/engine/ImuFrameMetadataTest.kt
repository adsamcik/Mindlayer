package com.adsamcik.mindlayer.service.engine

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

/**
 * Unit tests for [ImuFrameMetadata.parse] — same lenient-degrade rules
 * as [PageBoundariesConfig.parse]; missing IMU data must return [NONE]
 * (gyro = 0.0) so the boundary detector contributes nothing rather than
 * spuriously firing on a malformed extraJson envelope.
 */
class ImuFrameMetadataTest {

    @Test
    fun `null extraJson returns NONE`() {
        assertSame(ImuFrameMetadata.NONE, ImuFrameMetadata.parse(null))
    }

    @Test
    fun `blank extraJson returns NONE`() {
        assertSame(ImuFrameMetadata.NONE, ImuFrameMetadata.parse("  "))
    }

    @Test
    fun `non-object extraJson returns NONE`() {
        assertSame(ImuFrameMetadata.NONE, ImuFrameMetadata.parse("\"plain string\""))
        assertSame(ImuFrameMetadata.NONE, ImuFrameMetadata.parse("[1,2,3]"))
        assertSame(ImuFrameMetadata.NONE, ImuFrameMetadata.parse("99"))
    }

    @Test
    fun `malformed JSON returns NONE without throwing`() {
        assertSame(ImuFrameMetadata.NONE, ImuFrameMetadata.parse("{broken"))
    }

    @Test
    fun `missing imu block returns NONE`() {
        assertSame(ImuFrameMetadata.NONE, ImuFrameMetadata.parse("""{"otherKey":1}"""))
    }

    @Test
    fun `imu block of wrong type returns NONE`() {
        assertSame(ImuFrameMetadata.NONE, ImuFrameMetadata.parse("""{"imu":"oops"}"""))
        assertSame(ImuFrameMetadata.NONE, ImuFrameMetadata.parse("""{"imu":4.5}"""))
    }

    @Test
    fun `missing gyro field returns NONE`() {
        assertSame(ImuFrameMetadata.NONE, ImuFrameMetadata.parse("""{"imu":{"capture_window_ms":33}}"""))
    }

    @Test
    fun `non-numeric gyro field returns NONE`() {
        assertSame(ImuFrameMetadata.NONE, ImuFrameMetadata.parse("""{"imu":{"gyro_max_rad_per_s":"fast"}}"""))
    }

    @Test
    fun `valid gyro field parses correctly`() {
        val imu = ImuFrameMetadata.parse("""{"imu":{"gyro_max_rad_per_s":1.23}}""")
        assertEquals(1.23f, imu.gyroMaxRadPerS, 1e-5f)
    }

    @Test
    fun `integer gyro value is accepted`() {
        val imu = ImuFrameMetadata.parse("""{"imu":{"gyro_max_rad_per_s":7}}""")
        assertEquals(7f, imu.gyroMaxRadPerS, 1e-5f)
    }

    @Test
    fun `reserved sibling fields are ignored without error`() {
        val imu = ImuFrameMetadata.parse(
            """
            {
              "imu": {
                "gyro_max_rad_per_s": 0.5,
                "accel_max_m_per_s2": 9.81,
                "capture_window_ms": 33
              }
            }
            """.trimIndent()
        )
        assertEquals(0.5f, imu.gyroMaxRadPerS, 1e-5f)
    }

    @Test
    fun `unknown root keys are ignored`() {
        val imu = ImuFrameMetadata.parse(
            """{"someOtherEnvelopeField":true,"imu":{"gyro_max_rad_per_s":2.0}}"""
        )
        assertEquals(2f, imu.gyroMaxRadPerS, 1e-5f)
    }

    @Test
    fun `negative gyro is clamped to zero`() {
        val imu = ImuFrameMetadata.parse("""{"imu":{"gyro_max_rad_per_s":-3.0}}""")
        assertEquals(0f, imu.gyroMaxRadPerS, 1e-5f)
    }

    @Test
    fun `non-finite gyro is clamped to zero`() {
        // kotlinx.serialization's floatOrNull accepts "NaN" → Float.NaN.
        // Our parser then clamps non-finite values to 0f. We assert by
        // value (not identity, since this returns a fresh instance).
        val imu = ImuFrameMetadata.parse("""{"imu":{"gyro_max_rad_per_s":"NaN"}}""")
        assertEquals(0f, imu.gyroMaxRadPerS, 1e-5f)
    }
}
