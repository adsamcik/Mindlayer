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
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * F-032: pending rows preserve a `previousSigSha256` when a previously
 * approved package re-requests approval under a different signing
 * certificate. The dashboard uses this to render a cert-rotation banner +
 * "I understand" gate.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AllowlistStoreCertRotationTest {

    private lateinit var context: Context
    private lateinit var store: AllowlistStore
    private val dirName = "test_rotation_${System.nanoTime()}"

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
    fun `recordPending populates previousSigSha256 when approved entry has different sig`() {
        // Approve under original sig.
        store.approveDirect("com.example", "AAAA", "Example")

        // New connection attempt under rotated key.
        store.recordPending("com.example", "BBBB", "Example")

        val pending = store.listPending().single()
        assertEquals("BBBB", pending.signingCertSha256)
        assertEquals("AAAA", pending.previousSigSha256)
    }

    @Test
    fun `recordPending leaves previousSigSha256 null when no prior approval exists`() {
        store.recordPending("com.example", "AAAA")
        assertNull(store.listPending().single().previousSigSha256)
    }

    @Test
    fun `recordPending leaves previousSigSha256 null when same sig as approved`() {
        store.approveDirect("com.example", "AAAA")
        // Same sig as approved — recordPending should short-circuit (already
        // approved) but if it did record, prevSig would be null.
        store.recordPending("com.example", "AAAA")
        assertTrue(store.listPending().isEmpty())
    }

    @Test
    fun `JSON round-trip preserves previousSigSha256 across instances`() {
        store.approveDirect("com.example", "AAAA")
        store.recordPending("com.example", "BBBB")

        val reopened = AllowlistStore(context, dirName)
        val pending = reopened.listPending().single()
        assertEquals("BBBB", pending.signingCertSha256)
        assertEquals("AAAA", pending.previousSigSha256)
    }

    @Test
    fun `approve via context overload after rotation clears the rotated pending row`() {
        store.approveDirect("com.example", "AAAA")
        store.recordPending("com.example", "BBBB")
        assertNotNull(store.listPending().single().previousSigSha256)

        mockkObject(CallerVerifier)
        every { CallerVerifier.identifyByPackage(any(), "com.example") } returns
            CallerIdentity("com.example", "BBBB", "Example")

        store.approve(context, "com.example", "BBBB")
        // entries.json now has BBBB as the approved sig.
        assertTrue(store.isAllowed("com.example", "BBBB"))
        assertEquals(1, store.list().size)
        // pending.json no longer contains the BBBB row for com.example.
        assertTrue(store.listPending().isEmpty())
    }
}
