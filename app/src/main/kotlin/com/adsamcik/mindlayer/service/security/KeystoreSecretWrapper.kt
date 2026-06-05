package com.adsamcik.mindlayer.service.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import com.adsamcik.mindlayer.service.logging.safeLabel
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Wraps/unwraps small secrets (e.g. the allowlist HMAC key) with an
 * AndroidKeyStore-resident AES-256-GCM key so the secret never sits in
 * cleartext on disk (security review S-8).
 *
 * ## Threat model & graceful fallback
 *
 * The plaintext-on-disk weakness this addresses is only reachable by an
 * attacker that can already write `filesDir` — which on a sandboxed device
 * means OS root, a scope the project's threat model otherwise lists as out
 * of scope. The wrap is therefore *defense in depth*: on a healthy device
 * the AndroidKeyStore is always available and the key is wrapped; if the
 * Keystore is genuinely unavailable (an already-broken/rooted environment,
 * or a JVM/Robolectric host with no `AndroidKeyStore` provider) [isAvailable]
 * returns false and callers fall back to the legacy plaintext format so the
 * authorization system never bricks itself over a Keystore hiccup.
 *
 * Each instance is keyed by a Keystore [alias]; different stores (e.g. one
 * per `AllowlistStore` directory) should use distinct aliases.
 */
internal class KeystoreSecretWrapper(private val alias: String) {

    /** Text marker prefixing a wrapped blob: `mlks1:<b64 iv>:<b64 ct>`. */
    private val prefix = "$WRAP_MARKER:"

    /**
     * True if this host can actually use the AndroidKeyStore. Cached after
     * the first probe (provider availability does not change at runtime).
     */
    val isAvailable: Boolean by lazy { probeAvailable() }

    /** True if [encoded] is a wrapped blob produced by [wrap]. */
    fun isWrapped(encoded: String): Boolean = encoded.startsWith(prefix)

    /**
     * Encrypt [secret] with the Keystore key (creating it on first use) and
     * return a `mlks1:<b64 iv>:<b64 ct>` string. Returns `null` if the
     * Keystore is unavailable or any crypto step fails — the caller then
     * persists the legacy plaintext format.
     */
    fun wrap(secret: ByteArray): String? {
        if (!isAvailable) return null
        return try {
            val key = loadOrCreateKey()
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val ct = cipher.doFinal(secret)
            prefix + b64(iv) + ":" + b64(ct)
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Keystore wrap failed; falling back to plaintext: ${t.safeLabel()}")
            null
        }
    }

    /**
     * Decrypt a `mlks1:<b64 iv>:<b64 ct>` blob produced by [wrap]. Returns
     * `null` if the input is not a wrapped blob, the Keystore key is missing
     * (e.g. user cleared credentials), or decryption fails — the caller
     * treats `null` as "key unrecoverable" and regenerates, which simply
     * forces affected callers to be re-approved.
     */
    fun unwrap(encoded: String): ByteArray? {
        if (!encoded.startsWith(prefix)) return null
        if (!isAvailable) return null
        return try {
            val body = encoded.substring(prefix.length)
            val sep = body.indexOf(':')
            if (sep <= 0) return null
            val iv = unb64(body.substring(0, sep))
            val ct = unb64(body.substring(sep + 1))
            val key = loadKey() ?: return null
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
            cipher.doFinal(ct)
        } catch (t: Throwable) {
            MindlayerLog.w(TAG, "Keystore unwrap failed: ${t.safeLabel()}")
            null
        }
    }

    private fun probeAvailable(): Boolean = try {
        KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        true
    } catch (_: Throwable) {
        false
    }

    private fun loadKey(): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val entry = ks.getEntry(alias, null) as? KeyStore.SecretKeyEntry ?: return null
        return entry.secretKey
    }

    private fun loadOrCreateKey(): SecretKey {
        loadKey()?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(
                alias,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .setRandomizedEncryptionRequired(true)
                .build(),
        )
        return gen.generateKey()
    }

    private fun b64(b: ByteArray): String = java.util.Base64.getEncoder().encodeToString(b)
    private fun unb64(s: String): ByteArray = java.util.Base64.getDecoder().decode(s)

    companion object {
        private const val TAG = "KeystoreSecretWrapper"
        private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        private const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
        private const val GCM_TAG_BITS = 128

        /** Versioned marker so legacy plaintext keys are distinguishable. */
        const val WRAP_MARKER = "mlks1"
    }
}
