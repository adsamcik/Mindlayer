package com.adsamcik.mindlayer.service.security

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import java.security.MessageDigest

data class CallerIdentity(
    val packageName: String,
    val signingCertSha256: String,
    val displayName: String? = null,
)

/**
 * Pure utility for resolving a calling UID to a [CallerIdentity]. Shared-UID
 * callers are rejected (return `null`) because their identity is ambiguous.
 *
 * On API 28+ uses `GET_SIGNING_CERTIFICATES` + `signingInfo`; below that falls
 * back to the deprecated `GET_SIGNATURES` API. SHA-256 is computed over the
 * DER-encoded certificate bytes.
 */
object CallerVerifier {

    /** F-030: maximum length for a sanitised application label. */
    const val MAX_LABEL_LEN = 64

    /**
     * F-030: characters that hide attacker-controlled labels under
     * homoglyphs / direction overrides / private-use codepoints. Stripped
     * after NFKC normalisation.
     */
    private val UNSAFE_LABEL_CHARS = Regex("[\\p{Cf}\\p{Cc}\\p{Co}\\p{Cn}]")

    /**
     * F-030: NFKC-normalise, strip Cf/Cc/Co/Cn (RTL overrides, ZWNJ,
     * private-use, unassigned) and cap to [MAX_LABEL_LEN]. Returns null for
     * blank / empty / wholly-stripped labels so callers fall back to the
     * package name as primary identity.
     */
    fun sanitizeLabel(raw: String?): String? {
        if (raw.isNullOrBlank()) return null
        val nfkc = java.text.Normalizer.normalize(raw, java.text.Normalizer.Form.NFKC)
        val stripped = UNSAFE_LABEL_CHARS.replace(nfkc, "").trim()
        if (stripped.isEmpty()) return null
        return if (stripped.length > MAX_LABEL_LEN) stripped.substring(0, MAX_LABEL_LEN) else stripped
    }

    fun identifyCaller(context: Context, callingUid: Int): CallerIdentity? {
        val pm = context.packageManager
        val packages = pm.getPackagesForUid(callingUid) ?: return null
        if (packages.size != 1) {
            // Shared UID — ambiguous, reject.
            return null
        }
        val pkg = packages[0]

        val sha = try {
            certificateSha256(pm, pkg) ?: return null
        } catch (_: PackageManager.NameNotFoundException) {
            return null
        } catch (_: Throwable) {
            return null
        }

        val displayName = try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            sanitizeLabel(pm.getApplicationLabel(appInfo).toString())
        } catch (_: Throwable) {
            null
        }
        return CallerIdentity(pkg, sha, displayName)
    }

    /**
     * F-031: resolve a [CallerIdentity] from a package name only. Used at
     * approve-tap time to re-verify the live signing certificate against the
     * sig pinned in the displayed [PendingApproval] row.
     *
     * Returns `null` if the package is not installed, has no signing
     * certificate, or fails sig resolution. Does NOT consult the calling
     * UID (the dashboard's UID is not the target package's UID).
     */
    fun identifyByPackage(context: Context, pkg: String): CallerIdentity? {
        val pm = context.packageManager
        return try {
            val sha = certificateSha256(pm, pkg) ?: return null
            val label = sanitizeLabel(rawApplicationLabel(pm, pkg))
            CallerIdentity(pkg, sha, label)
        } catch (_: PackageManager.NameNotFoundException) {
            null
        } catch (_: Throwable) {
            null
        }
    }

    private fun rawApplicationLabel(pm: PackageManager, pkg: String): String? = try {
        val appInfo = pm.getApplicationInfo(pkg, 0)
        pm.getApplicationLabel(appInfo).toString()
    } catch (_: Throwable) {
        null
    }

    @Suppress("DEPRECATION")
    private fun certificateSha256(pm: PackageManager, pkg: String): String? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = info.signingInfo ?: return null
            if (signingInfo.hasMultipleSigners()) {
                // Multi-signer: order of apkContentsSigners is not guaranteed. Hash each
                // certificate, sort the hex digests, then hash the concatenation so the
                // fingerprint is deterministic across platform quirks.
                val signatures = signingInfo.apkContentsSigners ?: return null
                if (signatures.isEmpty()) return null
                val perSigner = signatures.map { sha256Hex(it.toByteArray()) }.sorted()
                return sha256Hex(perSigner.joinToString(":").toByteArray(Charsets.US_ASCII))
            }
            // Single signer (possibly rotated). signingCertificateHistory is oldest-first,
            // with the current signer last. Pin to the CURRENT signer — otherwise an app
            // that rotated away from a leaked cert would still match on the old one.
            val history = signingInfo.signingCertificateHistory
            val current = when {
                !history.isNullOrEmpty() -> history.last()
                signingInfo.apkContentsSigners?.isNotEmpty() == true -> signingInfo.apkContentsSigners[0]
                else -> return null
            }
            return sha256Hex(current.toByteArray())
        } else {
            val info = pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
            val signatures = info.signatures ?: return null
            if (signatures.isEmpty()) return null
            if (signatures.size == 1) return sha256Hex(signatures[0].toByteArray())
            val perSigner = signatures.map { sha256Hex(it.toByteArray()) }.sorted()
            return sha256Hex(perSigner.joinToString(":").toByteArray(Charsets.US_ASCII))
        }
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val sb = StringBuilder(digest.size * 2)
        for (b in digest) {
            val v = b.toInt() and 0xff
            sb.append(HEX[v ushr 4])
            sb.append(HEX[v and 0x0f])
        }
        return sb.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

}
