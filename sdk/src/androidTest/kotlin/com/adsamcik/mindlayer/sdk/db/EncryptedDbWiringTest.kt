package com.adsamcik.mindlayer.sdk.db

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented wiring test that proves the SQLCipher native lib loads and Room can open an
 * in-memory encrypted DB via [SupportOpenHelperFactory]. Ported from the JVM/Robolectric
 * @Ignore'd version — SQLCipher ships native-only, so this must run on device.
 */
@RunWith(AndroidJUnit4::class)
class EncryptedDbWiringTest {

    @Test
    fun canOpenEncryptedDb_withStaticKey() {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        try { System.loadLibrary("sqlcipher") } catch (_: UnsatisfiedLinkError) { }
        val key = ByteArray(32) { it.toByte() }
        val factory = SupportOpenHelperFactory(key)
        val db = Room.inMemoryDatabaseBuilder(ctx, MindlayerDatabase::class.java)
            .openHelperFactory(factory)
            .build()
        // Force the open helper to actually open the DB (Room is lazy otherwise).
        db.openHelper.writableDatabase
        db.close()
    }
}
