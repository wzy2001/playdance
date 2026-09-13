package com.example.dance.data

import android.content.Context
import com.example.dance.data.db.LibraryVideo
import com.example.dance.data.db.RecordingChunk
import com.example.dance.data.db.Segment
import com.example.dance.data.db.Video
import kotlinx.coroutines.flow.Flow

/**
 * Single source of truth for library data. Wraps the Room DAOs so screens and
 * ViewModels never touch the database directly.
 */
class VideoRepository(private val db: com.example.dance.data.db.AppDatabase) {

    private val videoDao = db.videoDao()
    private val segmentDao = db.segmentDao()
    private val chunkDao = db.recordingChunkDao()

    fun observeLibrary(): Flow<List<LibraryVideo>> = videoDao.observeLibrary()

    fun observeVideos(): Flow<List<Video>> = videoDao.observeAll()

    suspend fun getVideo(id: Long): Video? = videoDao.getById(id)

    suspend fun addVideo(video: Video): Long = videoDao.insert(video)

    suspend fun updateVideo(video: Video) = videoDao.update(video)

    suspend fun getVideosWithoutThumbnail(): List<Video> = videoDao.getMissingThumbnails()

    suspend fun deleteVideo(video: Video) = videoDao.delete(video)

    suspend fun countVideos(): Int = videoDao.count()

    // Segments
    fun observeSegments(videoId: Long): Flow<List<Segment>> =
        segmentDao.observeForVideo(videoId)

    suspend fun addSegment(segment: Segment): Long = segmentDao.insert(segment)

    /** Atomically replaces the whole segment list of one video. */
    suspend fun replaceSegments(videoId: Long, segments: List<Segment>) =
        segmentDao.replaceForVideo(videoId, segments)

    suspend fun updateSegment(segment: Segment) = segmentDao.update(segment)

    suspend fun deleteSegment(segment: Segment) = segmentDao.delete(segment)

    suspend fun getSegments(videoId: Long): List<Segment> = segmentDao.getForVideo(videoId)

    // Recording chunks
    fun observeChunks(videoId: Long): Flow<List<RecordingChunk>> =
        chunkDao.observeForVideo(videoId)

    suspend fun getChunks(videoId: Long): List<RecordingChunk> = chunkDao.getForVideo(videoId)

    suspend fun addChunk(chunk: RecordingChunk): Long = chunkDao.insert(chunk)

    /** Every audio path referenced by any chunk; used to sweep orphan files. */
    suspend fun getAllChunkAudioPaths(): List<String> = chunkDao.getAllAudioPaths()

    /**
     * Saves a freshly recorded chunk, replacing any chunk whose block overlaps
     * it. Returns the replaced chunks; the caller deletes their audio files.
     */
    suspend fun replaceChunkForBlock(chunk: RecordingChunk): List<RecordingChunk> =
        chunkDao.replaceForBlock(chunk)
}
