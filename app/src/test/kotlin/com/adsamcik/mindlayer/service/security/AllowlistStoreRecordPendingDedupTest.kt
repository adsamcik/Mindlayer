package com.adsamcik.mindlayer.service.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * F-054: when a `(pkg, sig)` is already approved in `entries.json`, calling
 * `recordPending` for the same `(pkg, sig)` must skip writing a pending
 * row entirely. This protects against a race where an external caller's
 * authz gate observes a stale "not yet approved" view (between the disk
 * read and the file lock acquisition) and tries to log a pending row for
 * an already-approved combination.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AllowlistStoreRecordPendingDedupTest {

    private lateinit var context: Context
    private lateinit var store: AllowlistStore
    private val dirName = "dedup_test_${System.nanoTime()}"

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, dirName).deleteRecursively()
        store = AllowlistStore(context, dirName)
    }

    @After
    fun tearDown() {
        File(context.filesDir, dirName).deleteRecursively()
    }

    @Test
    fun `recordPending is a no-op when pkg+sig is already approved`() {
        store.approveDirect("com.example", "abc123", "Example App")
        // Force-clear the in-memory dedup so the call is not short-circuited
        // by the TTL cache. Simulate a fresh process state.
        // Reflectively poking the dedup map is brittle; instead use a brand
        // new store instance pointing at the same directory.
        val freshStore = AllowlistStore(context, dirName)

        freshStore.recordPending("com.example", "abc123", "Example App")

        assertTrue(
            "approved entry intact",
            freshStore.list().any { it.packageName == "com.example" }
        )
        assertEquals(
            "no pending row written for already-approved (pkg, sig)",
            0,
            freshStore.listPending().size,
        )
    }

    @Test
    fun `recordPending still writes when sig differs from approved sig`() {
        store.approveDirect("com.example", "abc123", "Example App")
        val freshStore = AllowlistStore(context, dirName)

        // A different sig for the same pkg is the cert-rotation case — must
        // write a pending row so the user can decide.
        freshStore.recordPending("com.example", "deadbeef", "Example App")

        assertEquals(1, freshStore.listPending().size)
        val pending = freshStore.listPending().first()
        assertEquals("deadbeef", pending.signingCertSha256)
        // F-032 banner field set.
        assertEquals("abc123", pending.previousSigSha256)
    }

    @Test
    fun `recordPending writes for un-approved pkg`() {
        store.recordPending("com.unknown", "newsig", "New App")
        assertEquals(1, store.listPending().size)
    }
}
