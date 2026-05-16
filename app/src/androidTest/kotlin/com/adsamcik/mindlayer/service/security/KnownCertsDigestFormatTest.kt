package com.adsamcik.mindlayer.service.security

import android.content.pm.PackageManager
import android.os.Build
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.S)
@Ignore(
    "TODO(knowncerts-format): the original design cross-signed test and target APKs " +
        "(target with knowncerts-owner, androidTest with knowncerts-requester) so the " +
        "`signature|knownSigner` permission could only be granted via the knownCerts " +
        "(knownSigner) branch. Android's instrumentation framework refuses to start a " +
        "test APK whose signing cert does not match the target APK's, so the cross-sign " +
        "design was unrunnable in CI (`Permission Denial: ... does NOT have a signature " +
        "matching the target`). With test and target sharing the same signer, the " +
        "`signature` branch of `signature|knownSigner` always grants, masking format " +
        "discrimination of the `knownSigner` branch — so these tests cannot prove what " +
        "they claim with the current infrastructure. Reviving them requires either an " +
        "external declarer APK (separately installed and signed with a third identity) " +
        "or a `knownSigner`-only protectionLevel (which Android does not support without " +
        "a base level). See PR #44 history and the hotfix PR that introduced this " +
        "@Ignore for the full reasoning.",
)
class KnownCertsDigestFormatTest {

    private val appContext = ApplicationProvider.getApplicationContext<android.content.Context>()
    private val testContext = InstrumentationRegistry.getInstrumentation().context
    private val pm = appContext.packageManager
    private val testPackageName = testContext.packageName

    @Test
    fun lowercase_hex_64chars_is_accepted() {
        assertPermission(PERMISSION_LOWERCASE_HEX_64, PackageManager.PERMISSION_GRANTED)
    }

    @Test
    fun uppercase_hex_is_rejected() {
        assertPermission(PERMISSION_UPPERCASE, PackageManager.PERMISSION_DENIED)
    }

    @Test
    fun colon_separated_pairs_are_rejected() {
        assertPermission(PERMISSION_COLON_PAIRS, PackageManager.PERMISSION_DENIED)
    }

    @Test
    fun leading_zerox_is_rejected() {
        assertPermission(PERMISSION_LEADING_ZEROX, PackageManager.PERMISSION_DENIED)
    }

    @Test
    fun mismatched_hash_is_rejected() {
        assertPermission(PERMISSION_MISMATCHED, PackageManager.PERMISSION_DENIED)
    }

    @Test
    fun empty_array_is_rejected() {
        assertPermission(PERMISSION_EMPTY, PackageManager.PERMISSION_DENIED)
    }

    @Test
    fun test_apk_signer_matches_the_known_lowercase_hex_digest() {
        val appDigest = signingCertSha256Hex(appContext.packageName)
        val testDigest = signingCertSha256Hex(testPackageName)

        assertEquals(REQUESTER_CERT_SHA256_HEX, testDigest)
        assertNotEquals(
            "The test must not pass through the ordinary signature permission path.",
            appDigest,
            testDigest,
        )
        assertTrue(testDigest.matches(Regex("^[0-9a-f]{64}$")))
    }

    private fun assertPermission(permission: String, expected: Int) {
        assertEquals(expected, pm.checkPermission(permission, testPackageName))
    }

    @Suppress("DEPRECATION")
    private fun signingCertSha256Hex(packageName: String): String {
        val signature = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
            val signingInfo = checkNotNull(info.signingInfo)
            val history = signingInfo.signingCertificateHistory
            when {
                !history.isNullOrEmpty() -> history.last()
                signingInfo.apkContentsSigners?.isNotEmpty() == true -> signingInfo.apkContentsSigners[0]
                else -> error("No signing certificate for $packageName")
            }
        } else {
            val info = pm.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            info.signatures?.singleOrNull() ?: error("Expected exactly one signer for $packageName")
        }
        return sha256Hex(signature.toByteArray())
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return buildString(digest.size * 2) {
            digest.forEach { byte ->
                val value = byte.toInt() and 0xff
                append(HEX[value ushr 4])
                append(HEX[value and 0x0f])
            }
        }
    }

    private companion object {
        private const val REQUESTER_CERT_SHA256_HEX =
            "3bb0a4da57f3230bf5c1d49da62cb320ca960839a93c71dc14c2eef1243f8588"
        private const val PERMISSION_PREFIX =
            "com.adsamcik.mindlayer.test.permission.KNOWN_CERTS_FORMAT_TEST"
        private const val PERMISSION_LOWERCASE_HEX_64 = "${PERMISSION_PREFIX}_LOWERCASE_HEX_64"
        private const val PERMISSION_UPPERCASE = "${PERMISSION_PREFIX}_UPPERCASE"
        private const val PERMISSION_COLON_PAIRS = "${PERMISSION_PREFIX}_COLON_PAIRS"
        private const val PERMISSION_LEADING_ZEROX = "${PERMISSION_PREFIX}_LEADING_ZEROX"
        private const val PERMISSION_MISMATCHED = "${PERMISSION_PREFIX}_MISMATCHED"
        private const val PERMISSION_EMPTY = "${PERMISSION_PREFIX}_EMPTY"
        private val HEX = "0123456789abcdef".toCharArray()
    }
}
