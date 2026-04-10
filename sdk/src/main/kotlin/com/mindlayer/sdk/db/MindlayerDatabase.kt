package com.mindlayer.sdk.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ConversationEntity::class, TurnEntity::class, TurnPartEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class MindlayerDatabase : RoomDatabase() {

    abstract fun conversationDao(): ConversationDao
    abstract fun turnDao(): TurnDao

    companion object {
        private const val DB_NAME = "mindlayer_history.db"

        @Volatile
        private var instance: MindlayerDatabase? = null

        fun getInstance(context: Context): MindlayerDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    MindlayerDatabase::class.java,
                    DB_NAME,
                )
                    .fallbackToDestructiveMigrationFrom(1)
                    .build()
                    .also { instance = it }
            }
    }
}
