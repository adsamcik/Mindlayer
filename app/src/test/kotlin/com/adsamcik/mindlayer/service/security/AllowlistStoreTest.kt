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
        // H-T1: explicit user revoke must persist forever. Otherwise a
        // future consent flow could silently re-admit the revoked package
        // after a prior denial expired.
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
        // time-bounded consent denial) must not prune permanent tombstones.
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
    fun `denialFor returns a DENY_24H row for the same pkg and sig only`() {
        store.deny("com.example", "sigA", com.adsamcik.mindlayer.ConsentDecision.KIND_DENY_24H)
        // Same pkg+sig is denied.
        val hit = store.denialFor("com.example", "sigA")
        assertTrue("DENY_24H must match same pkg+sig", hit != null)
        assertFalse("DENY_24H is not permanent", hit!!.permanent)
        assertEquals(DenialScope.CERT_PAIR, hit.scope)
        // A different signer under the same package is NOT covered by a
        // CERT_PAIR denial (cert rotation re-asks, which is intended for 24h).
        assertTrue(store.denialFor("com.example", "sigB") == null)
        // A different package is unaffected.
        assertTrue(store.denialFor("com.other", "sigA") == null)
    }

    @Test
    fun `denialFor matches any cert for a DENY_PERMANENT package-wide block`() {
        store.deny("com.example", "sigA", com.adsamcik.mindlayer.ConsentDecision.KIND_DENY_PERMANENT)
        // Same package, ANY signer is blocked — cert rotation cannot bypass.
        val hitA = store.denialFor("com.example", "sigA")
        val hitB = store.denialFor("com.example", "totally-different-sig")
        assertTrue("permanent block must match the original signer", hitA != null)
        assertTrue("permanent block must match a rotated signer", hitB != null)
        assertTrue("permanent block is permanent", hitA!!.permanent)
        assertEquals(DenialScope.PACKAGE_WIDE, hitA.scope)
        // A different package is unaffected.
        assertTrue(store.denialFor("com.other", "sigA") == null)
    }

    @Test
    fun `denialFor agrees with isDenied across scopes`() {
        // denialFor must be the row-returning twin of the boolean isDenied:
        // wherever isDenied is true, denialFor returns a row, and vice-versa.
        store.deny("com.cert", "sigA", com.adsamcik.mindlayer.ConsentDecision.KIND_DENY_24H)
        store.deny("com.wide", "sigX", com.adsamcik.mindlayer.ConsentDecision.KIND_DENY_PERMANENT)

        val probes = listOf(
            "com.cert" to "sigA",   // CERT_PAIR hit
            "com.cert" to "sigB",   // CERT_PAIR miss (different sig)
            "com.wide" to "sigX",   // PACKAGE_WIDE hit
            "com.wide" to "sigROT", // PACKAGE_WIDE hit (rotated sig)
            "com.none" to "sigA",   // no denial
        )
        for ((pkg, sig) in probes) {
            assertEquals(
                "denialFor/isDenied must agree for $pkg/$sig",
                store.isDenied(pkg, sig),
                store.denialFor(pkg, sig) != null,
            )
        }
    }

    @Test
    fun `approveFromConsent refuses and preserves an in-effect denial`() {
        // The atomic grant path must NOT clear a denial that is in effect:
        // it returns the blocking row and writes no approval. (The success
        // branch needs a real installed package for the live cert re-verify,
        // so it is covered by the instrumented / ServiceBinder-level tests;
        // here we pin the security-critical refusal branch, which returns
        // before any package lookup.)
        store.deny("com.example", "sigA", com.adsamcik.mindlayer.ConsentDecision.KIND_DENY_24H)
        val blocking = store.approveFromConsent(context, "com.example", "sigA")
        assertTrue("a 24h-denied app must be refused", blocking != null)
        assertEquals("com.example", blocking!!.packageName)
        assertFalse("no approval may be written", store.isAllowed("com.example", "sigA"))
        assertTrue("the denial must survive the refused grant", store.isDenied("com.example", "sigA"))
    }

    @Test
    fun `approveFromConsent refusal honours a package-wide permanent block`() {
        store.deny("com.example", "sigA", com.adsamcik.mindlayer.ConsentDecision.KIND_DENY_PERMANENT)
        // A rotated signer is still blocked by the PACKAGE_WIDE permanent row.
        val blocking = store.approveFromConsent(context, "com.example", "rotated-sig")
        assertTrue("permanent block must refuse any cert", blocking != null)
        assertTrue(blocking!!.permanent)
        assertFalse(store.isAllowed("com.example", "rotated-sig"))
    }

    @Test
    fun `tampering the permanent flag on a denied entry is detected by the v3 MAC (S-9)`() {
        store.approveDirect("com.evil", "sigEvil")
        store.revoke("com.evil") // sticky (permanent) denial, v3-signed
        assertTrue("revoked app must be denied", store.isDenied("com.evil", "sigEvil"))

        // Attacker with filesDir write access tries to SILENTLY downgrade the
        // sticky revoke into an expirable one by removing the `permanent`
        // field while keeping the original MAC. Pre-fix (v2) `permanent` was
        // NOT in the HMAC pre-image, so this tamper went undetected and the
        // denial would expire after the TTL, re-allowing the revoked app.
        val deniedFile = File(dir(), "denied.json")
        val env = JSONObject(deniedFile.readText())
        env.getJSONArray("denied").getJSONObject(0).remove("permanent")
        deniedFile.writeText(env.toString())

        // Reopen to force a fresh disk read with MAC verification. v3 binds
        // `permanent` into the MAC, so the tamper breaks verification and the
        // forged entry is rejected — the downgrade is detected, not honoured.
        val reopened = AllowlistStore(context, dirName)
        assertFalse(
            "tampered denied.json must fail MAC verification (downgrade not honoured)",
            reopened.isDenied("com.evil", "sigEvil"),
        )
    }

    private fun String.sanitizedForPath(): String =
        replace(Regex("[^A-Za-z0-9_.-]"), "_")
}
