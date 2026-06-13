package com.adsamcik.mindlayer.sdk.camera.launcher

import android.os.Parcel
import com.adsamcik.mindlayer.shared.MindlayerErrorCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Round-trips every [OcrCaptureRequest] / [OcrCaptureResult] variant
 * through a [Parcel] to catch wire-protocol regressions.
 *
 * Why this matters: the launcher's contract is read by the framework
 * across an Activity Result bundle, which the OS may serialise to a
 * persistable form on memory pressure. If a field is ever added to
 * either parcelable without bumping [OcrCaptureRequest.CURRENT_SCHEMA_VERSION]
 * (or worse, if a field ordering changes), the bundle silently
 * de-marshals to a corrupt struct on restore.
 *
 * Robolectric required: the real Android [Parcel] is unavailable on
 * the host JVM otherwise.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OcrCaptureContractParcelTest {

    @Test
    fun ocrCaptureRequest_async_roundTripsThroughParcel() {
        val original = OcrCaptureRequest(
            mode = OcrCaptureMode.Async,
            profileId = OcrProfileId.Receipt,
            extractionSchemaJson = "{\"type\":\"object\"}",
            runLlmExtraction = true,
            emitBoundingBoxes = true,
            languageHints = listOf("en", "de-DE"),
            titleOverride = "Scan receipt",
        )

        val restored = original.parcelRoundTrip()
        assertEquals(original, restored)
        assertEquals(OcrCaptureRequest.CURRENT_SCHEMA_VERSION, restored.schemaVersion)
    }

    @Test
    fun ocrCaptureRequest_realtime_roundTripsThroughParcel() {
        val original = OcrCaptureRequest(
            mode = OcrCaptureMode.Realtime,
            profileId = OcrProfileId.IdCard,
            maxFrames = 30,
            languageHints = emptyList(),
        )

        val restored = original.parcelRoundTrip()
        assertEquals(original, restored)
        assertEquals(OcrCaptureMode.Realtime, restored.mode)
        assertEquals(30, restored.maxFrames)
    }

    @Test
    fun ocrCaptureRequest_defaultsRoundTrip() {
        val original = OcrCaptureRequest(
            mode = OcrCaptureMode.Async,
            profileId = OcrProfileId.GeneralDocument,
        )

        val restored = original.parcelRoundTrip()
        assertEquals(original, restored)
        assertEquals(false, restored.runLlmExtraction)
        assertEquals(false, restored.emitBoundingBoxes)
        assertEquals(0, restored.maxFrames)
    }

    @Test
    fun ocrCaptureRequest_toString_redactsSchemaAndTitle() {
        val req = OcrCaptureRequest(
            mode = OcrCaptureMode.Async,
            profileId = OcrProfileId.Receipt,
            extractionSchemaJson = "{\"sensitive\":\"data\"}",
            titleOverride = "TopSecret receipt",
        )

        val rendered = req.toString()
        assertTrue("toString must not leak schema body: $rendered", "sensitive" !in rendered)
        assertTrue("toString must not leak title body: $rendered", "TopSecret" !in rendered)
        assertTrue("toString must mark schema as redacted: $rendered", "<redacted:" in rendered)
        assertTrue("toString must mark title as set: $rendered", "<set>" in rendered)
    }

    @Test
    fun ocrCaptureResult_async_roundTripsThroughParcel() {
        val original: OcrCaptureResult = OcrCaptureResult.Async(
            fullJson = """{"lines":["hello"]}""",
            extractionJson = """{"hello":"world"}""",
            ocrDurationMs = 42L,
            llmDurationMs = 8L,
            totalDurationMs = 50L,
            backend = "CPU",
        )

        val restored = original.parcelRoundTripSealed()
        assertEquals(original, restored)
        val async = restored as OcrCaptureResult.Async
        assertEquals("""{"hello":"world"}""", async.extractionJson)
        assertEquals(42L, async.ocrDurationMs)
        assertEquals("CPU", async.backend)
    }

    @Test
    fun ocrCaptureResult_realtime_roundTripsThroughParcel() {
        val original: OcrCaptureResult = OcrCaptureResult.Realtime(
            finalJson = "{\"total\":\"12.34\"}",
            framesPushed = 17,
        )

        val restored = original.parcelRoundTripSealed()
        assertEquals(original, restored)
        val realtime = restored as OcrCaptureResult.Realtime
        assertEquals(17, realtime.framesPushed)
        assertEquals("{\"total\":\"12.34\"}", realtime.finalJson)
    }

    @Test
    fun ocrCaptureResult_cancelled_roundTripsThroughParcel() {
        val original: OcrCaptureResult = OcrCaptureResult.Cancelled

        val restored = original.parcelRoundTripSealed()
        assertEquals(OcrCaptureResult.Cancelled, restored)
    }

    @Test
    fun ocrCaptureResult_error_roundTripsThroughParcel() {
        val original: OcrCaptureResult = OcrCaptureResult.Error(
            code = MindlayerErrorCode.LOW_MEMORY,
            message = "engine OOM",
        )

        val restored = original.parcelRoundTripSealed()
        assertEquals(original, restored)
        val err = restored as OcrCaptureResult.Error
        assertEquals(MindlayerErrorCode.LOW_MEMORY, err.code)
        assertEquals("engine OOM", err.message)
    }

    @Test
    fun ocrCaptureResult_error_launcherLocalCode_roundTripsThroughParcel() {
        val original: OcrCaptureResult = OcrCaptureResult.Error(
            code = OcrCaptureResult.Error.CAMERA_PERMISSION_DENIED,
            message = "user said no",
        )

        val restored = original.parcelRoundTripSealed()
        assertEquals(original, restored)
        val err = restored as OcrCaptureResult.Error
        // Negative-coded launcher-local errors must survive the round-trip
        // unchanged so consumers can branch on them as expected.
        assertEquals(OcrCaptureResult.Error.CAMERA_PERMISSION_DENIED, err.code)
    }

    @Test
    fun ocrCaptureResult_toString_doesNotLeakFinalJson() {
        val r = OcrCaptureResult.Realtime(
            finalJson = "{\"secret\":\"shhhh\"}",
            framesPushed = 5,
        )
        val rendered = r.toString()
        assertTrue("toString must not leak finalJson body: $rendered", "secret" !in rendered)
        assertTrue("toString must redact length: $rendered", "<redacted:" in rendered)
    }

    @Test
    fun ocrCaptureResult_async_toString_doesNotLeakJson() {
        val r = OcrCaptureResult.Async(
            fullJson = """{"secret":"shhhh"}""",
            extractionJson = """{"total":"12.34"}""",
            totalDurationMs = 50L,
            ocrDurationMs = 42L,
            llmDurationMs = 8L,
            backend = "CPU",
        )
        val rendered = r.toString()
        assertTrue("toString must not leak fullJson body: $rendered", "secret" !in rendered)
        assertTrue("toString must not leak extractionJson body: $rendered", "12.34" !in rendered)
        assertTrue("toString must redact JSON lengths: $rendered", "<redacted:" in rendered)
    }

    /** Helper: data-class parcelable round trip via Parcel + writeParcelable / readParcelable. */
    private fun OcrCaptureRequest.parcelRoundTrip(): OcrCaptureRequest {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(this, 0)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            val restored = parcel.readParcelable<OcrCaptureRequest>(
                OcrCaptureRequest::class.java.classLoader,
            )
            assertNotNull(restored)
            return restored!!
        } finally {
            parcel.recycle()
        }
    }

    /**
     * Helper: sealed-Parcelable round trip via [Parcel.writeParcelable] /
     * [Parcel.readParcelable] so each variant goes through the same code
     * path the framework uses for Activity-result bundles.
     */
    private fun OcrCaptureResult.parcelRoundTripSealed(): OcrCaptureResult {
        val parcel = Parcel.obtain()
        try {
            parcel.writeParcelable(this, 0)
            parcel.setDataPosition(0)
            @Suppress("DEPRECATION")
            val restored = parcel.readParcelable<OcrCaptureResult>(
                OcrCaptureResult::class.java.classLoader,
            )
            assertNotNull(restored)
            return restored!!
        } finally {
            parcel.recycle()
        }
    }
}
