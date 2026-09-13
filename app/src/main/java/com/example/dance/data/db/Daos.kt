package com.example.dance.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Home-screen list item: a video plus whether it has any dubbing chunk.
 * Exposed by [VideoDao.observeLibrary] via Room's data-class projection.
 */
data class LibraryVideo(
    val id: Long,
    val title: String,
    val filePath: String,
    val durationMs: Long,
    val thumbnailPath: String?,
    val hasDubbing: Boolean
)

@Dao
interface VideoDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(video: Video): Long

    /**
     * Updates an existing row in place. Never use REPLACE-insert for updates:
     * with foreign keys on, REPLACE deletes the old row first, cascading away
     * the video's segments and recording chunks.
     */
    @Update
    suspend fun update(video: Video)

    @Query("SELECT * FROM videos WHERE thumbnailPath IS NULL")
    suspend fun getMissingThumbnails(): List<Video>

    @Query("SELECT * FROM videos ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<Video>>

    @Query(
        """
        SELECT videos.id AS id, videos.title AS title, videos.filePath AS filePath,
               videos.durationMs AS durationMs, videos.thumbnailPath AS thumbnailPath,
               EXISTS(
                   SELECT 1 FROM recording_chunks
                   WHERE recording_chunks.videoId = videos.id
               ) AS hasDubbing
        FROM videos
        ORDER BY videos.createdAt DESC
        """
    )
    fun observeLibrary(): Flow<List<LibraryVideo>>

    @Query("SELECT * FROM videos WHERE id = :id")
    suspend fun getById(id: Long): Video?

    @Delete
    suspend fun delete(video: Video)

    @Query("SELECT COUNT(*) FROM videos")
    suspend fun count(): Int
}

@Dao
interface SegmentDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(segment: Segment): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(segments: List<Segment>)

    @Update
    suspend fun update(segment: Segment)

    @Delete
    suspend fun delete(segment: Segment)

    @Query("SELECT * FROM segments WHERE videoId = :videoId ORDER BY \"index\" ASC")
    fun observeForVideo(videoId: Long): Flow<List<Segment>>

    @Query("SELECT * FROM segments WHERE videoId = :videoId ORDER BY \"index\" ASC")
    suspend fun getForVideo(videoId: Long): List<Segment>

    @Query("DELETE FROM segments WHERE videoId = :videoId")
    suspend fun deleteForVideo(videoId: Long)

    /** Atomically replaces the whole segment list of one video. */
    @Transaction
    suspend fun replaceForVideo(videoId: Long, segments: List<Segment>) {
        deleteForVideo(videoId)
        if (segments.isNotEmpty()) insertAll(segments)
    }
}

@Dao
interface RecordingChunkDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(chunk: RecordingChunk): Long

    @Query("SELECT * FROM recording_chunks WHERE videoId = :videoId ORDER BY blockStartMs ASC")
    fun observeForVideo(videoId: Long): Flow<List<RecordingChunk>>

    @Query("SELECT * FROM recording_chunks WHERE videoId = :videoId ORDER BY blockStartMs ASC")
    suspend fun getForVideo(videoId: Long): List<RecordingChunk>

    /** Chunks whose block range intersects [startMs, endMs). */
    @Query(
        """
        SELECT * FROM recording_chunks
        WHERE videoId = :videoId AND blockStartMs < :endMs AND blockEndMs > :startMs
        """
    )
    suspend fun getOverlapping(videoId: Long, startMs: Long, endMs: Long): List<RecordingChunk>

    @Delete
    suspend fun deleteAll(chunks: List<RecordingChunk>)

    /** Every audio path referenced by any chunk; used to sweep orphan files. */
    @Query("SELECT audioPath FROM recording_chunks")
    suspend fun getAllAudioPaths(): List<String>

    /**
     * Inserts [chunk], removing any chunk whose block overlaps it (re-recording a
     * block replaces it, DESIGN 2.5). Returns the replaced chunks so the caller
     * can delete their audio files.
     */
    @Transaction
    suspend fun replaceForBlock(chunk: RecordingChunk): List<RecordingChunk> {
        val replaced = getOverlapping(chunk.videoId, chunk.blockStartMs, chunk.blockEndMs)
        if (replaced.isNotEmpty()) deleteAll(replaced)
        insert(chunk)
        return replaced
    }
}
