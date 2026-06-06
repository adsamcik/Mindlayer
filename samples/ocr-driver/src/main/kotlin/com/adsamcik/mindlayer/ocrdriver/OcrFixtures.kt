package com.adsamcik.mindlayer.ocrdriver

internal object OcrFixtures {
    const val RECEIPT = "receipt.webp"
    const val DOCUMENT = "document.webp"
    const val SCREEN_CAPTURE = "screen_capture.webp"
    const val DASHBOARD = "dashboard-fixture.webp"

    val all: List<String> = listOf(RECEIPT, DOCUMENT, SCREEN_CAPTURE, DASHBOARD)

    fun mimeType(name: String): String = when {
        name.endsWith(".webp", ignoreCase = true) -> "image/webp"
        name.endsWith(".png", ignoreCase = true) -> "image/png"
        name.endsWith(".jpg", ignoreCase = true) ||
            name.endsWith(".jpeg", ignoreCase = true) -> "image/jpeg"
        else -> error("Unsupported OCR fixture type: $name")
    }
}
