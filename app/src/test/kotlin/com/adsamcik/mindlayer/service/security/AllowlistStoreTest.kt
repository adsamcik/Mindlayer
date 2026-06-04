package com.adsamcik.mindlayer.service.security

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TestName
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.json.JSONObject
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class AllowlistStoreTest {

    @get:Rule
    val testName = TestName()

    private lateinit var context: Context
    private lateinit var store: AllowlistStore
    private lateinit var dirName: String

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        dirName = "test_allowlist_${testName.methodName.sanitizedForPath()}"
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
        store.approveDirect("com.example", "abc123", "Example App")
        assertTrue(store.isAllowed("com.example", "abc123"))
        assertEquals(1, store.list().size)
        assertEquals("Example App", store.list().first().displayName)
    }

    @Test
    fun `isAllowed fails on signature mismatch`() {
        store.approveDirect("com.example", "abc123", null)
        assertFalse(store.isAllowed("com.example", "different"))
    }

    @Test
    fun `isAllowed is case-insensitive on signature`() {
        store.approveDirect("com.example", "ABCDEF", null)
        assertTrue(store.isAllowed("com.example", "abcdef"))
    }

    @Test
    fun `revoke removes the entry`() {
        store.approveDirect("com.example", "abc123")
        store.revoke("com.example")
        assertFalse(store.isAllowed("com.example", "abc123"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `approve replaces existing entry for same package`() {
        store.approveDirect("com.example", "old")
        store.approveDirect("com.example", "new")
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
    fun `recordPending appends a new row when signature changes`() {
        // F-031: pending becomes append-only across cert mismatches so a
        // sig-swap cannot silently overwrite the prior pending row before
        // the user has a chance to see it.
        store.recordPending("com.example", "sig1")
        store.recordPending("com.example", "sig2")
        assertEquals(2, store.listPending().size)
        val sigs = store.listPending().map { it.signingCertSha256 }.toSet()
        assertTrue(sigs.contains("sig1"))
        assertTrue(sigs.contains("sig2"))
    }

    @Test
    fun `approve clears any pending entry for the same package`() {
        store.recordPending("com.example", "sig1")
        store.approveDirect("com.example", "sig1")
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
        store.approveDirect("com.example", "sig")
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
        writer.approveDirect("com.example", "sig")
        assertTrue(reader.isAllowed("com.example", "sig"))
    }

    @Test
    fun `approved entries are written with integrity envelope`() {
        store.approveDirect("com.example", "sig")

        val envelope = JSONObject(File(dir(), "entries.json").readText())

        assertTrue(envelope.has("entries"))
        assertTrue(envelope.has("mac"))
    }

    @Test
    fun `tampered signed entries are rejected`() {
        store.approveDirect("com.example", "sig")
        val entriesFile = File(dir(), "entries.json")
        val envelope = JSONObject(entriesFile.readText())
        envelope.getJSONArray("entries")
            .getJSONObject(0)
            .put("sig", "evil")
        entriesFile.writeText(envelope.toString())

        assertFalse(store.isAllowed("com.example", "evil"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `approve recovers store after entries file corruption`() {
        store.approveDirect("com.example", "sig")
        val entriesFile = File(dir(), "entries.json")
        val envelope = JSONObject(entriesFile.readText())
        envelope.getJSONArray("entries")
            .getJSONObject(0)
            .put("sig", "evil")
        entriesFile.writeText(envelope.toString())
        assertTrue(store.list().isEmpty())

        store.approveDirect("com.example", "recovered", "Recovered App")

        assertTrue(store.isAllowed("com.example", "recovered"))
        assertEquals("Recovered App", store.list().single().displayName)
    }

    @Test
    fun `corrupted entries file does not hide valid pending approvals`() {
        store.approveDirect("com.example", "sig")
        store.recordPending("com.pending", "pending-sig", "Pending App")
        File(dir(), "entries.json").writeText("{not-json")

        assertFalse(store.isAllowed("com.example", "sig"))
        assertEquals(1, store.listPending().size)
        assertEquals("com.pending", store.listPending().single().packageName)
    }

    @Test
    fun `pending approvals reject tampered signed file`() {
        store.recordPending("com.example", "sig", "Example")
        val pendingFile = File(dir(), "pending.json")
        val envelope = JSONObject(pendingFile.readText())
        envelope.getJSONArray("pending")
            .getJSONObject(0)
            .put("sig", "evil")
        pendingFile.writeText(envelope.toString())

        assertTrue(store.listPending().isEmpty())
    }

    @Test
    fun `recordPending recovers store after pending file corruption`() {
        store.recordPending("com.example", "sig", "Example")
        val pendingFile = File(dir(), "pending.json")
        val envelope = JSONObject(pendingFile.readText())
        envelope.getJSONArray("pending")
            .getJSONObject(0)
            .put("sig", "evil")
        pendingFile.writeText(envelope.toString())
        assertTrue(store.listPending().isEmpty())

        store.recordPending("com.example", "recovered", "Recovered Pending")

        assertEquals(1, store.listPending().size)
        assertEquals("recovered", store.listPending().single().signingCertSha256)
        assertEquals("Recovered Pending", store.listPending().single().displayName)
    }

    @Test
    fun `corrupted pending file does not invalidate approved entries`() {
        store.approveDirect("com.example", "sig")
        store.recordPending("com.pending", "pending-sig")
        File(dir(), "pending.json").writeText("{not-json")

        assertTrue(store.isAllowed("com.example", "sig"))
        assertTrue(store.listPending().isEmpty())
    }

    @Test
    fun `malformed entries file is replaced by next approval`() {
        File(dir(), "entries.json").writeText("{not-json")

        store.approveDirect("com.example", "sig", "Recovered")

        assertTrue(store.isAllowed("com.example", "sig"))
        val envelope = JSONObject(File(dir(), "entries.json").readText())
        assertTrue(envelope.has("entries"))
        assertTrue(envelope.has("mac"))
    }

    @Test
    fun `corrupted hmac key invalidates existing approvals`() {
        store.approveDirect("com.example", "sig")
        File(dir(), "allowlist.hmac").writeText("not-base64")

        assertFalse(store.isAllowed("com.example", "sig"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `approve recovers by rotating hmac key after key corruption`() {
        store.approveDirect("com.example", "sig")
        File(dir(), "allowlist.hmac").writeText("not-base64")
        assertFalse(store.isAllowed("com.example", "sig"))

        store.approveDirect("com.example", "new-sig")

        assertTrue(store.isAllowed("com.example", "new-sig"))
        assertEquals(1, store.list().size)
    }

    @Test
    fun `signed entries survive json key reordering`() {
        store.approveDirect("com.example", "sig", "Example")
        val entriesFile = File(dir(), "entries.json")
        val envelope = JSONObject(entriesFile.readText())
        val entry = envelope.getJSONArray("entries").getJSONObject(0)
        // Rewrite with keys reordered but the same envelope version + MAC.
        // The HMAC pre-image is built from a canonical (key-sorted) form
        // by `canonicalPayload`, so a key-reordered JSON must still verify.
        // Version is the current SIGNED_FILE_VERSION (3 since the v0.10
        // consent-architecture bump for the denial permanent+scope HMAC
        // fix — entries shape is unchanged but the global file-format
        // version applies).
        entriesFile.writeText(
            """{"version":3,"entries":[{"displayName":"${entry.getString("displayName")}","grantedAtMs":${entry.getLong("grantedAtMs")},"sig":"${entry.getString("sig")}","pkg":"${entry.getString("pkg")}"}],"mac":"${envelope.getString("mac")}"}""",
        )

        assertTrue(store.isAllowed("com.example", "sig"))
    }

    @Test
    fun `legacy arrays are quarantined on load`() {
        val legacyDirName = "${dirName}_legacy_entries"
        val legacyDir = File(context.filesDir, legacyDirName).also { it.mkdirs() }
        File(legacyDir, "entries.json").writeText(
            """[{"pkg":"com.example","sig":"sig","grantedAtMs":1}]""",
        )

        val migrated = AllowlistStore(context, legacyDirName)

        // Legacy unsigned entries are quarantined (not re-signed); caller must re-approve.
        assertFalse(migrated.isAllowed("com.example", "sig"))
        assertTrue(migrated.list().isEmpty())
        assertFalse("entries.json must have been renamed", File(legacyDir, "entries.json").exists())
        legacyDir.deleteRecursively()
    }

    @Test
    fun `legacy pending arrays are quarantined on load`() {
        val legacyDirName = "${dirName}_legacy_pending"
        val legacyDir = File(context.filesDir, legacyDirName).also { it.mkdirs() }
        File(legacyDir, "pending.json").writeText(
            """[{"pkg":"com.example","sig":"sig","firstRequestedAtMs":1,"displayName":"Example"}]""",
        )

        val migrated = AllowlistStore(context, legacyDirName)

        // Legacy unsigned pending entries are quarantined; caller must re-connect to appear in pending.
        assertTrue(migrated.listPending().isEmpty())
        assertFalse("pending.json must have been renamed", File(legacyDir, "pending.json").exists())
        legacyDir.deleteRecursively()
    }

    @Test
    fun `legacy array injection after migration is rejected`() {
        store.approveDirect("com.example", "sig")
        File(dir(), "entries.json").writeText(
            """[{"pkg":"com.evil","sig":"sig","grantedAtMs":1}]""",
        )

        assertFalse(store.isAllowed("com.evil", "sig"))
        assertTrue(store.list().isEmpty())
    }

    @Test
    fun `revoke writes a permanent denial tombstone`() {
        // H-T1: explicit user revoke must persist forever, not just for the
        // 7-day denyPending cooldown. Otherwise a future seedIfEmpty (after
        // an app-data clear or DB-corruption re-init) could silently re-admit
        // the revoked package once the cooldown had expired.
        store.approveDirect("com.example", "sig123")
        store.revoke("com.example")

        val deniedFile = File(dir(), "denied.json")
        val envelope = JSONObject(deniedFile.readText())
        val arr = envelope.getJSONArray("denied")
        assertEquals(1, arr.length())
        val row = arr.getJSONObject(0)
        assertEquals("com.example", row.getString("pkg"))
        assertTrue("revoke must mark the row permanent", row.optBoolean("permanent", false))
        assertEquals(Long.MAX_VALUE, row.getLong("expiresAtMs"))
    }

    @Test
    fun `permanent revoke survives subsequent writeDenied prune`() {
        // H-T1: a later writeDenied (triggered by an unrelated revoke or
        // denyPending) must not prune permanent tombstones. denyPending
        // entries still get their 7-day TTL.
        store.approveDirect("com.foo", "sigFoo")
        store.revoke("com.foo")

        // Trigger another writeDenied via an unrelated revoke. This is the
        // call that, pre-fix, would have pruned a time-expired 7-day denial.
        store.approveDirect("com.bar", "sigBar")
        store.revoke("com.bar")

        val envelope = JSONObject(File(dir(), "denied.json").readText())
        val arr = envelope.getJSONArray("denied")
        val pkgs = (0 until arr.length()).map { arr.getJSONObject(it).getString("pkg") }.toSet()
        assertTrue("permanent revoke for com.foo must survive", pkgs.contains("com.foo"))
        assertTrue(pkgs.contains("com.bar"))
    }

    @Test
    fun `seedIfEmpty refuses a permanently revoked package even after entries wiped`() {
        // H-T1: the canonical attack: app-data partial clear (or corruption
        // re-init) wipes entries.json but the permanent denial in denied.json
        // remains. seedIfEmpty must still skip the revoked package.
        store.approveDirect("com.example", "sigOriginal")
        store.revoke("com.example")

        // Simulate the entries file being wiped while denied.json survives —
        // e.g. partial backup restore, DB corruption recovery path.
        File(dir(), "entries.json").delete()

        // Reopen the store to drop in-memory state and force a fresh read.
        val reopened = AllowlistStore(context, dirName)
        reopened.seedIfEmpty(
            listOf(
                AllowlistEntry(
                    packageName = "com.example",
                    signingCertSha256 = "sigOriginal",
                    grantedAtMs = 0L,
                    displayName = "Example",
                ),
            ),
        )

        assertFalse(reopened.isAllowed("com.example", "sigOriginal"))
        assertTrue(reopened.list().isEmpty())
    }

    @Test
    fun `denyPending denial still uses the 7-day TTL, not permanent`() {
        // Behavior-change boundary: only revoke is sticky. denyPending keeps
        // the original 7-day TTL semantics so a user who taps "deny" by
        // mistake on a *pending* row isn't permanently locked out.
        store.recordPending("com.example", "sig1")
        store.denyPending("com.example")

        val envelope = JSONObject(File(dir(), "denied.json").readText())
        val arr = envelope.getJSONArray("denied")
        assertEquals(1, arr.length())
        val row = arr.getJSONObject(0)
        assertFalse("denyPending must not mark the row permanent", row.optBoolean("permanent", false))
        assertTrue(row.getLong("expiresAtMs") < Long.MAX_VALUE)
    }

    private fun String.sanitizedForPath(): String =
        replace(Regex("[^A-Za-z0-9_.-]"), "_")
}
