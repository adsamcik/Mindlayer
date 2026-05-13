package com.adsamcik.mindlayer.service.security

import android.content.Context
import com.adsamcik.mindlayer.service.logging.LogRepository

internal fun debugSeedIfApplicable(
    context: Context,
    allowlistStore: AllowlistStore,
    callerVerifier: CallerVerifier,
    logRepository: LogRepository,
) {
    DebugAllowlistSeeder(context, allowlistStore, callerVerifier, logRepository).seedSameCertInstalledPackages()
}
