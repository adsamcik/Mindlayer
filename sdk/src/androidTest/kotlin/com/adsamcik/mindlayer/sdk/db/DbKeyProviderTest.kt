package com.adsamcik.mindlayer.sdk.db

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for the SDK's [DbKeyProvider] — exercises real AndroidKeystore +
 * binary-file storage. Tests are hermetic via [clear] in @Before/@After, which wipes the
 * keystore alias, key file, lock file, any quarantined key files, and the throwaway test
 * DB. (The legacy SharedPreferences entry is also cleared so test runs that span the
 * H7 storage migration boundary stay deterministic.)
 */
@RunWith(AndroidJUnit4::class)
class DbKeyProviderTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val keyAlias = "mindlayer.sdk.db.key"
    private val legacyPrefFile = "mindlayer_sdk_db_key"
    private val keyFile = "mindlayer_sdk_db_key.bin"
    private val lockFile = "mindlayer_sdk_db_key.lock"

    @Before
    fun setUp() = clear()

    @After
    fun tearDown() = clear()

    private fun clear() {
        val ctx = context
        ctx.deleteDatabase(TEST_DB)
        ctx.getSharedPreferences(legacyPrefFile, Context.MODE_PRIVATE)
            .edit().clear().commit()
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(keyAlias)) ks.deleteEntry(keyAlias)
        } catch (_: Exception) { /* best effort */ }
        File(ctx.filesDir, lockFile).delete()
        File(ctx.filesDir, keyFile).delete()
        // Quarantine files left behind by previous tamper-path runs.
        ctx.filesDir.listFiles { _, name -> name.startsWith("$keyFile.tampered-") }
            ?.forEach { it.delete() }
    }

    @Test
    fun firstTimeGeneration_persistsWrappedKey() {
        val passphrase = DbKeyProvider.get(context, TEST_DB)

        assertEquals(32, passphrase.size)
        assertFalse("passphrase must not be all zeros", passphrase.all { it == 0.toByte() })

        val keyFileObj = File(context.filesDir, keyFile)
        assertTrue("wrapped DB key file should be created", keyFileObj.exists())
        assertTrue("wrapped DB key file must be non-empty", keyFileObj.length() > 0)

        // Verify the on-disk record decodes via the same parser the production code uses.
        // Catches partial writes / format drift; better than a length check alone.
        val decoded = DbKeyProvider.decodeKeyRecord(keyFileObj.readBytes())
        assertNotNull("wrapped DB key file must decode cleanly", decoded)
        assertTrue("IV must be non-empty", decoded!!.iv.isNotEmpty())
        assertTrue("wrapped blob must be non-empty", decoded.wrapped.isNotEmpty())

        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue("keystore alias should exist after first-time gen", ks.containsAlias(keyAlias))
    }

    @Test
    fun reopen_returnsSamePassphrase() {
        val first = DbKeyProvider.get(context, TEST_DB)
        val second = DbKeyProvider.get(context, TEST_DB)
        assertArrayEquals(first, second)
    }

    /**
     * AEAD authentication failure on the wrapped key is treated as tamper:
     * the key file is quarantined and [DbKeyProvider.get] throws
     * [IllegalStateException]. The DB file is **not** deleted — recovery is a
     * deliberate manual action (clear app data). This locks in the post-H7
     * fail-closed contract.
     */
    @Test
    fun corruptedWrappedBlob_quarantinesAndThrows() {
        DbKeyProvider.get(context, TEST_DB)

        // Plant a dummy DB file so we can verify the tamper path does NOT delete it.
        val dbFile = context.getDatabasePath(TEST_DB).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        assertTrue("precondition: dummy DB file exists", dbFile.exists())

        // Flip the wrapped-payload bytes via the production format helpers (no hand-rolled
        // offset math). The header (magic/version/iv) stays intact so decoding succeeds,
        // but Cipher.doFinal will fail AEAD verification on the tampered wrapped blob.
        corruptWrappedPayload(File(context.filesDir, keyFile))

        try {
            DbKeyProvider.get(context, TEST_DB)
            fail("expected IllegalStateException after corruption — DbKeyProvider must fail closed")
        } catch (e: IllegalStateException) {
            assertTrue(
                "exception message should match the tamper-path prefix we own; got: ${e.message}",
                e.message?.startsWith("Wrapped DB key authentication failed") == true,
            )
            assertTrue(
                "cause should be a security-layer exception; got: ${e.cause?.javaClass?.name}",
                e.cause is GeneralSecurityException,
            )
        }

        assertTrue("DB file must NOT be wiped on tamper", dbFile.exists())
        assertFalse(
            "active key file must be removed (renamed to quarantine)",
            File(context.filesDir, keyFile).exists(),
        )
        val quarantined = context.filesDir.listFiles { _, name ->
            name.startsWith("$keyFile.tampered-")
        }
        assertNotNull("a quarantine file must be created on tamper", quarantined)
        assertEquals(
            "exactly one quarantine file expected after a single tamper event",
            1,
            quarantined!!.size,
        )

        // Lock in the post-quarantine "next call" behavior so a future change can't silently
        // resurrect the old data-loss path. Current contract: after the IllegalStateException
        // surfaces to the caller, a subsequent get() treats the missing keyfile as a fresh
        // install — it mints a new wrapped key. The previously-encrypted DB on disk becomes
        // unreadable to SQLCipher, surfacing as an explicit decrypt failure rather than a
        // silent wipe. (If the contract changes — e.g., to refuse while DB exists — this
        // assertion will fail loudly and force the test to be updated alongside the code.)
        val nextPassphrase = DbKeyProvider.get(context, TEST_DB)
        assertEquals(32, nextPassphrase.size)
        assertTrue(
            "active key file must be recreated on subsequent get() after tamper",
            File(context.filesDir, keyFile).exists(),
        )
        assertTrue(
            "previously-encrypted DB file must remain on disk; recovery requires manual app-data clear",
            dbFile.exists(),
        )
    }

    /**
     * One-shot legacy migration: a wrapped key in the old SharedPreferences storage must
     * be promoted into the binary key file on first [DbKeyProvider.get] call, and prefs
     * cleared. We verify the migration step itself — not post-migration usability, since
     * a faithful "real install" round-trip would require coupling the seeded blob to a
     * real Keystore alias the test does not own.
     */
    @Test
    fun legacyPrefsMigration_promotesToBinaryFileAndClearsPrefs() {
        val prefs = context.getSharedPreferences(legacyPrefFile, Context.MODE_PRIVATE)
        val seedWrapped = ByteArray(48) { (it + 1).toByte() }
        val seedIv = ByteArray(12) { (it + 100).toByte() }
        prefs.edit()
            .putString("wrapped_key", Base64.encodeToString(seedWrapped, Base64.NO_WRAP))
            .putString("wrapped_iv", Base64.encodeToString(seedIv, Base64.NO_WRAP))
            .commit()

        // First get(): migrates prefs → binary file, then attempts decrypt. The decrypt
        // will fail (no Keystore alias backs the seeded blob) and surface as
        // IllegalStateException; the migration step ran first, which is what we verify.
        try { DbKeyProvider.get(context, TEST_DB) } catch (_: IllegalStateException) { /* expected */ }

        val migratedFile = File(context.filesDir, keyFile)
        assertTrue("binary key file must be created from legacy prefs", migratedFile.exists())

        val migrated = DbKeyProvider.decodeKeyRecord(migratedFile.readBytes())
        assertNotNull("migrated key file must decode cleanly", migrated)
        assertArrayEquals("migrated IV must match seeded legacy IV", seedIv, migrated!!.iv)
        assertArrayEquals(
            "migrated wrapped blob must match seeded legacy blob",
            seedWrapped,
            migrated.wrapped,
        )

        assertEquals(
            "legacy wrapped_key pref must be cleared after migration",
            null,
            prefs.getString("wrapped_key", null),
        )
        assertEquals(
            "legacy wrapped_iv pref must be cleared after migration",
            null,
            prefs.getString("wrapped_iv", null),
        )
    }

    @Test
    fun concurrentGet_producesSinglePassphrase() {
        val threadCount = 8
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val results = arrayOfNulls<ByteArray>(threadCount)
        // Capture exceptions per worker so a single failure isn't laundered through a bare
        // assertNotNull(first) — the real cause shows up in the assertion message.
        val errors = ConcurrentLinkedQueue<Throwable>()
        val exec = Executors.newFixedThreadPool(threadCount)
        try {
            repeat(threadCount) { i ->
                exec.submit {
                    try {
                        startLatch.await()
                        results[i] = DbKeyProvider.get(context, TEST_DB)
                    } catch (t: Throwable) {
                        errors.add(t)
                    } finally {
                        doneLatch.countDown()
                    }
                }
            }
            startLatch.countDown()
            assertTrue(doneLatch.await(30, TimeUnit.SECONDS))
        } finally {
            exec.shutdownNow()
        }
        if (errors.isNotEmpty()) {
            fail("worker threads threw: ${errors.joinToString("; ") { it.toString() }}")
        }
        val first = results[0]
        assertNotNull(first)
        for (i in 1 until threadCount) {
            assertArrayEquals(
                "thread $i returned a different passphrase — first-time gen was not serialised",
                first,
                results[i],
            )
        }
    }

    /**
     * Tampers with the wrapped portion of an existing key file by round-tripping through
     * the production format helpers. Replaces every byte of [DbKeyProvider.KeyRecord.wrapped]
     * with `0x5A`, preserving lengths so the file decodes cleanly but AEAD verify fails on
     * the next unwrap. Writes back via tmp + rename to mirror the production write path.
     */
    private fun corruptWrappedPayload(file: File) {
        val bytes = file.readBytes()
        val record = DbKeyProvider.decodeKeyRecord(bytes)
            ?: error("could not decode wrapped key file — production format may have drifted")
        val tampered = DbKeyProvider.KeyRecord(
            iv = record.iv,
            wrapped = ByteArray(record.wrapped.size) { 0x5A.toByte() },
        )
        val encoded = DbKeyProvider.encodeKeyRecord(tampered)
        val tmp = File(file.parentFile, "${file.name}.test-tmp")
        tmp.writeBytes(encoded)
        check(file.delete()) { "could not delete original key file before tamper rewrite" }
        check(tmp.renameTo(file)) { "could not rename tampered key file into place" }
    }

    companion object {
        private const val TEST_DB = "dbkey_test.db"
    }
}
