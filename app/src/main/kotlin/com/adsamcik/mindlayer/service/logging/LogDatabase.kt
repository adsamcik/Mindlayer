package com.adsamcik.mindlayer.service.logging

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.mindlayer.service.security.DbKeyProvider
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * The log DB is SQLCipher-encrypted with a random 32-byte passphrase stored wrapped in the
 * AndroidKeystore (see [DbKeyProvider]). Cross-install backup/restore will produce an
 * unreadable DB; this is acceptable pre-release — logs are ephemeral and [fallbackToDestructiveMigration]
 * already allows resetting the table on schema changes.
 */
@Database(entities = [LogEntry::class], version = 2, exportSchema = false)
abstract class LogDatabase : RoomDatabase() {
    abstract fun logDao(): LogDao

    companion object {
        private const val DB_NAME = "mindlayer_logs"
        private const val PREF_FILE = "mindlayer_db_state"
        private const val PREF_ENCRYPTED_V1 = "db_encrypted_v1"

        @Volatile
        private var INSTANCE: LogDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_usage_logs_request_timestamp " +
                        "ON usage_logs(requestId, timestampMs)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_usage_logs_session_timestamp " +
                        "ON usage_logs(sessionId, timestampMs)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_usage_logs_category_event_timestamp " +
                        "ON usage_logs(category, event, timestampMs)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_usage_logs_backend_event_timestamp " +
                        "ON usage_logs(backend, event, timestampMs)",
                )
            }
        }

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
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
            } finally {
                // Zero our local handle so the plaintext passphrase does not linger in heap
                // beyond what SQLCipher itself owns.
                java.util.Arrays.fill(passphrase, 0.toByte())
            }
        }

        /**
         * First encrypted run: drop any pre-existing plaintext DB (SQLCipher would reject it).
         *
         * The migration marker lives in [SharedPreferences] (per-process cached) and may drift
         * between processes — we therefore must not trust the marker alone. Before deleting,
         * peek the first 16 bytes: if they match SQLite's plaintext magic header
         * (`"SQLite format 3\u0000"`) the DB is plaintext and safe to delete; otherwise it
         * is already SQLCipher-encrypted (header is randomised) and we skip the delete to
         * avoid wiping live encrypted data on a marker drift / corruption. The marker is
         * written **after** the conditional delete.
         */
        private fun migrateFromPlaintextIfNeeded(appContext: Context) {
            val prefs = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            if (prefs.getBoolean(PREF_ENCRYPTED_V1, false)) return
            val dbFile = appContext.getDatabasePath(DB_NAME)
            if (dbFile.exists()) {
                if (isPlaintextSqlite(dbFile)) {
                    if (!appContext.deleteDatabase(DB_NAME)) {
                        return
                    }
                } else {
                    MindlayerLog.w(
                        "LogDatabase",
                        "Migration marker '$PREF_ENCRYPTED_V1' was unset but $DB_NAME does not " +
                            "have a plaintext SQLite header — assuming already encrypted and skipping delete.",
                    )
                }
            }
            if (!prefs.edit().putBoolean(PREF_ENCRYPTED_V1, true).commit()) {
                // Leave marker unset — we'll retry on next launch.
            }
        }

        /**
         * Returns true iff [file] starts with the canonical 16-byte SQLite plaintext header
         * `"SQLite format 3\u0000"`. Returns false on any read error or short file.
         */
        @VisibleForTesting
        internal fun isPlaintextSqlite(file: java.io.File): Boolean {
            if (!file.exists() || !file.isFile) return false
            return try {
                java.io.RandomAccessFile(file, "r").use { raf ->
                    if (raf.length() < SQLITE_MAGIC.size) return false
                    val buf = ByteArray(SQLITE_MAGIC.size)
                    raf.readFully(buf)
                    buf.contentEquals(SQLITE_MAGIC)
                }
            } catch (_: java.io.IOException) {
                false
            } catch (_: SecurityException) {
                false
            }
        }

        /** SQLite's plaintext file magic, exactly 16 bytes including trailing NUL. */
        @VisibleForTesting
        internal val SQLITE_MAGIC: ByteArray =
            "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    }
}
