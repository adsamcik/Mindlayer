package com.adsamcik.mindlayer.service.logging

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.*
import com.adsamcik.mindlayer.service.security.DbKeyProvider
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * The log DB is SQLCipher-encrypted with a random 32-byte passphrase stored wrapped in the
 * AndroidKeystore (see [DbKeyProvider]). Cross-install backup/restore will produce an
 * unreadable DB; this is acceptable pre-release — logs are ephemeral and [fallbackToDestructiveMigration]
 * already allows resetting the table on schema changes.
 */
@Database(entities = [LogEntry::class], version = 1, exportSchema = false)
abstract class LogDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    companion object {
        private const val DB_NAME = "mindlayer_logs"
        private const val PREF_FILE = "mindlayer_db_state"
        private const val PREF_ENCRYPTED_V1 = "db_encrypted_v1"

        @Volatile
        private var INSTANCE: LogDatabase? = null

        fun getInstance(context: Context): LogDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }

        /** Test-only seam: inject a Room in-memory [LogDatabase] in place of the encrypted singleton. */
        @VisibleForTesting
        fun setInstance(db: LogDatabase) {
            synchronized(this) { INSTANCE = db }
        }

        /** Test-only seam: clear the cached singleton so the next [getInstance] call rebuilds it. */
        @VisibleForTesting
        fun clearInstance() {
            synchronized(this) { INSTANCE = null }
        }

        private fun build(appContext: Context): LogDatabase {
            migrateFromPlaintextIfNeeded(appContext)
            // Idempotent: no-op if already loaded. Safety net in case SupportOpenHelperFactory
            // does not auto-load the native library on this device/SDK version.
            try { System.loadLibrary("sqlcipher") } catch (_: UnsatisfiedLinkError) { /* may already be resolved by factory */ }
            var passphrase = DbKeyProvider.get(appContext, DB_NAME)
            try {
                val factory = SupportOpenHelperFactory(passphrase)
                return Room.databaseBuilder(appContext, LogDatabase::class.java, DB_NAME)
                    .openHelperFactory(factory)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
            } finally {
                // Zero our local handle so the plaintext passphrase does not linger in heap
                // beyond what SQLCipher itself owns.
                java.util.Arrays.fill(passphrase, 0.toByte())
            }
        }

        /** First encrypted run: drop any pre-existing plaintext DB (SQLCipher would reject it). */
        private fun migrateFromPlaintextIfNeeded(appContext: Context) {
            val prefs = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            if (prefs.getBoolean(PREF_ENCRYPTED_V1, false)) return
            val dbFile = appContext.getDatabasePath(DB_NAME)
            if (dbFile.exists()) {
                if (!appContext.deleteDatabase(DB_NAME)) {
                    // Don't mark the migration complete; next launch will retry.
                    return
                }
            }
            if (!prefs.edit().putBoolean(PREF_ENCRYPTED_V1, true).commit()) {
                // Leave marker unset — we'll retry on next launch.
            }
        }
    }
}

