package com.adsamcik.mindlayer.service.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provides a 32-byte passphrase for the SQLCipher-encrypted log Room DB.
 *
 * The passphrase is a random 32-byte blob wrapped (AES/GCM) with a Keystore-resident AES key.
 * Only the wrapped ciphertext + IV is persisted in SharedPreferences; the raw passphrase never
 * touches disk in cleartext form.
 *
 * Fail-closed: if the Keystore is unavailable, this throws [IllegalStateException]. The service
 * must refuse to start rather than fall back to a plaintext DB.
 *
 * Cross-process safety: [get] serialises init across all processes in this app via an exclusive
 * [java.nio.channels.FileLock] on a sentinel file in `filesDir`. This prevents the main process
 * and `:ml` service from racing through the "no wrapped key yet" path and persisting divergent
 * passphrases (which would brick the DB).
 */
internal object DbKeyProvider {

    private const val TAG = "DbKeyProvider"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "mindlayer.db.key.app"
    /**
     * F-049: separate Keystore alias holding the HMAC key that signs the
     * unwrap-failure counter. Living under a different alias means an
     * attacker who plants a corrupted wrapped blob in `SharedPreferences`
     * cannot also pre-set the failure counter to `MAX_UNWRAP_FAILS - 1`
     * to coerce the next legitimate unwrap-failure into an immediate
     * destructive `forceReset`.
     */
    private const val KEY_ALIAS_HMAC = "mindlayer.db.key.app.hmac"
    private const val PREF_FILE = "mindlayer_db_key"
    private const val PREF_WRAPPED = "wrapped_key"
    private const val PREF_IV = "wrapped_iv"
    /** F-049: persisted unwrap-failure strike counter. Tamper-evident via HMAC. */
    private const val PREF_FAIL_COUNT = "unwrap_fail_count"
    /** F-049: HMAC-SHA256 over `unwrap_fail_count` value, base-64 encoded. */
    private const val PREF_FAIL_MAC = "unwrap_fail_mac"
    private const val LOCK_FILE = "mindlayer_db_key.lock"
    private const val PASSPHRASE_LEN = 32
    private const val GCM_TAG_BITS = 128
    private const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    /**
     * F-049: number of consecutive verified unwrap failures we will tolerate
     * before destructively wiping the DB. Three strikes is enough to make a
     * one-shot planted-blob attack obviously expensive (the attacker has to
     * plant three different corrupted blobs across three service starts) but
     * still bounded — a genuinely corrupted disk recovers in `N` restarts.
     */
    private const val MAX_UNWRAP_FAILS = 3

    private val lock = Any()

    /**
     * Returns the passphrase for the DB named [databaseName]. If a regeneration is forced
     * (Keystore invalidated / prefs corrupted), the existing DB file is **deleted** before
     * a new key is returned — otherwise Room would try to open the old ciphertext with a
     * new key and crash.
     *
     * Callers **must not** cache the returned byte array. SQLCipher owns it after
     * `SupportOpenHelperFactory` is constructed; the caller should zero its local reference
     * as soon as the database is built.
     */
    fun get(context: Context, databaseName: String): ByteArray {
        val appContext = context.applicationContext
        synchronized(lock) {
            return withCrossProcessLock(appContext) {
                loadOrCreate(appContext, databaseName)
            }
        }
    }

    private inline fun <T> withCrossProcessLock(context: Context, block: () -> T): T {
        val lockFile = java.io.File(context.filesDir, LOCK_FILE)
        // Ensure parent exists (filesDir always does, but be defensive).
        lockFile.parentFile?.mkdirs()
        java.io.RandomAccessFile(lockFile, "rw").use { raf ->
            raf.channel.use { channel ->
                val fileLock = channel.lock()
                try {
                    return block()
                } finally {
                    try { fileLock.release() } catch (_: Throwable) { /* best effort */ }
                }
            }
        }
    }

