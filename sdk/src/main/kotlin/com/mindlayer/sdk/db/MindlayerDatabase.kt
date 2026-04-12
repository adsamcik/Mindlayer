package com.mindlayer.sdk.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [ConversationEntity::class, TurnEntity::class, TurnPartEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class MindlayerDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun turnDao(): TurnDao

    companion object {
        private const val DB_NAME = "mindlayer_history.db"

        @Volatile
        private var instance: MindlayerDatabase? = null

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE conversations ADD COLUMN state TEXT NOT NULL DEFAULT 'READY'")
            }
        }

        fun getInstance(context: Context): MindlayerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MindlayerDatabase::class.java,
                    DB_NAME,
                )
                    .addMigrations(MIGRATION_1_2)
                    .fallbackToDestructiveMigrationFrom(1)
                    .build()
                    .also { instance = it }
            }
    }
}
