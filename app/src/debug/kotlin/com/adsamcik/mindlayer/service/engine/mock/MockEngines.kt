package com.adsamcik.mindlayer.service.engine.mock

import android.content.Context
import com.adsamcik.mindlayer.service.BuildConfig
import com.adsamcik.mindlayer.service.logging.MindlayerLog

private const val TAG = "Mindlayer.MockEngines"

/** System property that arms the DEBUG-only CI mock-engines mode. */
const val MOCK_ENGINES_SYSPROP: String = "debug.mindlayer.mock_engines"

/**
 * DEBUG-variant seam for the "CI mock engines" mode.
 *
 * Returns a populated [MockEngineBundle] when this is a debuggable build **and**
 * the `debug.mindlayer.mock_engines` system property is `"1"` (or `"true"`);
 * otherwise `null`. The release-variant override in
 * `app/src/release/.../engine/mock/MockEngines.kt` always returns `null`, and
 * the `Mock*` classes are physically absent from the release classpath — so a
 * release build can never enter mock mode, even via reflection.
 *
 * Arming the mode (on a debuggable build, no models required):
 *
 * ```
 * adb shell setprop debug.mindlayer.mock_engines 1
 * # then restart the service process so MindlayerMlService.onCreate re-reads it
 * adb shell am force-stop com.adsamcik.mindlayer.service.debug
 * ```
 *
 * The property is read **once** at `MindlayerMlService.onCreate` and is inert
 * everywhere else, so a normal debug build with the property unset behaves
 * exactly like production.
 */
internal fun mockEnginesOrNull(@Suppress("UNUSED_PARAMETER") context: Context): MockEngineBundle? {
    if (!BuildConfig.DEBUG) return null
    if (!mockEngineSysPropEnabled()) return null
    MindlayerLog.i(
        TAG,
        "CI mock engines ENABLED via $MOCK_ENGINES_SYSPROP — OCR/embeddings/OCR-LLM " +
            "return synthetic [mock] data; no real models are loaded",
    )
    return MockEngineBundle(
        embeddingBackendFactory = { MockEmbeddingBackend() },
        embeddingDefaultModel = MockEmbeddingBackend.MODEL,
        paddleOcrBackendFactory = { MockPaddleOcrBackend() },
        ocrLlmExtractor = MockOcrLlmExtractor(),
    )
}

/**
 * Read `android.os.SystemProperties.get(key, def)` reflectively. The class is
 * hidden API but readable on debuggable builds; any failure (SecurityException,
 * NoSuchMethod / "not mocked" under a pure-JVM unit runtime, etc.) fails closed
 * to `false`. The catch is intentionally silent — emitting a log here would
 * route through `android.util.Log`, which throws under the mockable android.jar
 * used by `testDebugUnitTest`, and a failed read simply means "mode off".
 */
private fun mockEngineSysPropEnabled(): Boolean = try {
    val clazz = Class.forName("android.os.SystemProperties")
    val getter = clazz.getMethod("get", String::class.java, String::class.java)
    val value = getter.invoke(null, MOCK_ENGINES_SYSPROP, "0") as? String
    value == "1" || value.equals("true", ignoreCase = true)
} catch (t: Throwable) {
    false
}
