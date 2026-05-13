package com.adsamcik.mindlayer.service.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.adsamcik.mindlayer.service.logging.LogRepository
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel

internal class DebugAllowlistSeeder(
    private val context: Context,
    private val allowlistStore: AllowlistStore,
    private val callerVerifier: CallerVerifier,
    @Suppress("unused") private val logRepository: LogRepository,
) {
    fun seedSameCertInstalledPackages() {
        val ownPkg = context.packageName
        val ownSig = callerVerifier.identifyByPackage(context, ownPkg)?.signingCertSha256 ?: run {
            MindlayerLog.w(TAG, "DebugAllowlistSeeder: own signing cert unresolvable; skipping")
            return
        }

        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            PackageManager.GET_SIGNING_CERTIFICATES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_SIGNATURES
        }
        val installed = context.packageManager.getInstalledPackages(flags)

        var seeded = 0
        for (pi in installed) {
            val pkg = pi.packageName
            if (pkg == ownPkg) continue
            val identity = callerVerifier.identifyByPackage(context, pkg) ?: continue
            val sig = identity.signingCertSha256
            if (sig != ownSig) continue
            try {
                val before = allowlistStore.isAllowed(pkg, sig)
                allowlistStore.seedVerified(
                    listOf(
                        AllowlistEntry(
                            packageName = pkg,
                            signingCertSha256 = sig,
                            grantedAtMs = 0L,
                            displayName = pi.applicationInfo?.loadLabel(context.packageManager)?.toString(),
                        ),
                    ),
                    requireEmpty = false,
                    action = "debug_same_cert_auto_seed",
                )
                if (!before && allowlistStore.isAllowed(pkg, sig)) {
                    seeded += 1
                }
            } catch (e: Exception) {
                MindlayerLog.w(TAG, "DebugAllowlistSeeder: approve failed for $pkg: ${e.safeLabel()}", throwable = null)
            }
        }
        MindlayerLog.i(TAG, "DebugAllowlistSeeder: seeded $seeded same-cert packages")
    }

    private companion object {
        private const val TAG = "Mindlayer.DebugSeeder"
    }
}