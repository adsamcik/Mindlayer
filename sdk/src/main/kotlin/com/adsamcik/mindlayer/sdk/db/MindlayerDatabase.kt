package com.adsamcik.mindlayer.sdk.db

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * The history DB is SQLCipher-encrypted with a random 32-byte passphrase stored wrapped in the
 * AndroidKeystore (see [DbKeyProvider]). Cross-install backup/restore will produce an
 * unreadable DB; this is acceptable pre-release.
 */
@Database(
    entities = [ConversationEntity::class, TurnEntity::class, TurnPartEntity::class],
    version = 3,
    exportSchema = false,
)
abstract class MindlayerDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun turnDao(): TurnDao

    companion object {
        private const val DB_NAME = "mindlayer_history.db"
        private const val PREF_FILE = "mindlayer_sdk_db_state"
        private const val PREF_ENCRYPTED_V1 = "db_encrypted_v1"

        @Volatile
        private var instance: MindlayerDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN state TEXT NOT NULL DEFAULT 'READY'")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_conversations_updatedAtMs " +
                        "ON conversations(updatedAtMs)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_turns_conversation_state_seq " +
                        "ON turns(conversationId, state, seq)",
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS index_turns_conversation_role_state_seq " +
                        "ON turns(conversationId, role, state, seq)",
                )
            }
        }

        fun getInstance(context: Context): MindlayerDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /** Test-only seam: inject an in-memory database in place of the encrypted singleton. */
        @VisibleForTesting
        fun setInstance(db: MindlayerDatabase) {
            synchronized(this) { instance = db }
        }

        /** Test-only seam: clear the cached singleton so the next call rebuilds it. */
        @VisibleForTesting
        fun clearInstance() {
            synchronized(this) { instance = null }
        }

        private fun build(appContext: Context): MindlayerDatabase {
            migrateFromPlaintextIfNeeded(appContext)
            try { System.loadLibrary("sqlcipher") } catch (_: UnsatisfiedLinkError) { }
            var passphrase = DbKeyProvider.get(appContext, DB_NAME)
            try {
                val factory = SupportOpenHelperFactory(passphrase)
                return Room.databaseBuilder(appContext, MindlayerDatabase::class.java, DB_NAME)
                    .openHelperFactory(factory)
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
            } finally {
                java.util.Arrays.fill(passphrase, 0.toByte())
            }
        }

        /**
         * First encrypted run: drop any pre-existing plaintext DB (SQLCipher would reject it).
         *
         * The marker lives in [SharedPreferences] (per-process cached) and may drift between
         * processes — we therefore must not trust it alone. Before deleting, peek the first
         * 16 bytes: if they match SQLite's plaintext magic, delete; otherwise the file is
         * already SQLCipher-encrypted (header randomised) and we skip the delete to avoid
         * wiping live encrypted data on a marker drift / corruption. Marker is written
         * **after** the conditional delete.
         */
        private fun migrateFromPlaintextIfNeeded(appContext: Context) {
            val prefs = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            if (prefs.getBoolean(PREF_ENCRYPTED_V1, false)) return
            val dbFile = appContext.getDatabasePath(DB_NAME)
            if (dbFile.exists()) {
                if (isPlaintextSqlite(dbFile)) {
                    if (!appContext.deleteDatabase(DB_NAME)) return
                } else {
                    android.util.Log.w(
                        "Mindlayer.SdkDb",
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
