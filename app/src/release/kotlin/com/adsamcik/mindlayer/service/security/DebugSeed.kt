package com.adsamcik.mindlayer.service.security

import android.content.Context
import com.adsamcik.mindlayer.service.logging.LogRepository

/**
 * Release-variant no-op. The debug-variant override in
 * `app/src/debug/kotlin/.../DebugSeed.kt` seeds same-cert installed packages
 * via [DebugAllowlistSeeder] for the dev loop.
 */
internal fun debugSeedIfApplicable(
    context: Context,
    allowlistStore: AllowlistStore,
    callerVerifier: CallerVerifier,
    logRepository: LogRepository,
) {
    // Intentionally empty in release builds.
}
