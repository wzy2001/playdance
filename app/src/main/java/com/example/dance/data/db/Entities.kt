package com.example.dance.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A video in the library. Video files are stored in the app-private directory,
 * never in the shared media store.
 */
@Entity(tableName = "videos")
data class Video(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val filePath: String,
    val durationMs: Long,
    val thumbnailPath: String? = null,
    val sourceType: VideoSourceType,
    val createdAt: Long = System.currentTimeMillis()
)

enum class VideoSourceType {
    LOCAL,
    NETWORK_DOWNLOADED
}

/**
 * A single contiguous segment of a video's timeline, produced by split points.
 * Segment i covers [startMs, endMs); segment 0 starts at 0.
 */
@Entity(
    tableName = "segments",
    foreignKeys = [
        ForeignKey(
            entity = Video::class,
            parentColumns = ["id"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["videoId"])]
)
data class Segment(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: Long,
    val index: Int,
    val startMs: Long,
    val endMs: Long
)

/**
 * A recorded dubbing clip for one "block" (a set of contiguous segments).
 * Not physically merged with other chunks; playback composes them on the fly.
 */
@Entity(
    tableName = "recording_chunks",
    foreignKeys = [
        ForeignKey(
            entity = Video::class,
            parentColumns = ["id"],
            childColumns = ["videoId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["videoId"])]
)
data class RecordingChunk(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val videoId: Long,
    val audioPath: String,
    val blockStartMs: Long,
    val blockEndMs: Long,
    val recordedMs: Long,
    val createdAt: Long = System.currentTimeMillis()
)
