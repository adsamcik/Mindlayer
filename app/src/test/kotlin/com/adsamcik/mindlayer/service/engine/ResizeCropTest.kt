package com.adsamcik.mindlayer.service.engine

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ResizeCropTest {
    @Test fun `recognition crop preserves aspect ratio and right pads with zeros`() {
        val backend = LiteRtPaddleOcrBackend.forTesting(
            context = ApplicationProvider.getApplicationContext(),
            runnerFactory = { _, _ -> error("not used") },
        )
        val cropClass = Class.forName(
            "com.adsamcik.mindlayer.service.engine.LiteRtPaddleOcrBackend\$DetectionCandidate",
        )
        val candidate = cropClass.declaredConstructors.first { it.parameterTypes.size == 6 }.apply {
            isAccessible = true
        }.newInstance(0f, 0f, 1f, 1f, 0.9f, floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f))
        val resize = LiteRtPaddleOcrBackend::class.java.getDeclaredMethod(
            "resizeCropToRgbFloat",
            ByteArray::class.java,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            cropClass,
            Int::class.javaPrimitiveType,
            Int::class.javaPrimitiveType,
            Class.forName("com.adsamcik.mindlayer.service.engine.LiteRtPaddleOcrBackend\$Normalization"),
            Boolean::class.javaPrimitiveType,
        )
        resize.isAccessible = true
        val centered = enumValue("CENTERED")
        val yPlane = ByteArray(64 * 96) { 127.toByte() }
        val out = resize.invoke(backend, yPlane, 64, 96, candidate, 320, 48, centered, false) as FloatArray

        for (y in 0 until 48) {
            assertEquals(0f, out[(y * 320 + 31) * 3], 0.01f)
            assertEquals(0f, out[(y * 320 + 32) * 3], 0.0f)
            assertEquals(0f, out[(y * 320 + 319) * 3], 0.0f)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun enumValue(name: String): Any {
        val enumClass = Class.forName(
            "com.adsamcik.mindlayer.service.engine.LiteRtPaddleOcrBackend\$Normalization",
        ) as Class<out Enum<*>>
        return java.lang.Enum.valueOf(enumClass, name)
    }
}
