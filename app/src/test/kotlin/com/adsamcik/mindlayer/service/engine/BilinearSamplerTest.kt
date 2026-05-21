package com.adsamcik.mindlayer.service.engine

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BilinearSamplerTest {
    @Test fun `frame resize uses clamped four tap bilinear sampling`() {
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = ApplicationProvider.getApplicationContext(),
            runnerFactory = { _, _ -> error("not used") },
        )
        val resize = LiteRtPaddleOcrBackend::class.java.getDeclaredMethod(
            "resizeFrameToRgbFloat",
            ByteArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Class.forName("com.adsamcik.mindlayer.service.engine.LiteRtPaddleOcrBackend\$Normalization"),
        )
        resize.isAccessible = true
        val centered = enumValue("CENTERED")
        val input = byteArrayOf(0, 64, 128.toByte(), 255.toByte())
        val out = resize.invoke(backend, input, 2, 2, 4, 4, centered) as FloatArray

        assertEquals(0f, gray(out, 0, 0, 4), 0.001f)
        assertEquals(16f, gray(out, 1, 0, 4), 0.001f)
        assertEquals(48f, gray(out, 2, 0, 4), 0.001f)
        assertEquals(64f, gray(out, 3, 0, 4), 0.001f)
        assertEquals(51.9375f, gray(out, 1, 1, 4), 0.001f)
        assertEquals(179.4375f, gray(out, 2, 2, 4), 0.001f)
    }

    private fun gray(out: FloatArray, x: Int, y: Int, width: Int): Float =
        ((out[(y * width + x) * 3] + 1f) * 127.5f)

    @Suppress("UNCHECKED_CAST")
    private fun enumValue(name: String): Any {
        val enumClass = Class.forName(
            "com.adsamcik.mindlayer.service.engine.LiteRtPaddleOcrBackend\$Normalization",
        ) as Class<out Enum<*>>
        return java.lang.Enum.valueOf(enumClass, name)
    }
}
