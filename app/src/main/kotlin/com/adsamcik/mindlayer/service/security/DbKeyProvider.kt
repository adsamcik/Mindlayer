package com.adsamcik.mindlayer.service.security

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyPermanentlyInvalidatedException
import android.security.keystore.KeyProperties
import android.util.Base64
import androidx.annotation.VisibleForTesting
import com.adsamcik.mindlayer.service.logging.MindlayerLog
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.StandardCopyOption
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.AEADBadTagException
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Provides a 32-byte passphrase for the SQLCipher-encrypted log Room DB.
 *
 * The passphrase is a random 32-byte blob wrapped (AES/GCM) with a Keystore-resident AES key.
 * Only the wrapped ciphertext + IV is persisted on disk; the raw passphrase never touches
 * disk in cleartext form.
 *
 * **Storage** (was: SharedPreferences pre-fix; see H7):
 * The wrapped key + IV are stored in a single binary file [KEY_FILE] inside `filesDir`.
 * SharedPreferences are *per-process cached* and `commit()` from one process is **not**
 * visible to another process even after the FileLock is released — that race produced
 * divergent passphrases and bricked the encrypted DB. Reading the bytes directly from
 * disk inside the cross-process lock guarantees cache coherence.
 *
 * Fail-closed: if the Keystore is unavailable, this throws [IllegalStateException]. The service
 * must refuse to start rather than fall back to a plaintext DB.
 *
 * Cross-process safety: [get] serialises init across all processes in this app via an exclusive
 * [java.nio.channels.FileLock] on a sentinel file in `filesDir`. The wrapped-key file is read
 * and written **only inside the lock**.
 */
internal object DbKeyProvider {

    private const val TAG = "DbKeyProvider"
    private const val KEYSTORE_PROVIDER = "AndroidKeyStore"
    private const val KEY_ALIAS = "mindlayer.db.key.app"

    /** New on-disk store. Single binary file, atomic rename writes, read inside FileLock. */
    private const val KEY_FILE = "mindlayer_db_key.bin"

    /** Legacy SharedPreferences store (retained for one-shot migration only). */
    private const val LEGACY_PREF_FILE = "mindlayer_db_key"
    private const val LEGACY_PREF_WRAPPED = "wrapped_key"
    private const val LEGACY_PREF_IV = "wrapped_iv"

    private const val LOCK_FILE = "mindlayer_db_key.lock"
    private const val PASSPHRASE_LEN = 32
    private const val GCM_TAG_BITS = 128
    private const val AES_GCM_TRANSFORM = "AES/GCM/NoPadding"

    // KeyFile binary record format constants.
    // Layout: [magic:4 LE][version:2 LE][ivLen:2 LE][iv][wrappedLen:4 LE][wrapped]
    @VisibleForTesting internal const val MAGIC = 0x4D4C4B31 // 'MLK1'
    @VisibleForTesting internal const val VERSION: Short = 1
    private const val HEADER_BYTES = 4 + 2 + 2
    private const val MAX_IV_LEN = 64
    private const val MAX_WRAPPED_LEN = 4096

    private val lock = Any()

