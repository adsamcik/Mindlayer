package com.adsamcik.mindlayer.sdk.db

import android.content.Context
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
                    """
                    UPDATE conversations
                    SET systemPrompt = NULL,
                        toolsJson = NULL,
                        extraContextJson = NULL
                    """.trimIndent(),
                )
                db.execSQL("UPDATE turns SET textContent = NULL")
                db.query("SELECT name FROM sqlite_master WHERE type = 'table' AND name = 'turn_parts'")
                    .use { cursor ->
                        if (cursor.moveToFirst()) {
                            db.execSQL("UPDATE turn_parts SET text = NULL")
                        }
                    }
            }
        }

        fun getInstance(context: Context): MindlayerDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
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

        /** First encrypted run: drop any pre-existing plaintext DB (SQLCipher would reject it). */
        private fun migrateFromPlaintextIfNeeded(appContext: Context) {
            val prefs = appContext.getSharedPreferences(PREF_FILE, Context.MODE_PRIVATE)
            if (prefs.getBoolean(PREF_ENCRYPTED_V1, false)) return
            val dbFile = appContext.getDatabasePath(DB_NAME)
            if (dbFile.exists()) {
                if (!appContext.deleteDatabase(DB_NAME)) return
            }
            if (!prefs.edit().putBoolean(PREF_ENCRYPTED_V1, true).commit()) {
                // Leave marker unset — we'll retry on next launch.
            }
        }
    }
}

