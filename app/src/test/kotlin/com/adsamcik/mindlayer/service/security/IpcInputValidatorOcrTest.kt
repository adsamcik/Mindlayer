package com.adsamcik.mindlayer.service.security

import android.os.ParcelFileDescriptor
import com.adsamcik.mindlayer.MediaPart
import com.adsamcik.mindlayer.OcrFrameMeta
import com.adsamcik.mindlayer.OcrSessionConfig
import io.mockk.mockk
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Bound-check + shape-check regression tests for the v0.8 OCR ingress
 * validators on [IpcInputValidator]. Each test pins one rejection rule.
 *
 * Per `security.instructions.md`, every new AIDL entry point must validate
 * its parcelables before the engine sees them; this test suite is the
 * primary defense against malformed [OcrSessionConfig] / [OcrFrameMeta]
 * payloads.
 */
class IpcInputValidatorOcrTest {

    private fun goodSchemaJson() = """{"type":"object","properties":{"x":{"type":"string"}}}"""

    private fun goodConfig(
        mode: Int = OcrSessionConfig.MODE_GENERAL_DOCUMENT,
        outputSchemaJson: String = goodSchemaJson(),
        languageHints: List<String> = emptyList(),
        maxFrames: Int = 0,
        frameRateLimitFps: Int = 0,
        optionsJson: String? = null,
        schemaVersion: Int = OcrSessionConfig.CURRENT_SCHEMA_VERSION,
    ) = OcrSessionConfig(
        schemaVersion = schemaVersion,
        mode = mode,
        outputSchemaJson = outputSchemaJson,
        languageHints = languageHints,
        maxFrames = maxFrames,
        frameRateLimitFps = frameRateLimitFps,
        optionsJson = optionsJson,
    )

    private fun goodMeta(
        frameId: Long = 1L,
        captureTimeMs: Long = 0L,
        rotationDegrees: Int = 0,
        regionJson: String? = null,
        qualityHint: Int = OcrFrameMeta.QUALITY_UNKNOWN,
        extraJson: String? = null,
        schemaVersion: Int = OcrFrameMeta.CURRENT_SCHEMA_VERSION,
    ) = OcrFrameMeta(
        schemaVersion = schemaVersion,
        frameId = frameId,
        captureTimeMs = captureTimeMs,
        rotationDegrees = rotationDegrees,
        regionJson = regionJson,
        qualityHint = qualityHint,
        extraJson = extraJson,
    )

    // ── OcrSessionConfig ─────────────────────────────────────────────────

    @Test fun `validateOcrSessionConfig accepts minimal valid config`() {
        IpcInputValidator.validateOcrSessionConfig(goodConfig())
    }

    @Test fun `validateOcrSessionConfig accepts all 5 modes`() {
        for (mode in OcrSessionConfig.ALL_MODES) {
            IpcInputValidator.validateOcrSessionConfig(goodConfig(mode = mode))
        }
    }

