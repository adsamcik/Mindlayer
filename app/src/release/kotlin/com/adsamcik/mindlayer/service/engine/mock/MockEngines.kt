package com.adsamcik.mindlayer.service.engine.mock

import android.content.Context

/**
 * Release-variant no-op seam for the "CI mock engines" mode.
 *
 * The DEBUG override in `app/src/debug/.../engine/mock/MockEngines.kt` reads the
 * `debug.mindlayer.mock_engines` system property and constructs `Mock*`
 * backends — all of which live only in the debug source set. In release the
 * mode can never be armed, so this always returns `null` and
 * `MindlayerMlService.onCreate` wires the real LiteRT / LiteRT-LM / PaddleOCR
 * engines unconditionally.
 */
@Suppress("UNUSED_PARAMETER")
internal fun mockEnginesOrNull(context: Context): MockEngineBundle? = null
