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
    private const val PREF_FILE = "mindlayer_db_key"
    private const val PREF_WRAPPED = "wrapped_key"
    private const val PREF_IV = "wrapped_iv"
    private const val LOCK_FILE = "mindlayer_db_key.lock"
    private const val PASSPHRASE_LEN = 32
    private const val GCM_TAG_BITS = 128
    private const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"

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
                    ?: error("Keystore key missing despite wrapped blob present")
                val wrapped = Base64.decode(wrappedB64, Base64.NO_WRAP)
                val iv = Base64.decode(ivB64, Base64.NO_WRAP)
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
                return cipher.doFinal(wrapped)
            } catch (e: KeyPermanentlyInvalidatedException) {
                MindlayerLog.w(TAG, "Keystore key invalidated; regenerating DB passphrase and wiping $databaseName.", throwable = e)
                forceReset(context, prefs, databaseName)
            } catch (e: GeneralSecurityException) {
                MindlayerLog.w(TAG, "Failed to unwrap DB passphrase (${e.javaClass.simpleName}); regenerating and wiping $databaseName.", throwable = e)
                forceReset(context, prefs, databaseName)
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
        val ok = prefs.edit().remove(PREF_WRAPPED).remove(PREF_IV).commit()
        if (!ok) {
            throw IllegalStateException("Could not persist DB-key reset to SharedPreferences")
        }
        deleteKeystoreKey()
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

    private fun generateKeystoreKey(): SecretKey {
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        val spec = KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .setRandomizedEncryptionRequired(true)
            .build()
        gen.init(spec)
        return gen.generateKey()
    }
}
