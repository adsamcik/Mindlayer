package com.mindlayer.service.security

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.content.pm.SigningInfo
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

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CallerVerifierTest {

    private fun makeContext(pm: PackageManager): Context {
        val ctx = mockk<Context>(relaxed = true)
        every { ctx.packageManager } returns pm
        return ctx
    }

    private fun expectedSha(bytes: ByteArray): String {
        val d = MessageDigest.getInstance("SHA-256").digest(bytes)
        return d.joinToString("") { "%02x".format(it) }
    }

    @Test
    fun `null when uid has no packages`() {
        val pm = mockk<PackageManager>(relaxed = true)
        every { pm.getPackagesForUid(1000) } returns null
        val id = CallerVerifier.identifyCaller(makeContext(pm), 1000)
        assertNull(id)
    }

    @Test
    fun `null when uid is shared between packages`() {
        val pm = mockk<PackageManager>(relaxed = true)
        every { pm.getPackagesForUid(1000) } returns arrayOf("com.a", "com.b")
        val id = CallerVerifier.identifyCaller(makeContext(pm), 1000)
        assertNull(id)
    }

    @Test
    fun `returns CallerIdentity with sha256 of first signature on API 28+`() {
        val pm = mockk<PackageManager>(relaxed = true)
        val sigBytes = byteArrayOf(1, 2, 3, 4, 5)
        val signature = mockk<Signature>()
        every { signature.toByteArray() } returns sigBytes

        val signingInfo = mockk<SigningInfo>()
        every { signingInfo.hasMultipleSigners() } returns false
        every { signingInfo.signingCertificateHistory } returns arrayOf(signature)
        every { signingInfo.apkContentsSigners } returns arrayOf(signature)

        val pkgInfo = PackageInfo().apply {
            this.signingInfo = signingInfo
        }
        every { pm.getPackagesForUid(1000) } returns arrayOf("com.example")
        every {
            pm.getPackageInfo("com.example", PackageManager.GET_SIGNING_CERTIFICATES)
        } returns pkgInfo
        val appInfo = ApplicationInfo().apply { packageName = "com.example" }
        every { pm.getApplicationInfo("com.example", 0) } returns appInfo
        every { pm.getApplicationLabel(appInfo) } returns "Example"

        val id = CallerVerifier.identifyCaller(makeContext(pm), 1000)
        assertNotNull(id)
        assertEquals("com.example", id!!.packageName)
        assertEquals(expectedSha(sigBytes), id.signingCertSha256)
    }

    @Test
    fun `null when package info missing`() {
        val pm = mockk<PackageManager>(relaxed = true)
        every { pm.getPackagesForUid(1000) } returns arrayOf("com.example")
        every {
            pm.getPackageInfo("com.example", PackageManager.GET_SIGNING_CERTIFICATES)
        } throws PackageManager.NameNotFoundException()
        val id = CallerVerifier.identifyCaller(makeContext(pm), 1000)
        assertNull(id)
    }
}
