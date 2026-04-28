package com.adsamcik.mindlayer.service.logging

import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject

internal fun logExtraJson(builder: JsonObjectBuilder.() -> Unit): String =
    buildJsonObject(builder).toString()

internal fun String.redactedFileName(): String =
    substringAfterLast('/').substringAfterLast('\\')
