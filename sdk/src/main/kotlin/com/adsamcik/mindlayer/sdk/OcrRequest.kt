package com.adsamcik.mindlayer.sdk

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import kotlin.time.Duration

/**
 * Declarative description of a one-shot OCR call, assembled through [Builder]
 * inside [Mindlayer.ocr].
 *
 * Loads one image and returns the recognized text, optional boxes, and extraction result.
 */
class OcrRequest private constructor() {

    @MindlayerDsl
    class Builder {
        fun profile(profile: OcrProfile) {
            this.profile = profile
        }

        fun schema(schema: JsonSchema) {
            this.schema = schema
        }

        fun languageHints(langs: List<String>) {
            languageHints = langs
        }

        fun image(bitmap: Bitmap) {
            image = ImageInput.Bitmap(bitmap)
        }

        fun image(bytes: ByteArray, mimeType: String) {
            image = ImageInput.Bytes(bytes, mimeType)
        }

        fun image(file: File) {
            image = ImageInput.File(file)
        }

        fun image(uri: Uri, context: Context) {
            image = ImageInput.Uri(uri, context)
        }

        fun extractWithLlm(schema: JsonSchema) {
            extractionSchema = schema
        }

        fun emitBoundingBoxes() {
            emitBoundingBoxes = true
        }

        /**
         * Total client wait budget, including image loading and encoding.
         * [Duration.INFINITE] leaves the wait unbounded. Timeout/cancellation stops
         * waiting promptly; synchronous service-side OCR may finish in the background.
         */
        fun deadline(timeout: Duration) {
            require(timeout > Duration.ZERO) { "deadline must be positive" }
            deadline = timeout
        }

        internal var profile: OcrProfile = OcrProfile.GeneralDocument
        internal var schema: JsonSchema? = null
        internal var languageHints: List<String> = emptyList()
        internal var image: ImageInput? = null
        internal var extractionSchema: JsonSchema? = null
        internal var emitBoundingBoxes: Boolean = false
        internal var deadline: Duration? = null
    }
}

/**
 * Declarative description of a streaming multi-frame OCR session, assembled
 * through [Builder] inside [Mindlayer.ocrSession].
 *
 * Opens a live session for frame intake, streamed OCR events, and finalization.
 */
class OcrSessionRequest private constructor() {

    @MindlayerDsl
    class Builder {
        fun profile(profile: OcrProfile) {
            this.profile = profile
        }

        fun schema(schema: JsonSchema) {
            this.schema = schema
        }

        fun languageHints(langs: List<String>) {
            languageHints = langs
        }

        fun maxFrames(n: Int) {
            maxFrames = n
        }

        fun frameRateLimit(fps: Int) {
            frameRateLimit = fps
        }

        fun extractWithLlm(schema: JsonSchema) {
            extractionSchema = schema
        }

        fun emitBoundingBoxes() {
            emitBoundingBoxes = true
        }

        internal var profile: OcrProfile = OcrProfile.GeneralDocument
        internal var schema: JsonSchema? = null
        internal var languageHints: List<String> = emptyList()
        internal var maxFrames: Int? = null
        internal var frameRateLimit: Int? = null
        internal var extractionSchema: JsonSchema? = null
        internal var emitBoundingBoxes: Boolean = false
    }
}
