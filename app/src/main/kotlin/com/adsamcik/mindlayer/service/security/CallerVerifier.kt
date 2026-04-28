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
            sanitizeDisplayName(pm.getApplicationLabel(appInfo).toString())
        } catch (_: Throwable) {
            null
        }

        return CallerIdentity(pkg, sha, displayName)
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

    private fun sanitizeDisplayName(label: String): String? {
        val sanitized = buildString(label.length) {
            label.forEach { ch ->
                if (!Character.isISOControl(ch) && ch !in BIDI_CONTROLS) append(ch)
            }
        }.trim()
        return sanitized.takeIf { it.isNotEmpty() }?.take(MAX_DISPLAY_NAME_CHARS)
    }

    private val HEX = "0123456789abcdef".toCharArray()
    private const val MAX_DISPLAY_NAME_CHARS = 64
    private val BIDI_CONTROLS = setOf(
        '\u061C',
        '\u200E',
        '\u200F',
        '\u202A',
        '\u202B',
        '\u202C',
        '\u202D',
        '\u202E',
        '\u2066',
        '\u2067',
        '\u2068',
        '\u2069',
    )
}
