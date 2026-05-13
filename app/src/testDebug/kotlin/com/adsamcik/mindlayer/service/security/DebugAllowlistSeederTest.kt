package com.adsamcik.mindlayer.service.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import com.adsamcik.mindlayer.service.logging.LogRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.junit.Rule
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DebugAllowlistSeederTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var packageManager: PackageManager
    private lateinit var store: AllowlistStore
    private lateinit var logRepository: LogRepository

    @Before
    fun setUp() {
        mockkObject(CallerVerifier)
        packageManager = mockk(relaxed = true)
        context = mockk(relaxed = true) {
            every { packageName } returns OWN_PKG
            every { packageManager } returns this@DebugAllowlistSeederTest.packageManager
            every { applicationContext } returns this
            every { filesDir } returns temp.root
        }
        logRepository = mockk(relaxed = true)
        store = AllowlistStore(context, "debug_seed_test")
    }

    @After
    fun tearDown() {
        unmockkObject(CallerVerifier)
    }

    @Test
    fun `same-cert installed package is seeded`() {
        installed(OWN_PKG, "com.example.same")
        identity(OWN_PKG, OWN_SIG)
        identity("com.example.same", OWN_SIG)

        seed()

        assertTrue(store.isAllowed("com.example.same", OWN_SIG))
    }

    @Test
    fun `different-cert installed package is skipped`() {
        installed(OWN_PKG, "com.example.other")
        identity(OWN_PKG, OWN_SIG)
        identity("com.example.other", OTHER_SIG)

        seed()

        assertFalse(store.isAllowed("com.example.other", OTHER_SIG))
    }

    @Test
    fun `self package is always skipped`() {
        installed(OWN_PKG)
        identity(OWN_PKG, OWN_SIG)

        seed()

        assertFalse(store.isAllowed(OWN_PKG, OWN_SIG))
    }

    @Test
    fun `revoked same-cert package is not re-admitted`() {
        installed(OWN_PKG, "com.example.revoked")
        identity(OWN_PKG, OWN_SIG)
        identity("com.example.revoked", OWN_SIG)
        store.approveDirect("com.example.revoked", OWN_SIG)
        store.revoke("com.example.revoked")

        seed()

        assertFalse(store.isAllowed("com.example.revoked", OWN_SIG))
    }

    private fun seed() {
        DebugAllowlistSeeder(context, store, CallerVerifier, logRepository).seedSameCertInstalledPackages()
    }

    private fun installed(vararg packages: String) {
        every { packageManager.getInstalledPackages(PackageManager.GET_SIGNING_CERTIFICATES) } returns
            packages.map { PackageInfo().apply { packageName = it } }
    }

    private fun identity(pkg: String, sig: String) {
        every { CallerVerifier.identifyByPackage(context, pkg) } returns CallerIdentity(pkg, sig, pkg)
    }

    private companion object {
        private const val OWN_PKG = "com.adsamcik.mindlayer.service.debug"
        private const val OWN_SIG = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        private const val OTHER_SIG = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
    }
}