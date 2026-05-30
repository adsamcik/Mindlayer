package com.adsamcik.mindlayer.sdk

import com.adsamcik.mindlayer.OcrSessionConfig

/**
 * Built-in OCR profile presets.
 *
 * Each profile sets [OcrSessionConfig.mode] and a coherent default
 * ``outputSchemaJson`` so callers can do:
 *
 * ```kotlin
 * val session = mindlayer.ocrRealtime(OcrProfile.Receipt)
 * session.use { ... }
 * ```
 *
 * Callers that need a custom schema pass it as ``schemaJson`` to
 * [Mindlayer.ocrRealtime]; this overrides the profile default but
 * keeps the profile-specific ``mode``.
 *
 * The 5 profiles match the design lock from Phase 1 scope (final-design
 * § 7 + §11): GeneralDocument, Receipt, IdCard, Whiteboard,
 * ScreenCapture.
 */
sealed class OcrProfile(
    /** Wire-stable mode constant — pinned to `[OcrSessionConfig.MODE_*]`. */
    val mode: Int,
    /** Human-readable label for diagnostics + logs. */
    val displayName: String,
    /** Default output JSON schema. Callers may override. */
    val defaultSchema: String,
) {
    /**
     * General document or scene-text OCR. The default profile —
     * extracts a flat ``lines`` array of text blocks with no
     * domain-specific structure.
     */
    object GeneralDocument : OcrProfile(
        mode = OcrSessionConfig.MODE_GENERAL_DOCUMENT,
        displayName = "General document",
        defaultSchema = """{
            "type": "object",
            "properties": {
                "lines": {
                    "type": "array",
                    "items": {"type": "string"}
                }
            },
            "required": ["lines"]
        }""",
    )

    /**
     * Receipt-shaped layout. Extracts a structured object with
     * merchant, line items, subtotal, tax, total.
     */
    object Receipt : OcrProfile(
        mode = OcrSessionConfig.MODE_RECEIPT,
        displayName = "Receipt",
        defaultSchema = """{
            "type": "object",
            "properties": {
                "merchant": {"type": "string"},
                "date": {"type": "string"},
                "items": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "name": {"type": "string"},
                            "qty": {"type": "number"},
                            "price": {"type": "number"}
                        }
                    }
                },
                "subtotal": {"type": "number"},
                "tax": {"type": "number"},
                "total": {"type": "number"},
                "currency": {"type": "string"}
            }
        }""",
    )

    /**
     * ID card / MRZ-bearing layout. Extracts the MRZ lines + parsed
     * personal fields. The service-side validator (a follow-up to
     * Phase 1) checks MRZ check digits before locking the field.
     */
    object IdCard : OcrProfile(
        mode = OcrSessionConfig.MODE_ID_CARD,
        displayName = "ID card",
        defaultSchema = """{
            "type": "object",
            "properties": {
                "mrz_line_1": {"type": "string"},
                "mrz_line_2": {"type": "string"},
                "document_number": {"type": "string"},
                "given_names": {"type": "string"},
                "surname": {"type": "string"},
                "date_of_birth": {"type": "string"},
                "expiry_date": {"type": "string"},
                "nationality": {"type": "string"}
            }
        }""",
    )

    /**
     * Whiteboard / photo of a wall surface. Aggressive deskew + a
     * paragraphs-of-text shape.
     */
    object Whiteboard : OcrProfile(
        mode = OcrSessionConfig.MODE_WHITEBOARD,
        displayName = "Whiteboard",
        defaultSchema = """{
            "type": "object",
            "properties": {
                "paragraphs": {
                    "type": "array",
                    "items": {"type": "string"}
                }
            },
            "required": ["paragraphs"]
        }""",
    )

    /**
     * Phone screenshot or screen-recording frame. No perspective
     * transform. UI-element-aware extraction in a future profile
     * upgrade.
     */
    object ScreenCapture : OcrProfile(
        mode = OcrSessionConfig.MODE_SCREEN_CAPTURE,
        displayName = "Screen capture",
        defaultSchema = """{
            "type": "object",
            "properties": {
                "blocks": {
                    "type": "array",
                    "items": {
                        "type": "object",
                        "properties": {
                            "text": {"type": "string"},
                            "kind": {"type": "string"}
                        }
                    }
                }
            }
        }""",
    )

    companion object {
        /** All built-in profiles, in declaration order. Lazily initialised
         *  to avoid a sealed-class + companion-init order race where the
         *  inner ``object`` singletons can be null at the moment the
         *  companion's ``init`` runs. */
        val all: List<OcrProfile> by lazy {
            listOf(
                GeneralDocument,
                Receipt,
                IdCard,
                Whiteboard,
                ScreenCapture,
            )
        }

        /** Find a built-in profile by its [OcrSessionConfig.mode] value. */
        fun forMode(mode: Int): OcrProfile? = all.firstOrNull { it.mode == mode }
    }
}
