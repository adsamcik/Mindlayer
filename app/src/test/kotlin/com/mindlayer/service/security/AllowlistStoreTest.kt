package com.mindlayer.service.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AllowlistStoreTest {

    private lateinit var context: Context
    private lateinit var store: AllowlistStore
    private val dirName = "test_allowlist_${System.nanoTime()}"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dir().deleteRecursively()
        store = AllowlistStore(context, dirName)
    }

    @After
    fun tearDown() {
        dir().deleteRecursively()
    }

    private fun dir(): File = File(context.filesDir, dirName)

    @Test
    fun `empty store rejects everything`() {
        assertFalse(store.isAllowed("com.example", "sig"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `approve then isAllowed succeeds`() {
        store.approve("com.example", "abc123", "Example App")
        assertTrue(store.isAllowed("com.example", "abc123"))
        assertEquals(1, store.list().size)
        assertEquals("Example App", store.list().first().displayName)
    }

    @Test
    fun `isAllowed fails on signature mismatch`() {
        store.approve("com.example", "abc123", null)
        assertFalse(store.isAllowed("com.example", "different"))
    }

    @Test
    fun `isAllowed is case-insensitive on signature`() {
        store.approve("com.example", "ABCDEF", null)
        assertTrue(store.isAllowed("com.example", "abcdef"))
    }

    @Test
    fun `revoke removes the entry`() {
        store.approve("com.example", "abc123")
        store.revoke("com.example")
        assertFalse(store.isAllowed("com.example", "abc123"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `approve replaces existing entry for same package`() {
        store.approve("com.example", "old")
        store.approve("com.example", "new")
        assertEquals(1, store.list().size)
        assertTrue(store.isAllowed("com.example", "new"))
        assertFalse(store.isAllowed("com.example", "old"))
    }

    @Test
    fun `recordPending dedupes by package + signature`() {
        store.recordPending("com.example", "sig1", "A")
        store.recordPending("com.example", "sig1", "A")
        assertEquals(1, store.listPending().size)
    }

    @Test
    fun `recordPending replaces entry if signature changes`() {
        store.recordPending("com.example", "sig1")
        store.recordPending("com.example", "sig2")
        assertEquals(1, store.listPending().size)
        assertEquals("sig2", store.listPending().first().signingCertSha256)
    }

    @Test
    fun `approve clears any pending entry for the same package`() {
        store.recordPending("com.example", "sig1")
        store.approve("com.example", "sig1")
        assertTrue(store.listPending().isEmpty())
        assertTrue(store.isAllowed("com.example", "sig1"))
    }

    @Test
    fun `denyPending removes pending entry`() {
        store.recordPending("com.example", "sig1")
        store.denyPending("com.example")
        assertTrue(store.listPending().isEmpty())
    }

    @Test
    fun `entries persist across instances`() {
        store.approve("com.example", "sig")
        val reopened = AllowlistStore(context, dirName)
        assertTrue(reopened.isAllowed("com.example", "sig"))
    }

    @Test
    fun `second instance sees writes from first without cache staleness`() {
        // This is the cross-process-parity invariant. If two processes each
        // hold their own AllowlistStore, approving in one must be visible to
        // the other's isAllowed check on the very next call — no refresh().
        val writer = AllowlistStore(context, dirName)
        val reader = AllowlistStore(context, dirName)
        writer.approve("com.example", "sig")
        assertTrue(reader.isAllowed("com.example", "sig"))
    }
}
