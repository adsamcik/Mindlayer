package com.adsamcik.mindlayer.service.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.MessageDigest

/**
 * F-078: targeted Robolectric coverage for [CallerVerifier]'s legacy
 * `GET_SIGNATURES` fallback (API < 28).
 *
 * The post-28 `GET_SIGNING_CERTIFICATES` path is exercised by
 * [CallerVerifierTest] at `@Config(sdk = [33])`. minSdk is 26, so we
 * must also prove the deprecated `PackageInfo.signatures` path produces
 * the *same* SHA-256 multi-signer fingerprint — otherwise an Android 8
 * device could compute a different hash for the same cert and silently
 * miss-match the allowlist.
 *
 * Determinism contract: for a single signer the digest is
 * `sha256(certBytes)`; for N signers it is
 * `sha256(sorted(sha256Hex(cert_i)).joinToString(":").asciiBytes)`.
 * Both code paths must agree.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [26])
class CallerVerifierApi26Test {

    private fun makeContext(pm: PackageManager): Context {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.packageManager } returns pm
        return ctx
    }

    private fun sha256Hex(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    @Suppress("DEPRECATION")
    private fun mockGetSignaturesPath(
        pm: PackageManager,
        pkg: String,
        sigArray: Array<Signature>,
    ) {
        val pkgInfo = PackageInfo().apply {
            packageName = pkg
            signatures = sigArray
        }
        every { pm.getPackagesForUid(1000) } returns arrayOf(pkg)
        every { pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES) } returns pkgInfo
        val appInfo = ApplicationInfo().apply { packageName = pkg }
        every { pm.getApplicationInfo(pkg, 0) } returns appInfo
        every { pm.getApplicationLabel(appInfo) } returns "Example"
    }

    @Test
    fun `API 26 single signer uses GET_SIGNATURES fallback`() {
        val pm = mockk<PackageManager>(relaxed = true)
        val sigBytes = byteArrayOf(0x30, 0x42, 0x01, 0x02, 0x03, 0x04)
        val signature = mockk<Signature>()
        every { signature.toByteArray() } returns sigBytes

        mockGetSignaturesPath(pm, "com.example.api26", arrayOf(signature))

        val id = CallerVerifier.identifyCaller(makeContext(pm), 1000)

        assertNotNull("API 26 path must resolve identity from GET_SIGNATURES", id)
        assertEquals("com.example.api26", id!!.packageName)
        assertEquals(
            "Single-signer digest must be sha256(certBytes)",
            sha256Hex(sigBytes),
            id.signingCertSha256,
        )
    }

    @Test
    fun `API 26 GET_SIGNATURES path returns null when signatures array is empty`() {
        val pm = mockk<PackageManager>(relaxed = true)
        mockGetSignaturesPath(pm, "com.empty", emptyArray())

        val id = CallerVerifier.identifyCaller(makeContext(pm), 1000)
        assertNull("Empty signatures array must reject the caller", id)
    }

    @Test
    fun `API 26 GET_SIGNATURES path returns null when NameNotFoundException`() {
        val pm = mockk<PackageManager>(relaxed = true)
        every { pm.getPackagesForUid(1000) } returns arrayOf("com.missing")
        every {
            pm.getPackageInfo("com.missing", PackageManager.GET_SIGNATURES)
        } throws PackageManager.NameNotFoundException()

        val id = CallerVerifier.identifyCaller(makeContext(pm), 1000)
        assertNull(id)
    }

    /**
     * Multi-signer determinism: per-signer hashes are sorted, joined
     * with `:`, and re-hashed. Order of input array must NOT affect the
     * outcome — this protects against PackageManager returning signers
     * in arbitrary order across boots/devices.
     */
    @Test
    fun `API 26 multi-signer digest is order-independent on GET_SIGNATURES path`() {
        val pm = mockk<PackageManager>(relaxed = true)
        val sigBytesA = byteArrayOf(0x10, 0x11, 0x12)
        val sigBytesB = byteArrayOf(0x20, 0x21, 0x22)

        val sigA = mockk<Signature>().also { every { it.toByteArray() } returns sigBytesA }
        val sigB = mockk<Signature>().also { every { it.toByteArray() } returns sigBytesB }

        // Pass 1: order [A, B]
        mockGetSignaturesPath(pm, "com.multi.ab", arrayOf(sigA, sigB))
        val idAB = CallerVerifier.identifyCaller(makeContext(pm), 1000)

        // Pass 2: order [B, A]
        val pm2 = mockk<PackageManager>(relaxed = true)
        mockGetSignaturesPath(pm2, "com.multi.ab", arrayOf(sigB, sigA))
        val idBA = CallerVerifier.identifyCaller(makeContext(pm2), 1000)

        assertNotNull(idAB)
        assertNotNull(idBA)
        assertEquals(
            "Multi-signer digest MUST be order-independent",
            idAB!!.signingCertSha256,
            idBA!!.signingCertSha256,
        )

        // And it must equal sha256(sorted(sha256Hex(each)).joinToString(":").bytes)
        val expected = sha256Hex(
            listOf(sha256Hex(sigBytesA), sha256Hex(sigBytesB))
                .sorted()
                .joinToString(":")
                .toByteArray(Charsets.US_ASCII),
        )
        assertEquals(expected, idAB.signingCertSha256)
    }

    /**
     * Cross-API determinism proof. Constructs the same single signer on
     * the API 26 fallback path and confirms the digest equals the
     * algorithmically-derived expected value — which is exactly what
     * [CallerVerifierTest.`returns CallerIdentity with sha256 of first signature on API 28+`]
     * also asserts on API 33. Since both paths reduce to
     * `sha256(certBytes)` for a single signer, equal inputs produce
     * equal digests; this test pins the contract from the API 26 side.
     */
    @Test
    fun `API 26 single signer digest matches the API 28+ formula`() {
        val pm = mockk<PackageManager>(relaxed = true)
        val sigBytes = byteArrayOf(1, 2, 3, 4, 5)
        val signature = mockk<Signature>()
        every { signature.toByteArray() } returns sigBytes

        mockGetSignaturesPath(pm, "com.example", arrayOf(signature))

        val id = CallerVerifier.identifyCaller(makeContext(pm), 1000)

        // Same expected digest the post-28 test asserts.
        assertEquals(sha256Hex(sigBytes), id!!.signingCertSha256)
    }
}