    private fun loadOrCreate(context: Context, databaseName: String): ByteArray {
        val prefs = context.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
        val wrappedB64 = prefs.getString(PREF_WRAPPED, null)
        val ivB64 = prefs.getString(PREF_IV, null)

        if (wrappedB64 != null && ivB64 != null) {
            try {
                val key = loadKeystoreKey()
                if (key == null) {
                    // F-026: a wrapped blob exists in prefs but the Keystore
                    // entry is gone (uninstall/restore, factory reset of the
                    // Keystore alone, or aggressive cleaner). The blob is
                    // unrecoverable — don't fail-closed; recover the same way
                    // we do for GeneralSecurityException: wipe the orphaned
                    // ciphertext DB + prefs, regenerate, and persist a fresh
                    // wrapped passphrase.
                    MindlayerLog.w(TAG, "Keystore entry missing despite wrapped blob present; regenerating DB passphrase and wiping $databaseName.")
                    forceReset(context, prefs, databaseName)
                    return createAndPersist(prefs)
                }
                val wrapped = Base64.decode(wrappedB64, Base64.NO_WRAP)
                val iv = Base64.decode(ivB64, Base64.NO_WRAP)
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                val passphrase = cipher.doFinal(wrapped)
                // F-049: a successful unwrap clears the strike counter so a
                // single transient failure (e.g. a Keystore service restart
                // mid-init) doesn't inch the device toward a destructive
                // reset over time.
                resetFailCount(prefs)
                return passphrase
            } catch (e: KeyPermanentlyInvalidatedException) {
                MindlayerLog.w(TAG, "Keystore key invalidated; regenerating DB passphrase and wiping $databaseName.", throwable = e)
                forceReset(context, prefs, databaseName)
            } catch (e: GeneralSecurityException) {
                // F-049: instead of force-resetting on the very first
                // GeneralSecurityException (which is the destructive
                // outcome an attacker who plants a corrupt blob is trying
                // to force), increment a tamper-evident strike counter
                // and only wipe after `MAX_UNWRAP_FAILS` verified strikes.
                val strikes = incrementVerifiedFailCount(prefs)
                if (strikes >= MAX_UNWRAP_FAILS) {
                    MindlayerLog.w(
                        TAG,
                        "Failed to unwrap DB passphrase (${e.javaClass.simpleName}); " +
                            "$strikes/$MAX_UNWRAP_FAILS strikes — regenerating and wiping $databaseName.",
                        throwable = e,
                    )
                    forceReset(context, prefs, databaseName)
                } else {
                    MindlayerLog.w(
                        TAG,
                        "Failed to unwrap DB passphrase (${e.javaClass.simpleName}); " +
                            "$strikes/$MAX_UNWRAP_FAILS strikes — refusing to start to avoid " +
                            "destructive reset on a possibly transient failure.",
                        throwable = e,
                    )
                    throw IllegalStateException(
                        "DB unwrap failed (strike $strikes of $MAX_UNWRAP_FAILS); " +
                            "retry on next launch or clear app data manually",
                        e,
                    )
                }
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("Unrecoverable Keystore failure while unwrapping DB key", e)
            }
        }

        return createAndPersist(prefs)
    }

    /**
     * Wipe the old ciphertext DB and the stale wrapped key before we regenerate.
     * The order matters: DB delete first (so even if prefs commit fails, we don't
     * leave an orphaned ciphertext on disk), then keystore + prefs.
     * Uses `commit()` so the caller sees a durable outcome.
     */
    private fun forceReset(
        context: Context,
        prefs: android.content.SharedPreferences,
        databaseName: String,
    ) {
        if (!context.deleteDatabase(databaseName)) {
            MindlayerLog.w(TAG, "deleteDatabase($databaseName) returned false during key-reset; the file may persist and open with the new key will fail.")
        }
        // F-049: reset is the strike-counter terminator — clear it so the
        // post-reset session starts from zero strikes. Both keys and the
        // counter MAC are dropped.
        val ok = prefs.edit()
            .remove(PREF_WRAPPED)
            .remove(PREF_IV)
            .remove(PREF_FAIL_COUNT)
            .remove(PREF_FAIL_MAC)
            .commit()
        if (!ok) {
            throw IllegalStateException("Could not persist DB-key reset to SharedPreferences")
        }
        deleteKeystoreKey()
        deleteHmacKey()
    }

    private fun createAndPersist(prefs: android.content.SharedPreferences): ByteArray {
        val key = try {
            generateKeystoreKey()
        } catch (e: Exception) {
            throw IllegalStateException("AndroidKeystore unavailable; cannot create DB passphrase", e)
        }
        val passphrase = ByteArray(PASSPHRASE_LEN).also { SecureRandom().nextBytes(it) }
        try {
            val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
            cipher.init(Cipher.ENCRYPT_MODE, key)
            val iv = cipher.iv
            val wrapped = cipher.doFinal(passphrase)
            // commit() — not apply() — so the wrapped key is durably on disk BEFORE we return
            // it to the caller. A process crash between apply() and fsync would otherwise leave
            // an encrypted DB whose key was never saved.
            val ok = prefs.edit()
                .putString(PREF_WRAPPED, Base64.encodeToString(wrapped, Base64.NO_WRAP))
                .putString(PREF_IV, Base64.encodeToString(iv, Base64.NO_WRAP))
                .commit()
            if (!ok) {
                throw IllegalStateException("Could not persist wrapped DB key to SharedPreferences")
            }
            return passphrase
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Failed to wrap DB passphrase with Keystore key", e)
        }
    }

    private fun loadKeystoreKey(): SecretKey? {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val entry = ks.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry ?: return null
        return entry.secretKey
    }

