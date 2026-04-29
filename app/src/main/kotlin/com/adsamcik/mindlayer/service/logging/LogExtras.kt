package com.adsamcik.mindlayer.service.logging

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

internal fun logExtraJson(builder: JsonObjectBuilder.() -> Unit): String =
    buildJsonObject(builder).toString()

internal fun String.redactedFileName(): String =
    substringAfterLast('/').substringAfterLast('\\')

/**
 * Sanitise a raw error string so that prompt fragments can never appear in
 * diagnostic exports or persisted logs.
 *
 * Keeps only characters that are safe in a class/exception identifier
 * (`[A-Za-z0-9._:-]`) and caps the result at 64 characters.
 *
 * Example: `"OutOfMemoryError -> IOException"` → `"OutOfMemoryError-IOException"`.
 */
internal fun sanitizeErrorClass(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    val safe = raw.take(64).filter { it.isLetterOrDigit() || it in "._:-" }
    return safe.ifEmpty { null }
}
