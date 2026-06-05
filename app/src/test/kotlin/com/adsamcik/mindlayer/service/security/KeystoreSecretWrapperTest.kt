package com.adsamcik.mindlayer.service.security

import android.util.Log
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Pure-JVM coverage for [KeystoreSecretWrapper] (security review S-8).
 *
 * The real AndroidKeyStore wrap/unwrap round-trip is exercised on hardware
 * via the `androidTest` source set (same split as [DbKeyProvider], whose
 * Keystore path also can't run under Robolectric). Here we pin the
 * host-independent contract: marker detection and the graceful fallback
 * when the Keystore provider is absent (the JVM/Robolectric case AND a
 * broken/rooted device).
 */
class KeystoreSecretWrapperTest {

    @Before
    fun setUp() {
        mockkStatic(Log::class)
        every { Log.d(any(), any()) } returns 0
        every { Log.i(any(), any()) } returns 0
        every { Log.w(any(), any<String>()) } returns 0
        every { Log.w(any(), any<String>(), any()) } returns 0
        every { Log.e(any(), any()) } returns 0
    }

    @After
    fun tearDown() {
        unmockkAll()
    }

    @Test
    fun `isWrapped detects the mlks1 marker and rejects plaintext`() {
        val wrapper = KeystoreSecretWrapper("test.alias.1")
        assertTrue(wrapper.isWrapped("${KeystoreSecretWrapper.WRAP_MARKER}:abc:def"))
        assertFalse(wrapper.isWrapped("dGhpcyBpcyBwbGFpbnRleHQ=")) // legacy base64
        assertFalse(wrapper.isWrapped(""))
    }

    @Test
    fun `wrap returns null when the keystore provider is unavailable (fallback path)`() {
        // Under a plain JVM/Robolectric host there is no AndroidKeyStore
        // provider, so isAvailable is false and wrap() must yield null so
        // the caller persists the legacy plaintext format instead of
        // bricking the authorization system.
        val wrapper = KeystoreSecretWrapper("test.alias.2")
        assertFalse("no AndroidKeyStore on JVM host", wrapper.isAvailable)
        assertNull(wrapper.wrap(ByteArray(32) { it.toByte() }))
    }

    @Test
    fun `unwrap returns null for non-wrapped input`() {
        val wrapper = KeystoreSecretWrapper("test.alias.3")
        assertNull(wrapper.unwrap("not-a-wrapped-blob"))
    }
}
