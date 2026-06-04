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

        // Production format (post-b15b656 H7 hardening): the persisted
        // shape is `{"version":<N>,"entries":[...],"mac":"<hex>"}` — an
        // HMAC-integrity envelope around the entry array. The current
        // SIGNED_FILE_VERSION is 3 (bumped from 2 in the v0.10
        // consent-architecture for the denial-side permanent+scope HMAC
        // fix — entries.json structure is unchanged but it carries the
        // global file-format version). Earlier versions of this test
        // asserted the file was a raw JSON array, which silently broke
        // when integrity was added. We now assert both the envelope
        // shape AND the inner entries layout.
        val text = entriesFile.readText()
        val envelope = JSONObject(text)
        assertEquals("envelope version must be 3", 3, envelope.getInt("version"))
        val mac = envelope.getString("mac")
        assertTrue("mac must be a non-empty hex string", mac.isNotEmpty() && mac.matches(Regex("^[0-9a-fA-F]+$")))
        val arr = envelope.getJSONArray("entries")
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

        // See `approve writes a valid JSON array` for the envelope shape.
        val envelope = JSONObject(entriesFile.readText())
        assertEquals(3, envelope.getInt("version"))
        assertNotNull("mac field must survive revoke", envelope.optString("mac", null))
        val arr = envelope.getJSONArray("entries")
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
