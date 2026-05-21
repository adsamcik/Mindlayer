package com.adsamcik.mindlayer.service.engine

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OcrFeatureFlagsTest {
    private lateinit var context: Context

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        val dir = context.filesDir
        dir.listFiles()?.forEach { it.delete() }
        File(dir, "paddleocr-ppocrv5-mobile-det.tflite").writeBytes(byteArrayOf(1))
        File(dir, "paddleocr-ppocrv5-mobile-rec.tflite").writeBytes(byteArrayOf(2))
        File(dir, "paddleocr-ppocrv5-mobile-dict.txt").writeText("A")
    }

    @Test fun defaultOcrProductionFlagIsFalse() {
        assertFalse(OcrFeatureFlags.IS_PRODUCTION_READY)
        assertFalse(OcrSessionManager().isProductionReady)
    }

    @Test fun constructorInjectionControlsProductionGate() = runTest {
        val backend = FakePaddleOcrBackend()
        val engine = PaddleOcrEngine(context, backendFactory = { backend })
        engine.initialize()

        val disabled = OcrSessionManager(engine = engine, isProductionReady = false)
        val enabled = OcrSessionManager(engine = engine, isProductionReady = true)

        assertTrue(disabled.isEngineReady())
        assertFalse(disabled.isProductionReady)
        assertTrue(enabled.isEngineReady())
        assertTrue(enabled.isProductionReady)
    }
}
