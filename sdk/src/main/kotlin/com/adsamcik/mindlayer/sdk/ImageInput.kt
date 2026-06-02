package com.adsamcik.mindlayer.sdk

/**
 * A unit of image input that can be handed to OCR without committing to a
 * single in-memory representation.
 *
 * The nested data classes intentionally shadow the platform `Bitmap`, `File`,
 * and `Uri` names (qualified at the field type) so call sites read naturally:
 * `ImageInput.Bitmap(bmp)`, `ImageInput.File(f)`, etc.
 */
sealed interface ImageInput {
    /** An in-memory bitmap. */
    data class Bitmap(val bitmap: android.graphics.Bitmap) : ImageInput

    /** Raw encoded bytes with an explicit MIME type (e.g. `image/jpeg`). */
    data class Bytes(val bytes: ByteArray, val mimeType: String) : ImageInput {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Bytes) return false
            return mimeType == other.mimeType && bytes.contentEquals(other.bytes)
        }

        override fun hashCode(): Int = 31 * bytes.contentHashCode() + mimeType.hashCode()
    }

    /** A file on disk. */
    data class File(val file: java.io.File) : ImageInput

    /** A content [android.net.Uri] resolved through [context]. */
    data class Uri(val uri: android.net.Uri, val context: android.content.Context) : ImageInput
}
