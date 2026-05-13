package com.adsamcik.mindlayer.service.engine

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.adsamcik.mindlayer.service.security.DbKeyProvider
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [DeferredEntity::class], version = 1, exportSchema = false)
abstract class DeferredDatabase : RoomDatabase() {
    abstract fun deferredDao(): DeferredDao

    companion object {
        private const val DB_NAME = "mindlayer-deferred.db"
        @Volatile private var INSTANCE: DeferredDatabase? = null

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
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .build()
            } finally {
                java.util.Arrays.fill(passphrase, 0.toByte())
            }
        }
    }
}
