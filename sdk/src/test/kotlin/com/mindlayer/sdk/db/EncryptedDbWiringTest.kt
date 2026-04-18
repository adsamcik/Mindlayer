package com.mindlayer.sdk.db

import androidx.test.core.app.ApplicationProvider
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory
import androidx.room.Room
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Sanity wiring test for the SQLCipher-backed Room builder.
 *
 * Ignored on JVM/Robolectric: SQLCipher ships its SQLite as a native .so for Android targets
 * only; there is no JVM build, so `SupportOpenHelperFactory.create()` fails at `loadLibraries`.
 * This test is kept as executable documentation; it will pass when run as an instrumented
 * androidTest (out of scope for this module) and exists to prevent accidental regressions
 * in the factory wiring (imports, API shape).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EncryptedDbWiringTest {

    @Test
    @Ignore("SQLCipher native lib is not available on JVM/Robolectric — covered by instrumented tests")
    fun canOpenEncryptedDb_withStaticKey() {
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        val key = ByteArray(32) { it.toByte() }
        val factory = SupportOpenHelperFactory(key)
        val db = Room.inMemoryDatabaseBuilder(ctx, MindlayerDatabase::class.java)
            .openHelperFactory(factory)
            .build()
        db.close()
    }
}
