package com.adsamcik.mindlayer

import android.os.Parcel
import android.os.Parcelable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `Parcel.marshall` / `unmarshall` round-trip tests for the v0.8 multi-frame
 * OCR parcelables.
 *
 * Per `docs/AIDL_STABILITY.md` these parcelables are **wire-frozen** once
 * shipped. If any of these tests fail, the wire format has drifted.
 *
 * Each parcelable also asserts:
 *   1. `schemaVersion` is the first field at the current version.
 *   2. `featureFlags` is the last field (reserved bitfield).
 *   3. The CREATOR field exists and rehydrates equivalent instances.
 *   4. `toString()` redacts user-supplied content (privacy invariant).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class OcrParcelableTest {

    private inline fun <reified T : Parcelable> roundtrip(value: T): T {
        val parcel = Parcel.obtain()
        try {
            value.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            parcel.recycle()
            val second = Parcel.obtain()
            try {
                second.unmarshall(bytes, 0, bytes.size)
                second.setDataPosition(0)
                @Suppress("UNCHECKED_CAST")
                val creator = T::class.java.getField("CREATOR").get(null) as Parcelable.Creator<T>
                return creator.createFromParcel(second)
            } finally {
                second.recycle()
            }
        } finally {
            if (parcel.dataPosition() >= 0) parcel.recycle()
        }
    }

    // ── OcrSessionConfig ─────────────────────────────────────────────────

    @Test fun `OcrSessionConfig roundtrips with all fields populated`() {
        val cfg = OcrSessionConfig(
            schemaVersion = OcrSessionConfig.CURRENT_SCHEMA_VERSION,
            mode = OcrSessionConfig.MODE_RECEIPT,
            outputSchemaJson = """{"type":"object","properties":{"total":{"type":"number"}}}""",
            languageHints = listOf("en", "de-DE"),
            maxFrames = 30,
            frameRateLimitFps = 5,
            optionsJson = """{"presort":{"blurMin":150}}""",
            featureFlags = 0,
        )
        assertEquals(cfg, roundtrip(cfg))
    }

    @Test fun `OcrSessionConfig roundtrips with minimal defaults`() {
        val cfg = OcrSessionConfig(
            mode = OcrSessionConfig.MODE_GENERAL_DOCUMENT,
            outputSchemaJson = """{"type":"object"}""",
        )
        val rt = roundtrip(cfg)
        assertEquals(cfg, rt)
        assertEquals(1, rt.schemaVersion)
        assertEquals(emptyList<String>(), rt.languageHints)
        assertEquals(0, rt.maxFrames)
        assertEquals(0, rt.frameRateLimitFps)
        assertEquals(null, rt.optionsJson)
        assertEquals(0, rt.featureFlags)
    }

    @Test fun `OcrSessionConfig toString redacts schema and options`() {
        val cfg = OcrSessionConfig(
            mode = OcrSessionConfig.MODE_RECEIPT,
            outputSchemaJson = """{"secret":"do-not-log"}""",
            optionsJson = """{"hidden":"option"}""",
        )
        val s = cfg.toString()
        assertFalse("toString must not leak schema content", s.contains("secret"))
        assertFalse("toString must not leak options content", s.contains("hidden"))
        assertTrue(s.contains("<redacted:"))
    }

    @Test fun `OcrSessionConfig mode constants are wire-stable`() {
        assertEquals(1, OcrSessionConfig.MODE_GENERAL_DOCUMENT)
        assertEquals(2, OcrSessionConfig.MODE_RECEIPT)
        assertEquals(3, OcrSessionConfig.MODE_ID_CARD)
        assertEquals(4, OcrSessionConfig.MODE_WHITEBOARD)
        assertEquals(5, OcrSessionConfig.MODE_SCREEN_CAPTURE)
        assertEquals(5, OcrSessionConfig.ALL_MODES.size)
    }

    // ── OcrFrameMeta ─────────────────────────────────────────────────────

    @Test fun `OcrFrameMeta roundtrips with all fields`() {
        val meta = OcrFrameMeta(
            schemaVersion = OcrFrameMeta.CURRENT_SCHEMA_VERSION,
            frameId = 1234567890L,
            captureTimeMs = 1_700_000_000_000L,
            rotationDegrees = 90,
            regionJson = """[{"x":0.1,"y":0.2,"w":0.8,"h":0.5}]""",
            qualityHint = OcrFrameMeta.QUALITY_GOOD,
            extraJson = """{"gyro":[0.01,0.02,0.03]}""",
            featureFlags = 0,
        )
        assertEquals(meta, roundtrip(meta))
    }

    @Test fun `OcrFrameMeta roundtrips with minimal fields`() {
        val meta = OcrFrameMeta(frameId = 1L, captureTimeMs = 0L)
        val rt = roundtrip(meta)
        assertEquals(meta, rt)
        assertEquals(0, rt.rotationDegrees)
        assertEquals(null, rt.regionJson)
        assertEquals(OcrFrameMeta.QUALITY_UNKNOWN, rt.qualityHint)
        assertEquals(null, rt.extraJson)
        assertEquals(0, rt.featureFlags)
    }

    @Test fun `OcrFrameMeta toString redacts region`() {
        val meta = OcrFrameMeta(
            frameId = 7L,
            captureTimeMs = 0L,
            regionJson = """[{"secret-roi":"do-not-log"}]""",
        )
        val s = meta.toString()
        assertFalse(s.contains("secret-roi"))
        assertTrue(s.contains("<redacted:"))
    }

    @Test fun `OcrFrameMeta quality and rotation constants are wire-stable`() {
        assertEquals(0, OcrFrameMeta.QUALITY_UNKNOWN)
        assertEquals(1, OcrFrameMeta.QUALITY_GOOD)
        assertEquals(2, OcrFrameMeta.QUALITY_BLURRY)
        assertEquals(3, OcrFrameMeta.QUALITY_TOO_DARK)
        assertEquals(4, OcrFrameMeta.QUALITY_DUPLICATE)
        assertEquals(5, OcrFrameMeta.ALL_QUALITY_HINTS.size)
        assertEquals(setOf(0, 90, 180, 270), OcrFrameMeta.ALLOWED_ROTATIONS)
    }

    // ── OcrFrameAck ──────────────────────────────────────────────────────

    @Test fun `OcrFrameAck roundtrips for each status`() {
        for (status in OcrFrameAck.ALL_STATUSES) {
            val ack = OcrFrameAck(
                frameId = 42L,
                status = status,
                queueDepth = 3,
                retryAfterMs = if (status == OcrFrameAck.STATUS_DROPPED_BUSY) 250L else 0L,
            )
            assertEquals(ack, roundtrip(ack))
        }
    }

    @Test fun `OcrFrameAck status constants are wire-stable`() {
        assertEquals(1, OcrFrameAck.STATUS_ACCEPTED)
        assertEquals(2, OcrFrameAck.STATUS_DROPPED_BUSY)
        assertEquals(3, OcrFrameAck.STATUS_REJECTED_QUALITY)
        assertEquals(4, OcrFrameAck.STATUS_REJECTED_FINALIZED)
        assertEquals(4, OcrFrameAck.ALL_STATUSES.size)
    }

    // ── OcrSessionState ──────────────────────────────────────────────────

    @Test fun `OcrSessionState roundtrips with all fields`() {
        val state = OcrSessionState(
            sessionId = "ocr-abcdef12",
            phase = OcrSessionState.PHASE_ACTIVE,
            framesAccepted = 12,
            framesDropped = 1,
            framesRejected = 2,
            pendingQueueDepth = 1,
            streamAttached = true,
            createdAtMs = 1_700_000_000_000L,
            lastFrameAtMs = 1_700_000_005_000L,
        )
        assertEquals(state, roundtrip(state))
    }

    @Test fun `OcrSessionState phase constants are wire-stable`() {
        assertEquals(1, OcrSessionState.PHASE_ACTIVE)
        assertEquals(2, OcrSessionState.PHASE_FINALIZING)
        assertEquals(3, OcrSessionState.PHASE_FINALIZED)
        assertEquals(4, OcrSessionState.PHASE_CLOSED)
        assertEquals(4, OcrSessionState.ALL_PHASES.size)
    }

    // ── OcrLimits ────────────────────────────────────────────────────────

    @Test fun `OcrLimits roundtrips with non-zero values`() {
        val lim = OcrLimits(
            maxConcurrentOcrSessions = 1,
            maxOcrFramesPerMinute = 120,
            maxFramesPerOcrSession = 60,
            maxOcrSessionDurationMs = 300_000L,
            ocrPerFrameDecodeBudgetTokens = 1024,
            ocrSchemaJsonMaxLen = 16 * 1024,
        )
        assertEquals(lim, roundtrip(lim))
    }

    @Test fun `OcrLimits zeroBaseline rehydrates as OCR-disabled`() {
        val baseline = OcrLimits.zeroBaseline()
        val rt = roundtrip(baseline)
        assertEquals(baseline, rt)
        assertEquals(0, rt.maxConcurrentOcrSessions)
        assertEquals(0, rt.maxOcrFramesPerMinute)
        assertEquals(0, rt.maxFramesPerOcrSession)
        assertEquals(0L, rt.maxOcrSessionDurationMs)
        assertEquals(0, rt.ocrPerFrameDecodeBudgetTokens)
        assertEquals(0, rt.ocrSchemaJsonMaxLen)
        assertEquals(1, rt.schemaVersion)
    }

    // ── CREATOR contract (per Parcelable.Creator API) ────────────────────

    @Test fun `every OCR parcelable exposes a CREATOR field`() {
        assertNotNull(OcrSessionConfig::class.java.getField("CREATOR").get(null))
        assertNotNull(OcrFrameMeta::class.java.getField("CREATOR").get(null))
        assertNotNull(OcrFrameAck::class.java.getField("CREATOR").get(null))
        assertNotNull(OcrSessionState::class.java.getField("CREATOR").get(null))
        assertNotNull(OcrLimits::class.java.getField("CREATOR").get(null))
    }
}
