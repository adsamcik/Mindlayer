package com.adsamcik.mindlayer.sdk.db

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import android.util.Log
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provides a 32-byte passphrase for the SQLCipher-encrypted SDK conversation-history DB.
 *
 * The passphrase is a random 32-byte blob wrapped (AES/GCM) with a Keystore-resident AES key
 * (alias `mindlayer.sdk.db.key`, distinct from the service's own alias so the two DBs cannot
 * share keys). Only the wrapped ciphertext + IV is persisted in SharedPreferences.
 *
 * Fail-closed: if the Keystore is unavailable, [get] throws [IllegalStateException]. Callers
 * must propagate rather than silently falling back to a plaintext DB.
 *
 * Cross-process safety: init path is serialised via an exclusive [java.nio.channels.FileLock]
 * so multiple processes in the client app cannot race to create divergent passphrases.
 */
internal object DbKeyProvider {

    private const val TAG = "Mindlayer.SdkDbKey"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "mindlayer.sdk.db.key"
    /** F-049: HMAC alias for tamper-evident strike counter. */
    private const val KEY_ALIAS_HMAC = "mindlayer.sdk.db.key.hmac"
    private const val PREF_FILE = "mindlayer_sdk_db_key"
    private const val PREF_WRAPPED = "wrapped_key"
    private const val PREF_IV = "wrapped_iv"
    /** F-049: persisted unwrap-failure strike counter. */
    private const val PREF_FAIL_COUNT = "unwrap_fail_count"
    /** F-049: HMAC over the strike count, base-64 encoded. */
    private const val PREF_FAIL_MAC = "unwrap_fail_mac"
    private const val LOCK_FILE = "mindlayer_sdk_db_key.lock"
    private const val PASSPHRASE_LEN = 32
    private const val GCM_TAG_BITS = 128
    private const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"
    private const val HMAC_ALGORITHM = "HmacSHA256"
    /** F-049: see app-side `DbKeyProvider.MAX_UNWRAP_FAILS` for rationale. */
    private const val MAX_UNWRAP_FAILS = 3

    private val lock = Any()

    /**
     * Returns the passphrase for the DB named [databaseName]. If regeneration is forced,
     * the existing DB file is **deleted** first — otherwise Room would try to open stale
     * ciphertext with a new key and crash. Callers must not cache the returned array;
     * zero their reference once the database is built.
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
        lockFile.parentFile?.mkdirs()
        java.io.RandomAccessFile(lockFile, "rw").use { raf ->
            raf.channel.use { channel ->
                val fileLock = channel.lock()
                try {
                    return block()
                } finally {
                    try { fileLock.release() } catch (_: Throwable) { }
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
                    // F-026: wrapped blob exists but Keystore entry is gone
                    // (uninstall/restore, factory reset of the Keystore alone,
                    // or aggressive cleaner). Match the GeneralSecurityException
                    // recovery path: wipe orphaned ciphertext + prefs and
                    // regenerate.
                    Log.w(TAG, "Keystore entry missing despite wrapped blob present; regenerating DB passphrase and wiping $databaseName.")
                    forceReset(context, prefs, databaseName)
                    return createAndPersist(prefs)
                }
                val wrapped = Base64.decode(wrappedB64, Base64.NO_WRAP)
                val iv = Base64.decode(ivB64, Base64.NO_WRAP)
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                val passphrase = cipher.doFinal(wrapped)
                resetFailCount(prefs)
                return passphrase
            } catch (e: KeyPermanentlyInvalidatedException) {
                Log.w(TAG, "Keystore key invalidated; regenerating DB passphrase and wiping $databaseName.", e)
                forceReset(context, prefs, databaseName)
            } catch (e: GeneralSecurityException) {
                // F-049: tamper-evident strike counter; only reset after
                // MAX_UNWRAP_FAILS verified strikes. See the app-side
                // `DbKeyProvider` for the rationale.
                val strikes = incrementVerifiedFailCount(prefs)
                if (strikes >= MAX_UNWRAP_FAILS) {
                    Log.w(TAG, "Failed to unwrap DB passphrase (${e.javaClass.simpleName}); $strikes/$MAX_UNWRAP_FAILS strikes — regenerating and wiping $databaseName.", e)
                    forceReset(context, prefs, databaseName)
                } else {
                    Log.w(TAG, "Failed to unwrap DB passphrase (${e.javaClass.simpleName}); $strikes/$MAX_UNWRAP_FAILS strikes — refusing to start.", e)
                    throw IllegalStateException(
                        "DB unwrap failed (strike $strikes of $MAX_UNWRAP_FAILS); retry on next launch or clear app data manually",
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

    private fun forceReset(
        context: Context,
        prefs: android.content.SharedPreferences,
        databaseName: String,
    ) {
        if (!context.deleteDatabase(databaseName)) {
            Log.w(TAG, "deleteDatabase($databaseName) returned false during key-reset; future open may fail.")
        }
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

    private fun generateKeystoreKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        // F-048: prefer StrongBox-backed + unlocked-device-required; fall
        // back to TEE-backed unlocked-device-required; final fallback is the
        // baseline spec for devices that reject either flag at key gen time.
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

    // ── F-049 strike-counter HMAC plumbing ──────────────────────────────

    private fun verifiedFailCount(prefs: android.content.SharedPreferences): Int {
        val count = prefs.getInt(PREF_FAIL_COUNT, 0)
        if (count <= 0) return 0
        val storedMac = prefs.getString(PREF_FAIL_MAC, null) ?: return 0
        val key = try { loadOrCreateHmacKey() } catch (_: Exception) { return 0 }
        val computed = computeFailCountMac(count, key)
        return if (constantTimeEquals(computed, storedMac)) count else {
            Log.w(TAG, "Unwrap-fail-count HMAC mismatch — counter rejected as tampered")
            prefs.edit().remove(PREF_FAIL_COUNT).remove(PREF_FAIL_MAC).apply()
            0
        }
    }

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
            Log.w(TAG, "Failed to persist strike counter HMAC", e)
            prefs.edit().remove(PREF_FAIL_COUNT).remove(PREF_FAIL_MAC).apply()
        }
        return newCount
    }

    private fun resetFailCount(prefs: android.content.SharedPreferences) {
        val current = prefs.getInt(PREF_FAIL_COUNT, 0)
        if (current == 0 && prefs.getString(PREF_FAIL_MAC, null) == null) return
        prefs.edit().remove(PREF_FAIL_COUNT).remove(PREF_FAIL_MAC).apply()
    }

    private fun computeFailCountMac(count: Int, key: javax.crypto.SecretKey): String {
        val mac = javax.crypto.Mac.getInstance(HMAC_ALGORITHM)
        mac.init(key)
        return Base64.encodeToString(
            mac.doFinal(count.toString().toByteArray(Charsets.US_ASCII)),
            Base64.NO_WRAP,
        )
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
}
