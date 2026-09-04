package com.example.dance.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [Video::class, Segment::class, RecordingChunk::class],
    version = 1,
    exportSchema = true
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun videoDao(): VideoDao
    abstract fun segmentDao(): SegmentDao
    abstract fun recordingChunkDao(): RecordingChunkDao

    companion object {
        private const val DB_NAME = "dance.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .build()
    }
}
