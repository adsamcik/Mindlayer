package com.adsamcik.mindlayer

import android.app.PendingIntent
import android.content.Intent
import android.os.Parcel
import android.os.Parcelable
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * `Parcel.marshall` / `unmarshall` round-trip + wire-stability tests for the
 * consent-flow parcelables ([ConsentIdentity], [ConsentDecision]) and the
 * non-marshallable [ConsentChallenge] (constants + privacy-safe toString).
 *
 * These three types are the wire contract of the trust boundary: the SDK uses
 * them to drive `requestConsentChallenge` -> `lookupChallenge` ->
 * `completeConsent`. Per `docs/architecture/AIDL_STABILITY.md` they are wire-frozen once
 * shipped — a failure here means the consent wire format drifted.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ConsentParcelableTest {

    private inline fun <reified T : Parcelable> roundtrip(value: T): T {
        val parcel = Parcel.obtain()
        try {
            value.writeToParcel(parcel, 0)
            val bytes = parcel.marshall()
            parcel.recycle()
            val second = Parcel.obtain()
            try {
                second.unmarshall(bytes, 0, bytes.size)
                second.setDataPosition(0)
                @Suppress("UNCHECKED_CAST")
                val creator = T::class.java.getField("CREATOR").get(null) as Parcelable.Creator<T>
                return creator.createFromParcel(second)
            } finally {
                second.recycle()
            }
        } finally {
            if (parcel.dataPosition() >= 0) parcel.recycle()
        }
    }

    // ── ConsentIdentity ──────────────────────────────────────────────────

    @Test
    fun `ConsentIdentity roundtrips with all fields populated`() {
        val identity = ConsentIdentity(
            schemaVersion = ConsentIdentity.CURRENT_SCHEMA_VERSION,
            packageName = "com.example.client",
            displayName = "Example Client",
            signingCertSha256 = "a".repeat(64),
            installSource = "com.android.vending",
            previousSigSha256 = "b".repeat(64),
            expiresAtMs = 1_700_000_300_000L,
            extensionsJson = """{"k":"v"}""",
        )
        assertEquals(identity, roundtrip(identity))
    }

    @Test
    fun `ConsentIdentity roundtrips with nullable fields absent`() {
        val identity = ConsentIdentity(
            packageName = "com.example.client",
            displayName = null,
            signingCertSha256 = "c".repeat(64),
            installSource = null,
            previousSigSha256 = null,
            expiresAtMs = 0L,
        )
        val rt = roundtrip(identity)
        assertEquals(identity, rt)
        assertEquals(1, rt.schemaVersion)
        assertEquals(null, rt.displayName)
        assertEquals(null, rt.installSource)
        assertEquals(null, rt.previousSigSha256)
        assertEquals(null, rt.extensionsJson)
    }

    @Test
    fun `ConsentIdentity exposes a CREATOR field`() {
        assertNotNull(ConsentIdentity::class.java.getField("CREATOR").get(null))
    }

    // ── ConsentDecision ──────────────────────────────────────────────────

    @Test
    fun `ConsentDecision roundtrips for every kind`() {
        for (kind in listOf(
            ConsentDecision.KIND_GRANT,
            ConsentDecision.KIND_DENY_ONCE,
            ConsentDecision.KIND_DENY_24H,
            ConsentDecision.KIND_DENY_PERMANENT,
        )) {
            val decision = ConsentDecision(kind = kind)
            assertEquals(decision, roundtrip(decision))
        }
    }

    @Test
    fun `ConsentDecision kind constants are wire-stable and append-only`() {
        assertEquals(1, ConsentDecision.KIND_GRANT)
        assertEquals(2, ConsentDecision.KIND_DENY_ONCE)
        assertEquals(3, ConsentDecision.KIND_DENY_24H)
        assertEquals(4, ConsentDecision.KIND_DENY_PERMANENT)
        assertEquals(1, ConsentDecision.CURRENT_SCHEMA_VERSION)
    }

    @Test
    fun `nameOfKind maps every known kind and falls back for unknown`() {
        assertEquals("GRANT", ConsentDecision.nameOfKind(ConsentDecision.KIND_GRANT))
        assertEquals("DENY_ONCE", ConsentDecision.nameOfKind(ConsentDecision.KIND_DENY_ONCE))
        assertEquals("DENY_24H", ConsentDecision.nameOfKind(ConsentDecision.KIND_DENY_24H))
        assertEquals("DENY_PERMANENT", ConsentDecision.nameOfKind(ConsentDecision.KIND_DENY_PERMANENT))
        assertEquals("UNKNOWN(99)", ConsentDecision.nameOfKind(99))
    }

    @Test
    fun `ConsentDecision toString renders the symbolic kind name`() {
        assertTrue(ConsentDecision(kind = ConsentDecision.KIND_GRANT).toString().contains("GRANT"))
    }

    // ── ConsentChallenge (PendingIntent is not marshallable) ─────────────

    private fun newPendingIntent(): PendingIntent = PendingIntent.getActivity(
        ApplicationProvider.getApplicationContext(),
        0,
        Intent(),
        PendingIntent.FLAG_IMMUTABLE,
    )

    @Test
    fun `ConsentChallenge constants are wire-stable`() {
        assertEquals(1, ConsentChallenge.CURRENT_SCHEMA_VERSION)
        assertEquals(5 * 60 * 1000L, ConsentChallenge.TTL_MS_DEFAULT)
    }

    @Test
    fun `ConsentChallenge toString truncates the nonce and never leaks its tail`() {
        val challenge = ConsentChallenge(
            nonce = "abcdefgh_SECRET_TAIL_DO_NOT_LOG",
            consentIntent = newPendingIntent(),
            expiresAtMs = 1_700_000_300_000L,
        )
        val rendered = challenge.toString()
        assertTrue(rendered.contains("abcdefgh"))
        assertFalse("nonce tail must never appear in toString", rendered.contains("SECRET_TAIL"))
    }

    @Test
    fun `ConsentChallenge exposes a CREATOR field`() {
        assertNotNull(ConsentChallenge::class.java.getField("CREATOR").get(null))
    }
}
