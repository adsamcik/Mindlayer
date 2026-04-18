package com.mindlayer.service.security

import android.content.Context
import android.util.Base64
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.security.KeyStore
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented tests for [DbKeyProvider] — exercises real AndroidKeystore + SharedPreferences
 * + filesystem state. Tests are hermetic: [clear] is called in @Before and @After to wipe
 * the keystore alias, prefs, lock file, and a throwaway test DB.
 */
@RunWith(AndroidJUnit4::class)
class DbKeyProviderTest {

    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private val keyAlias = "mindlayer.db.key.app"
    private val prefFile = "mindlayer_db_key"
    private val lockFile = "mindlayer_db_key.lock"

    @Before
    fun setUp() = clear()

    @After
    fun tearDown() = clear()

    private fun clear() {
        val ctx = context
        ctx.deleteDatabase(TEST_DB)
        ctx.getSharedPreferences(prefFile, Context.MODE_PRIVATE)
            .edit().clear().commit()
        try {
            val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
            if (ks.containsAlias(keyAlias)) ks.deleteEntry(keyAlias)
        } catch (_: Exception) { /* best effort */ }
        File(ctx.filesDir, lockFile).delete()
    }

    @Test
    fun firstTimeGeneration_persistsWrappedKey() {
        val passphrase = DbKeyProvider.get(context, TEST_DB)

        assertEquals(32, passphrase.size)
        assertFalse("passphrase must not be all zeros", passphrase.all { it == 0.toByte() })

        val prefs = context.getSharedPreferences(prefFile, Context.MODE_PRIVATE)
        assertNotNull(prefs.getString("wrapped_key", null))
        assertNotNull(prefs.getString("wrapped_iv", null))

        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        assertTrue("keystore alias should exist after first-time gen", ks.containsAlias(keyAlias))
    }

    @Test
    fun reopen_returnsSamePassphrase() {
        val first = DbKeyProvider.get(context, TEST_DB)
        val second = DbKeyProvider.get(context, TEST_DB)
        assertArrayEquals(first, second)
    }

    @Test
    fun corruptedWrappedBlob_regeneratesAndWipesDb() {
        val original = DbKeyProvider.get(context, TEST_DB).copyOf()

        // Create a dummy DB file so we can assert it gets wiped on reset.
        val dbFile = context.getDatabasePath(TEST_DB).apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        assertTrue("precondition: dummy DB file exists", dbFile.exists())

        // Corrupt the wrapped_key: overwrite with a bogus blob of the same length so
        // the subsequent Cipher.doFinal throws AEADBadTagException (GeneralSecurityException),
        // which triggers forceReset.
        val prefs = context.getSharedPreferences(prefFile, Context.MODE_PRIVATE)
        val originalWrappedB64 = prefs.getString("wrapped_key", null)!!
        val originalWrapped = Base64.decode(originalWrappedB64, Base64.NO_WRAP)
        val corrupted = ByteArray(originalWrapped.size) { 0x5A.toByte() }
        prefs.edit()
            .putString("wrapped_key", Base64.encodeToString(corrupted, Base64.NO_WRAP))
            .commit()

        val regenerated = DbKeyProvider.get(context, TEST_DB)

        assertEquals(32, regenerated.size)
        assertFalse(
            "regenerated passphrase must differ from original",
            original.contentEquals(regenerated),
        )
        assertFalse("DB file should be wiped after reset", dbFile.exists())
        val newWrapped = prefs.getString("wrapped_key", null)
        assertNotNull(newWrapped)
        assertNotEquals(
            "new wrapped_key must differ from corrupted blob",
            Base64.encodeToString(corrupted, Base64.NO_WRAP),
            newWrapped,
        )
    }

    @Test
    fun concurrentGet_producesSinglePassphrase() {
        val threadCount = 8
        val startLatch = CountDownLatch(1)
        val doneLatch = CountDownLatch(threadCount)
        val results = arrayOfNulls<ByteArray>(threadCount)
        val exec = Executors.newFixedThreadPool(threadCount)
        try {
            repeat(threadCount) { i ->
                exec.submit {
                    try {
                        startLatch.await()
                        results[i] = DbKeyProvider.get(context, TEST_DB)
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

    companion object {
        private const val TEST_DB = "dbkey_test.db"
    }
}
