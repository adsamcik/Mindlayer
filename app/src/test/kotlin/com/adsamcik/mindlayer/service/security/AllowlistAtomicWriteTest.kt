package com.adsamcik.mindlayer.service.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * Pins SECURITY_REVIEW F-025 — atomic write must be crash-safe and never
 * leave the allowlist file in a state that parses as empty (silent
 * revocation of every approval).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AllowlistAtomicWriteTest {

    private lateinit var context: Context
    private lateinit var store: AllowlistStore
    private lateinit var dir: File
    private lateinit var entriesFile: File

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        store = AllowlistStore(context, dirName = "atomic_write_test")
        dir = File(context.filesDir, "atomic_write_test")
        entriesFile = File(dir, "entries.json")
    }

    @After
    fun tearDown() {
        dir.deleteRecursively()
    }

    @Test
    fun `approve writes a valid JSON array (no half-write artefact)`() {
        store.approveDirect("com.example.a", "ABC123", "Example A")

        // The temp sidecar must not survive after a successful write.
        val tmp = File(dir, "entries.json.tmp")
        assertFalse(
            "atomicWrite must clean up its .tmp sibling on success: ${tmp.exists()}",
            tmp.exists(),
        )
        assertTrue("entries.json must exist", entriesFile.exists())

        val text = entriesFile.readText()
        assertTrue("entries.json must be a JSON array", text.startsWith("[") && text.endsWith("]"))
        val arr = JSONArray(text)
        assertEquals(1, arr.length())
        val o = arr.getJSONObject(0)
        assertEquals("com.example.a", o.getString("pkg"))
        assertEquals("ABC123", o.getString("sig"))
    }

    @Test
    fun `revoke does not corrupt the file (atomic move)`() {
        store.approveDirect("com.a", "AAAA")
        store.approveDirect("com.b", "BBBB")
        store.revoke("com.a")

        val arr = JSONArray(entriesFile.readText())
        assertEquals(1, arr.length())
        assertEquals("com.b", arr.getJSONObject(0).getString("pkg"))
    }

    @Test
    fun `recovery from corrupt entries returns empty (existing behavior preserved)`() {
        // Write garbage directly — readEntries treats parse failure as
        // "no allowlist". This is a deliberate fail-safe: a corrupted
        // file should not be silently treated as approval of all callers.
        entriesFile.parentFile!!.mkdirs()
        entriesFile.writeText("{not-valid-json")
        assertEquals(0, store.list().size)
    }
}
