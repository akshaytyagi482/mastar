package com.mastar.editor.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ProjectEntity::class,
        TrackEntity::class,
        ClipEntity::class,
        KeyframeEntity::class,
    ],
    version = 4,
    exportSchema = true,
)
abstract class MastarDatabase : RoomDatabase() {

    abstract fun projectDao(): ProjectDao
    abstract fun trackDao(): TrackDao
    abstract fun clipDao(): ClipDao
    abstract fun keyframeDao(): KeyframeDao

    companion object {
        fun create(context: Context): MastarDatabase =
            Room.databaseBuilder(context, MastarDatabase::class.java, "mastar.db")
                // Pre-1.0: schema is still moving fast; a reset beats a crash.
                // Proper migrations start once the schema stabilizes.
                .fallbackToDestructiveMigration()
                .build()
    }
}
