package com.adsamcik.mindlayer.service.engine

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.adsamcik.mindlayer.service.security.DbKeyProvider
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [DeferredEntity::class], version = 3, exportSchema = false)
abstract class DeferredDatabase : RoomDatabase() {
    abstract fun deferredDao(): DeferredDao

    companion object {
        internal const val DB_NAME = "mindlayer-deferred.db"
        @Volatile private var INSTANCE: DeferredDatabase? = null

        val MIGRATION_2_3: Migration = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                addColumnIfMissing(db, "kind", "ALTER TABLE deferred_inference ADD COLUMN kind TEXT NOT NULL DEFAULT 'chat'")
                addColumnIfMissing(db, "blob_path", "ALTER TABLE deferred_inference ADD COLUMN blob_path TEXT")
                addColumnIfMissing(db, "blob_bytes", "ALTER TABLE deferred_inference ADD COLUMN blob_bytes INTEGER")
                addColumnIfMissing(db, "per_item_metadata_json", "ALTER TABLE deferred_inference ADD COLUMN per_item_metadata_json TEXT")
                db.execSQL("CREATE INDEX IF NOT EXISTS index_deferred_inference_uid_kind ON deferred_inference(uid, kind)")
            }
        }


        private fun addColumnIfMissing(db: SupportSQLiteDatabase, column: String, sql: String) {
            db.query("PRAGMA table_info(deferred_inference)").use { cursor ->
                val nameIndex = cursor.getColumnIndex("name")
                while (cursor.moveToNext()) {
                    if (cursor.getString(nameIndex) == column) return
                }
            }
            db.execSQL(sql)
        }

        fun getInstance(context: Context): DeferredDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context.applicationContext).also { INSTANCE = it }
            }

        private fun build(appContext: Context): DeferredDatabase {
            try { System.loadLibrary("sqlcipher") } catch (_: UnsatisfiedLinkError) { }
            val passphrase = DbKeyProvider.get(appContext, DB_NAME)
            try {
                return Room.databaseBuilder(appContext, DeferredDatabase::class.java, DB_NAME)
                    .openHelperFactory(SupportOpenHelperFactory(passphrase))
                    .addMigrations(MIGRATION_2_3)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
            } finally {
                java.util.Arrays.fill(passphrase, 0.toByte())
            }
        }
    }
}
