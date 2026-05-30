package com.adsamcik.mindlayer.service.security

import com.adsamcik.mindlayer.OcrImageOptions
import org.junit.Assert.assertThrows
import org.junit.Test

/**
 * Bound-check + shape-check regression tests for the v0.9 single-image OCR
 * options validator on [IpcInputValidator.validateOcrImageOptions].
 *
 * Companion to [IpcInputValidatorOcrTest] (which covers the session-side
 * [OcrSessionConfig] + [OcrFrameMeta] validators). Each test pins one
 * rejection rule so a regression here surfaces as a single failing test
 * with a self-describing name.
 *
 * Per `security.instructions.md`, every new AIDL entry point must validate
 * its parcelables before the engine sees them; the validator is the
 * primary defense for the `ocrImage` AIDL surface.
 */
class IpcInputValidatorOcrImageOptionsTest {

    private fun goodSchemaJson() = """{"type":"object","properties":{"x":{"type":"string"}}}"""

    private fun goodOptions(
        schemaVersion: Int = OcrImageOptions.CURRENT_SCHEMA_VERSION,
        emitBoundingBoxes: Boolean = false,
        maxLines: Int = 0,
        orientationDisabled: Boolean = false,
        languageHints: List<String> = emptyList(),
        runLlmExtraction: Boolean = false,
        extractionSchemaJson: String? = null,
        extractionDecodeBudgetTokens: Int = 0,
        optionsJson: String? = null,
    ) = OcrImageOptions(
        schemaVersion = schemaVersion,
        emitBoundingBoxes = emitBoundingBoxes,
        maxLines = maxLines,
        orientationDisabled = orientationDisabled,
        languageHints = languageHints,
        runLlmExtraction = runLlmExtraction,
        extractionSchemaJson = extractionSchemaJson,
        extractionDecodeBudgetTokens = extractionDecodeBudgetTokens,
        optionsJson = optionsJson,
    )

    @Test fun `default options pass`() {
        IpcInputValidator.validateOcrImageOptions(goodOptions())
    }

    @Test fun `bbox + maxLines + orientation disabled all pass`() {
        IpcInputValidator.validateOcrImageOptions(
            goodOptions(emitBoundingBoxes = true, maxLines = 32, orientationDisabled = true),
        )
    }

    @Test fun `unknown schemaVersion is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrImageOptions(goodOptions(schemaVersion = 99))
        }
    }

    @Test fun `negative maxLines is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrImageOptions(goodOptions(maxLines = -1))
        }
    }

    @Test fun `maxLines over hard cap is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrImageOptions(
                goodOptions(maxLines = IpcInputValidator.MAX_OCR_IMAGE_LINES + 1),
            )
        }
    }

    @Test fun `negative decodeBudget is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrImageOptions(
                goodOptions(extractionDecodeBudgetTokens = -1),
            )
        }
    }

    @Test fun `decodeBudget over hard cap is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrImageOptions(
                goodOptions(
                    extractionDecodeBudgetTokens =
                        IpcInputValidator.MAX_OCR_IMAGE_DECODE_BUDGET_TOKENS + 1,
                ),
            )
        }
    }

    @Test fun `too many language hints is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrImageOptions(
                goodOptions(
                    languageHints = List(IpcInputValidator.MAX_OCR_LANG_HINT_COUNT + 1) { "en" },
                ),
            )
        }
    }

    @Test fun `empty language hint is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrImageOptions(goodOptions(languageHints = listOf("")))
        }
    }

    @Test fun `non-BCP47 language hint is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            // contains a slash — fails the BCP-47 regex
            IpcInputValidator.validateOcrImageOptions(
                goodOptions(languageHints = listOf("en/US")),
            )
        }
    }

    @Test fun `well-shaped BCP47 hints pass`() {
        IpcInputValidator.validateOcrImageOptions(
            goodOptions(languageHints = listOf("en", "en-US", "cs", "de-DE", "zh-Hans-CN")),
        )
    }

    @Test fun `runLlmExtraction true without schema is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrImageOptions(
                goodOptions(runLlmExtraction = true, extractionSchemaJson = null),
            )
        }
    }

    @Test fun `runLlmExtraction true with empty schema is rejected`() {
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrImageOptions(
                goodOptions(runLlmExtraction = true, extractionSchemaJson = ""),
            )
        }
    }

    @Test fun `runLlmExtraction true with valid schema passes`() {
        IpcInputValidator.validateOcrImageOptions(
            goodOptions(runLlmExtraction = true, extractionSchemaJson = goodSchemaJson()),
        )
    }

    @Test fun `runLlmExtraction false with schema is tolerated`() {
        // Caller may speculatively pass a schema even with the toggle off;
        // we only validate length in that case (it's a real string).
        IpcInputValidator.validateOcrImageOptions(
            goodOptions(runLlmExtraction = false, extractionSchemaJson = goodSchemaJson()),
        )
    }

    @Test fun `oversized extractionSchemaJson is rejected`() {
        val oversized = "a".repeat(IpcInputValidator.MAX_OCR_SCHEMA_JSON_LEN + 1)
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrImageOptions(
                goodOptions(runLlmExtraction = true, extractionSchemaJson = oversized),
            )
        }
    }

    @Test fun `oversized optionsJson is rejected`() {
        val oversized = "a".repeat(IpcInputValidator.MAX_OCR_SCHEMA_JSON_LEN + 1)
        assertThrows(IllegalArgumentException::class.java) {
            IpcInputValidator.validateOcrImageOptions(goodOptions(optionsJson = oversized))
        }
    }

    @Test fun `unknown featureFlags bits are accepted`() {
        // featureFlags is a reserved bitfield — unknown bits do NOT trip
        // validation (AIDL_STABILITY guidance: ignore unknown bits, never
        // reject the parcelable for them).
        IpcInputValidator.validateOcrImageOptions(goodOptions().copy(featureFlags = 0x7F00_0001))
    }
}
