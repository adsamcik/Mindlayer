package com.adsamcik.mindlayer.service.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import io.mockk.every
import io.mockk.mockkObject
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * F-031: re-verify the live signing certificate at approve-tap time so a
 * sig-swap between dashboard render and the user's tap fails closed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AllowlistStoreSigReVerifyAtApproveTest {

    private lateinit var context: Context
    private lateinit var store: AllowlistStore
    private val dirName = "test_reverify_${System.nanoTime()}"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir().deleteRecursively()
        store = AllowlistStore(context, dirName)
    }

    @After
    fun tearDown() {
        dir().deleteRecursively()
        unmockkAll()
    }

    private fun dir(): File = File(context.filesDir, dirName)

    @Test
    fun `approve fails closed when live signer disagrees with expected sig`() {
        store.recordPending("com.example", "AAAA", "Example")
        mockkObject(CallerVerifier)
        every { CallerVerifier.identifyByPackage(any(), "com.example") } returns
            CallerIdentity("com.example", "BBBB", "Example")

        try {
            store.approve(context, "com.example", expectedSigSha256 = "AAAA", displayName = "Example")
            fail("Expected CertificateMismatchException")
        } catch (e: CertificateMismatchException) {
            assertEquals("com.example", e.pkg)
            assertEquals("AAAA", e.expectedSig)
            assertEquals("BBBB", e.liveSig)
        }

        // Entries.json untouched, pending row preserved.
        assertTrue(store.list().isEmpty())
        assertEquals(1, store.listPending().size)
        assertEquals("AAAA", store.listPending().first().signingCertSha256)
    }

    @Test
    fun `approve succeeds when live signer matches expected`() {
        store.recordPending("com.example", "AAAA")
        mockkObject(CallerVerifier)
        every { CallerVerifier.identifyByPackage(any(), "com.example") } returns
            CallerIdentity("com.example", "AAAA", "Example")

        store.approve(context, "com.example", "AAAA", "Example")
        assertTrue(store.isAllowed("com.example", "AAAA"))
        assertEquals(1, store.list().size)
        // The matching pending row is gone.
        assertTrue(store.listPending().none {
            it.packageName == "com.example" && it.signingCertSha256.equals("AAAA", true)
        })
    }

    @Test
    fun `approve removes only the matching sig pending row, not other sig rows for same pkg`() {
        store.recordPending("com.example", "AAAA")
        store.recordPending("com.example", "BBBB")
        assertEquals(2, store.listPending().size)

        mockkObject(CallerVerifier)
        every { CallerVerifier.identifyByPackage(any(), "com.example") } returns
            CallerIdentity("com.example", "AAAA", "Example")

        store.approve(context, "com.example", "AAAA")
        // Pending row with sig BBBB is preserved — sig swap is not silent.
        val remaining = store.listPending()
        assertEquals(1, remaining.size)
        assertEquals("BBBB", remaining.first().signingCertSha256)
    }

    @Test
    fun `approve throws SecurityException when package no longer installed`() {
        store.recordPending("com.gone", "AAAA")
        mockkObject(CallerVerifier)
        every { CallerVerifier.identifyByPackage(any(), "com.gone") } returns null

        try {
            store.approve(context, "com.gone", "AAAA")
            fail("Expected SecurityException")
        } catch (e: CertificateMismatchException) {
            fail("Should throw plain SecurityException, not CertificateMismatchException")
        } catch (_: SecurityException) {
            // Expected.
        }
        assertTrue(store.list().isEmpty())
    }
}
