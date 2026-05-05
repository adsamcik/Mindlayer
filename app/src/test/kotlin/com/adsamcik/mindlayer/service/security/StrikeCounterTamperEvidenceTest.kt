package com.adsamcik.mindlayer.service.security

import android.content.Context
import android.content.SharedPreferences
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pins SECURITY_REVIEW F-049 — the unwrap-failure strike counter must be
 * tamper-evident and must be cleared on a successful unwrap.
 *
 * Robolectric exposes a real `SharedPreferences` impl but does NOT provide
 * `AndroidKeyStore`, so the HMAC-key path falls back to "treat as missing"
 * (which sets the counter to 0). We use that gracefully-degraded path to
 * verify the public contract:
 *
 *  1. A fresh prefs file reports zero strikes.
 *  2. After we manually plant `unwrap_fail_count` without an HMAC, the
 *     verifier MUST reject the planted counter and remove the keys.
 *  3. A successful unwrap (driven by the test) clears any prior strike.
 *
 * We test against the production `mindlayer_db_key` prefs file directly
 * because the strike helpers are private; the public contract is observed
 * via prefs state after `DbKeyProvider.get()` runs. (On Robolectric the
 * AndroidKeyStore is unavailable, so `DbKeyProvider.get()` will throw on
 * key generation — the tests below stay below that line.)
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@org.junit.Ignore(
    "Architectural drift surfaced by post-merge rereview: the " +
        "SharedPreferences-based strike counter (unwrap_fail_count + " +
        "unwrap_fail_mac under prefs file 'mindlayer_db_key') was " +
        "replaced during the H7 security-hardening pass with a " +
        "file-quarantine model — a corrupted wrapped-key blob is " +
        "renamed `<file>.tampered-<ts>` and a fresh key is generated " +
        "(see DbKeyProvider.kt:155). The reflective hooks this test " +
        "drives (`DbKeyProvider.verifiedFailCount(SharedPreferences)`) " +
        "no longer exist. Equivalent coverage of the new design lives " +
        "in DbKeyProviderTest's quarantine path; restoring this exact " +
        "test would require resurrecting the prefs-based mechanism, " +
        "which is a *worse* design (HMAC-on-prefs has the same key " +
        "bootstrap problem the AEAD-on-blob approach already solves). " +
        "Tracking deletion as a follow-up — keeping the file in place " +
        "for now to preserve git blame for security review."
)
class StrikeCounterTamperEvidenceTest {

    private lateinit var context: Context
    private lateinit var prefs: SharedPreferences

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        prefs = context.getSharedPreferences("mindlayer_db_key", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
    }

    @After
    fun tearDown() {
        prefs.edit().clear().commit()
    }

    @Test
    fun `planted counter without HMAC is rejected and removed`() {
        // Pre-set count = 2 with NO HMAC — a planted blob attack.
        prefs.edit()
            .putInt("unwrap_fail_count", 2)
            .commit()
        assertEquals(2, prefs.getInt("unwrap_fail_count", 0))
        assertNull(prefs.getString("unwrap_fail_mac", null))

        // Reflectively call the package-private verifier. The verifier must
        // observe the missing MAC and zero out the counter as a side effect.
        val cls = Class.forName("com.adsamcik.mindlayer.service.security.DbKeyProvider")
        val method = cls.getDeclaredMethod("verifiedFailCount", SharedPreferences::class.java)
        method.isAccessible = true
        val instance = cls.getField("INSTANCE").get(null)
        val result = method.invoke(instance, prefs) as Int

        assertEquals("planted counter must be ignored", 0, result)
    }

    @Test
    fun `verifier returns 0 for fresh prefs`() {
        val cls = Class.forName("com.adsamcik.mindlayer.service.security.DbKeyProvider")
        val method = cls.getDeclaredMethod("verifiedFailCount", SharedPreferences::class.java)
        method.isAccessible = true
        val instance = cls.getField("INSTANCE").get(null)
        val result = method.invoke(instance, prefs) as Int
        assertEquals(0, result)
    }
}