    private fun deleteKeystoreKey() {
        try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS)) ks.deleteEntry(KEY_ALIAS)
        } catch (_: Exception) {
            // best effort
        }
    }

    /**
     * F-049: read the persisted strike counter and verify its HMAC against
     * the dedicated keystore alias. If the MAC is missing or doesn't
     * verify, treat the counter as untrustworthy and return 0 — a planted
     * counter cannot bias the decision toward an immediate reset.
     */
    private fun verifiedFailCount(prefs: android.content.SharedPreferences): Int {
        val count = prefs.getInt(PREF_FAIL_COUNT, 0)
        if (count <= 0) return 0
        val storedMac = prefs.getString(PREF_FAIL_MAC, null) ?: return 0
        val key = try { loadOrCreateHmacKey() } catch (_: Exception) { return 0 }
        val computed = computeFailCountMac(count, key)
        return if (constantTimeEquals(computed, storedMac)) count else {
            MindlayerLog.w(
                TAG,
                "Unwrap-fail-count HMAC mismatch — counter rejected as tampered",
            )
            // Drop the tampered counter so it can't keep biasing future decisions.
            prefs.edit().remove(PREF_FAIL_COUNT).remove(PREF_FAIL_MAC).apply()
            0
        }
    }

    /**
     * F-049: increment the strike counter under HMAC and return the new
     * value. The HMAC is computed over the decimal text of the count
     * using the dedicated keystore alias so an attacker who can write
     * `SharedPreferences` cannot also forge the MAC.
     */
    private fun incrementVerifiedFailCount(prefs: android.content.SharedPreferences): Int {
        val newCount = verifiedFailCount(prefs) + 1
        try {
            val key = loadOrCreateHmacKey()
            val mac = computeFailCountMac(newCount, key)
            prefs.edit()
                .putInt(PREF_FAIL_COUNT, newCount)
                .putString(PREF_FAIL_MAC, mac)
                .apply()
        } catch (e: Exception) {
            // If we can't HMAC the counter (e.g. Keystore busy), don't bias
            // the next decision either way — clear the counter rather than
            // persisting an unauthenticated value.
            MindlayerLog.w(TAG, "Failed to persist strike counter HMAC", throwable = e)
            prefs.edit().remove(PREF_FAIL_COUNT).remove(PREF_FAIL_MAC).apply()
        }
        return newCount
    }

    /** F-049: clear strikes after a successful unwrap. Best-effort. */
    private fun resetFailCount(prefs: android.content.SharedPreferences) {
        val current = prefs.getInt(PREF_FAIL_COUNT, 0)
        if (current == 0 && prefs.getString(PREF_FAIL_MAC, null) == null) return
        prefs.edit().remove(PREF_FAIL_COUNT).remove(PREF_FAIL_MAC).apply()
    }

    private fun computeFailCountMac(count: Int, key: javax.crypto.SecretKey): String {
        val mac = javax.crypto.Mac.getInstance(HMAC_ALGORITHM)
        mac.init(key)
        val bytes = mac.doFinal(count.toString().toByteArray(Charsets.US_ASCII))
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var diff = 0
        for (i in a.indices) {
            diff = diff or (a[i].code xor b[i].code)
        }
        return diff == 0
    }

    private fun loadOrCreateHmacKey(): javax.crypto.SecretKey {
        val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
        val existing = ks.getEntry(KEY_ALIAS_HMAC, null) as? KeyStore.SecretKeyEntry
        if (existing != null) return existing.secretKey
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, KEYSTORE_PROVIDER)
        gen.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS_HMAC,
                KeyProperties.PURPOSE_SIGN or KeyProperties.PURPOSE_VERIFY,
            ).setDigests(KeyProperties.DIGEST_SHA256).build()
        )
        return gen.generateKey()
    }

    private fun deleteHmacKey() {
        try {
            val ks = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }
            if (ks.containsAlias(KEY_ALIAS_HMAC)) ks.deleteEntry(KEY_ALIAS_HMAC)
        } catch (_: Exception) {
            // best effort
        }
    }

    private fun generateKeystoreKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        // F-048: prefer a hardware-isolated StrongBox-backed key whose use
        // requires the device to be in the unlocked state. On devices without
        // StrongBox (or whose StrongBox is at capacity), fall back to a
        // TEE-backed key with the same unlocked-device requirement; on older
        // devices fall back to the baseline spec.
        return try {
            gen.init(buildKeySpec(strongBox = true))
            gen.generateKey()
        } catch (_: Exception) {
            try {
                gen.init(buildKeySpec(strongBox = false))
                gen.generateKey()
            } catch (_: Exception) {
                gen.init(buildBaselineKeySpec())
                gen.generateKey()
            }
        }
    }

    private fun buildKeySpec(strongBox: Boolean): KeyGenParameterSpec {
        val builder = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
        // setUnlockedDeviceRequired + setIsStrongBoxBacked are API 28+. The
        // module's minSdk is 26, so guard explicitly.
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
            builder.setUnlockedDeviceRequired(true)
            if (strongBox) builder.setIsStrongBoxBacked(true)
        }
        return builder.build()
    }

    private fun buildBaselineKeySpec(): KeyGenParameterSpec =
        KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .build()
}