    /**
     * Returns the passphrase for the DB named [databaseName]. If a regeneration is forced
     * (Keystore invalidated), the existing DB file is **deleted** before a new key is returned.
     * AEAD authentication failures are treated as tamper and **do not** delete the DB; they
     * quarantine the key file and throw [IllegalStateException].
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
        val lockFile = File(context.filesDir, LOCK_FILE)
        lockFile.parentFile?.mkdirs()
        RandomAccessFile(lockFile, "rw").use { raf ->
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

    private fun keyFile(context: Context): File = File(context.filesDir, KEY_FILE)

    private fun loadOrCreate(context: Context, databaseName: String): ByteArray {
        val keyFile = keyFile(context)

        if (!keyFile.exists()) {
            migrateLegacyPrefsIfPresent(context, keyFile)
        }
        assertRegularFileIfExists(keyFile)

        val record = readKeyFile(keyFile)
        if (record != null) {
            try {
                val key = loadKeystoreKey()
                if (key == null) {
                    MindlayerLog.w(
                        TAG,
                        "Keystore key missing while wrapped DB key exists; regenerating passphrase and wiping $databaseName. " +
                            "Existing encrypted logs cannot be recovered without the lost Keystore key.",
                    )
                    forceReset(context, keyFile, databaseName)
                    return createAndPersist(keyFile)
                }
                val cipher = Cipher.getInstance(AES_GCM_TRANSFORM)
                cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, record.iv))
                return cipher.doFinal(record.wrapped)
            } catch (e: KeyPermanentlyInvalidatedException) {
                MindlayerLog.w(
                    TAG,
                    "Keystore key invalidated; regenerating DB passphrase and wiping $databaseName.",
                    throwable = e,
                )
                forceReset(context, keyFile, databaseName)
            } catch (e: AEADBadTagException) {
                handleTamper(keyFile, e)
            } catch (e: GeneralSecurityException) {
                handleTamper(keyFile, e)
            } catch (e: IllegalStateException) {
                throw e
            } catch (e: Exception) {
                throw IllegalStateException("Unrecoverable Keystore failure while unwrapping DB key", e)
            }
        }

        return createAndPersist(keyFile)
    }

    /**
     * Quarantines the on-disk wrapped key and throws. Called on AEADBadTag /
     * non-key-invalidated GeneralSecurityException — these indicate the wrapped key
     * blob has been tampered with or corrupted, NOT that the user pulled biometrics.
     * Wiping the DB silently in this case would let an attacker erase data with a
     * single bit flip; we make recovery a deliberate manual step instead.
     */
    private fun handleTamper(keyFile: File, cause: Throwable): Nothing {
        val ts = System.currentTimeMillis()
        val quarantine = File(keyFile.parentFile, "${keyFile.name}.tampered-$ts")
        val moved = try {
            keyFile.renameTo(quarantine)
        } catch (_: Throwable) {
            false
        }
        val quarantinePath = if (moved) quarantine.absolutePath else "<rename failed: ${keyFile.absolutePath}>"
        MindlayerLog.e(
            TAG,
            "Wrapped DB key authentication failed (${cause.javaClass.simpleName}); " +
                "quarantined to $quarantinePath. Refusing to delete the DB — manual app-data " +
                "clear is required if recovery is desired.",
            throwable = cause,
        )
        if (!moved) {
            try { keyFile.delete() } catch (_: Throwable) { /* ignore */ }
        }
        throw IllegalStateException(
            "Wrapped DB key authentication failed — file may have been tampered. Quarantined to $quarantinePath",
            cause,
        )
    }

    /**
     * Wipe the old ciphertext DB and the stale wrapped key before we regenerate.
     * Order: DB delete first (so even if key write fails, we don't leave an orphaned
     * ciphertext on disk), then keystore + key file.
     */
    private fun forceReset(context: Context, keyFile: File, databaseName: String) {
        if (!context.deleteDatabase(databaseName)) {
            MindlayerLog.w(
                TAG,
                "deleteDatabase($databaseName) returned false during key-reset; the file may persist " +
                    "and open with the new key will fail.",
            )
        }
        if (keyFile.exists() && !keyFile.delete()) {
            throw IllegalStateException("Could not delete stale wrapped DB key file: ${keyFile.absolutePath}")
        }
        deleteKeystoreKey()
    }