    @Test fun `validateOcrSessionConfig rejects unknown mode`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrSessionConfig(goodConfig(mode = 999))
        }
    }

    @Test fun `validateOcrSessionConfig rejects mode 0`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrSessionConfig(goodConfig(mode = 0))
        }
    }

    @Test fun `validateOcrSessionConfig rejects newer schemaVersion`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrSessionConfig(goodConfig(schemaVersion = 2))
        }
    }

    @Test fun `validateOcrSessionConfig rejects empty outputSchemaJson`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrSessionConfig(goodConfig(outputSchemaJson = ""))
        }
    }

    @Test fun `validateOcrSessionConfig rejects outputSchemaJson over 16 KiB`() {
        val tooLong = "x".repeat(IpcInputValidator.MAX_OCR_SCHEMA_JSON_LEN + 1)
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrSessionConfig(goodConfig(outputSchemaJson = tooLong))
        }
    }

    @Test fun `validateOcrSessionConfig accepts outputSchemaJson at boundary`() {
        val maxLen = "x".repeat(IpcInputValidator.MAX_OCR_SCHEMA_JSON_LEN)
        IpcInputValidator.validateOcrSessionConfig(goodConfig(outputSchemaJson = maxLen))
    }

    @Test fun `validateOcrSessionConfig rejects optionsJson over 16 KiB`() {
        val tooLong = "x".repeat(IpcInputValidator.MAX_OCR_SCHEMA_JSON_LEN + 1)
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrSessionConfig(goodConfig(optionsJson = tooLong))
        }
    }

    @Test fun `validateOcrSessionConfig accepts valid BCP-47 language hints`() {
        IpcInputValidator.validateOcrSessionConfig(
            goodConfig(languageHints = listOf("en", "de-DE", "zh-Hans-CN"))
        )
    }

    @Test fun `validateOcrSessionConfig rejects malformed language hint`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrSessionConfig(
                goodConfig(languageHints = listOf("not a tag"))
            )
        }
    }

    @Test fun `validateOcrSessionConfig rejects empty language hint`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrSessionConfig(
                goodConfig(languageHints = listOf(""))
            )
        }
    }

    @Test fun `validateOcrSessionConfig rejects too many language hints`() {
        val many = (1..IpcInputValidator.MAX_OCR_LANG_HINT_COUNT + 1).map { "lang" }
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrSessionConfig(goodConfig(languageHints = many))
        }
    }

    @Test fun `validateOcrSessionConfig rejects negative maxFrames`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrSessionConfig(goodConfig(maxFrames = -1))
        }
    }

    @Test fun `validateOcrSessionConfig rejects oversized maxFrames`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrSessionConfig(
                goodConfig(maxFrames = IpcInputValidator.MAX_OCR_MAX_FRAMES + 1)
            )
        }
    }

    @Test fun `validateOcrSessionConfig accepts maxFrames at boundary`() {
        IpcInputValidator.validateOcrSessionConfig(
            goodConfig(maxFrames = IpcInputValidator.MAX_OCR_MAX_FRAMES)
        )
    }

    @Test fun `validateOcrSessionConfig rejects negative frameRateLimitFps`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrSessionConfig(goodConfig(frameRateLimitFps = -1))
        }
    }

    @Test fun `validateOcrSessionConfig rejects oversized frameRateLimitFps`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrSessionConfig(
                goodConfig(frameRateLimitFps = IpcInputValidator.MAX_OCR_FRAME_RATE_LIMIT_FPS + 1)
            )
        }
    }

    // ── OcrFrameMeta ─────────────────────────────────────────────────────

    @Test fun `validateOcrFrameMeta accepts minimal valid meta`() {
        IpcInputValidator.validateOcrFrameMeta(goodMeta())
    }

    @Test fun `validateOcrFrameMeta accepts all four allowed rotations`() {
        for (rot in OcrFrameMeta.ALLOWED_ROTATIONS) {
            IpcInputValidator.validateOcrFrameMeta(goodMeta(rotationDegrees = rot))
        }
    }

    @Test fun `validateOcrFrameMeta rejects 45 degree rotation`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrFrameMeta(goodMeta(rotationDegrees = 45))
        }
    }

    @Test fun `validateOcrFrameMeta rejects negative frameId`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrFrameMeta(goodMeta(frameId = -1L))
        }
    }

    @Test fun `validateOcrFrameMeta accepts every known quality hint`() {
        for (q in OcrFrameMeta.ALL_QUALITY_HINTS) {
            IpcInputValidator.validateOcrFrameMeta(goodMeta(qualityHint = q))
        }
    }

    @Test fun `validateOcrFrameMeta rejects unknown quality hint`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrFrameMeta(goodMeta(qualityHint = 999))
        }
    }

    @Test fun `validateOcrFrameMeta rejects negative captureTimeMs`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrFrameMeta(goodMeta(captureTimeMs = -1L))
        }
    }

    @Test fun `validateOcrFrameMeta rejects regionJson over 16 KiB`() {
        val tooLong = "x".repeat(IpcInputValidator.MAX_OCR_SCHEMA_JSON_LEN + 1)
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrFrameMeta(goodMeta(regionJson = tooLong))
        }
    }

    @Test fun `validateOcrFrameMeta rejects extraJson over 16 KiB`() {
        val tooLong = "x".repeat(IpcInputValidator.MAX_OCR_SCHEMA_JSON_LEN + 1)
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrFrameMeta(goodMeta(extraJson = tooLong))
        }
    }

    @Test fun `validateOcrFrameMeta rejects newer schemaVersion`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrFrameMeta(goodMeta(schemaVersion = 2))
        }
    }

    @Test fun `validateImageTransfer rejects raw Y-plane above OCR cap`() {
        val part = MediaPart(
            requestId = "ocr-raw",
            kind = MediaPart.KIND_IMAGE,
            mimeType = IpcInputValidator.OCR_RAW_Y_PLANE_MIME,
            source = mockk<ParcelFileDescriptor>(relaxed = true),
            isSharedMemory = false,
            payloadBytes = 32_000_000L,
            width = 8_000,
            height = 4_000,
            rowStride = 8_000,
        )

        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateImageTransfer(part, maxMediaBytes = 100 * 1024 * 1024)
        }
    }
}