    private fun createAndPersist(keyFile: File): ByteArray {
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
            writeKeyFile(keyFile, KeyRecord(iv, wrapped))
            return passphrase
        } catch (e: GeneralSecurityException) {
            throw IllegalStateException("Failed to wrap DB passphrase with Keystore key", e)
        }
    }

    private fun migrateLegacyPrefsIfPresent(context: Context, keyFile: File) {
        val prefs = context.getSharedPreferences(LEGACY_PREF_FILE, Context.MODE_PRIVATE)
        val wrappedB64 = prefs.getString(LEGACY_PREF_WRAPPED, null) ?: return
        val ivB64 = prefs.getString(LEGACY_PREF_IV, null) ?: return
        try {
            val wrapped = Base64.decode(wrappedB64, Base64.NO_WRAP)
            val iv = Base64.decode(ivB64, Base64.NO_WRAP)
            writeKeyFile(keyFile, KeyRecord(iv, wrapped))
            prefs.edit().clear().commit()
            MindlayerLog.i(TAG, "Migrated wrapped DB key from legacy SharedPreferences to ${keyFile.name}.")
        } catch (e: Exception) {
            MindlayerLog.w(
                TAG,
                "Failed to migrate legacy wrapped DB key from prefs; will regenerate.",
                throwable = e,
            )
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

    // -----------------------------------------------------------------------------
    // Key file binary format (pure JVM logic; unit-testable without Android).
    // -----------------------------------------------------------------------------

    @VisibleForTesting
    internal data class KeyRecord(val iv: ByteArray, val wrapped: ByteArray) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is KeyRecord) return false
            return iv.contentEquals(other.iv) && wrapped.contentEquals(other.wrapped)
        }
        override fun hashCode(): Int = 31 * iv.contentHashCode() + wrapped.contentHashCode()
    }

    @VisibleForTesting
    internal fun encodeKeyRecord(record: KeyRecord): ByteArray {
        require(record.iv.size in 1..MAX_IV_LEN) { "iv length out of range: ${record.iv.size}" }
        require(record.wrapped.size in 1..MAX_WRAPPED_LEN) { "wrapped length out of range: ${record.wrapped.size}" }
        val total = HEADER_BYTES + record.iv.size + 4 + record.wrapped.size
        val buf = ByteBuffer.allocate(total).order(ByteOrder.LITTLE_ENDIAN)
        buf.putInt(MAGIC)
        buf.putShort(VERSION)
        buf.putShort(record.iv.size.toShort())
        buf.put(record.iv)
        buf.putInt(record.wrapped.size)
        buf.put(record.wrapped)
        return buf.array()
    }

    /** Returns null on any parse error (treated as "no key present"). */
    @VisibleForTesting
    internal fun decodeKeyRecord(bytes: ByteArray): KeyRecord? {
        if (bytes.size < HEADER_BYTES + 4) return null
        val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        try {
            val magic = buf.int
            if (magic != MAGIC) return null
            val version = buf.short
            if (version != VERSION) return null
            val ivLen = buf.short.toInt() and 0xFFFF
            if (ivLen <= 0 || ivLen > MAX_IV_LEN) return null
            if (buf.remaining() < ivLen + 4) return null
            val iv = ByteArray(ivLen)
            buf.get(iv)
            val wrappedLen = buf.int
            if (wrappedLen <= 0 || wrappedLen > MAX_WRAPPED_LEN) return null
            if (buf.remaining() < wrappedLen) return null
            val wrapped = ByteArray(wrappedLen)
            buf.get(wrapped)
            return KeyRecord(iv, wrapped)
        } catch (_: Exception) {
            return null
        }
    }

    private fun assertRegularFileIfExists(file: File) {
        val path = file.toPath()
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) &&
            !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)
        ) {
            throw IllegalStateException("Refusing to use non-regular DB key file: ${file.absolutePath}")
        }
    }

    private fun readKeyFile(file: File): KeyRecord? {
        if (!file.exists()) return null
        return try {
            RandomAccessFile(file, "r").use { raf ->
                val len = raf.length()
                if (len <= 0 || len > (HEADER_BYTES + 4 + MAX_IV_LEN + MAX_WRAPPED_LEN).toLong()) return null
                val bytes = ByteArray(len.toInt())
                raf.readFully(bytes)
                decodeKeyRecord(bytes)
            }
        } catch (_: IOException) {
            null
        } catch (_: SecurityException) {
            null
        }
    }

    private fun writeKeyFile(file: File, record: KeyRecord) {
        val bytes = encodeKeyRecord(record)
        val parent = file.parentFile
        parent?.mkdirs()
        assertRegularFileIfExists(file)
        val tmp = File(parent, "${file.name}.tmp")
        assertRegularFileIfExists(tmp)
        try {
            RandomAccessFile(tmp, "rw").use { raf ->
                raf.setLength(0)
                raf.write(bytes)
                try { raf.fd.sync() } catch (_: Throwable) { /* best effort */ }
            }
            Files.move(
                tmp.toPath(),
                file.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (e: IOException) {
            try { tmp.delete() } catch (_: Throwable) { /* best effort */ }
            throw IllegalStateException("Could not persist wrapped DB key to ${file.absolutePath}", e)
        }
    }
}
