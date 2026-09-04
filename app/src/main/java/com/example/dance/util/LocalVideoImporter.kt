package com.example.dance.util

import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.OpenableColumns
import com.example.dance.data.VideoRepository
import com.example.dance.data.db.Video
import com.example.dance.data.db.VideoSourceType
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Copies a user-picked local video into the app-private directory and records a
 * [Video] row. All source files live in filesDir so the system media store never
 * sees them.
 */
class LocalVideoImporter(
    private val context: Context,
    private val repository: VideoRepository
) {

    /**
     * Copies [uri] into filesDir/videos/ and returns the new video id.
     * Duration and display name are read from the content provider.
     */
    suspend fun import(uri: Uri): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val name = queryDisplayName(uri) ?: "video_${System.currentTimeMillis()}"
            val videoDir = File(context.filesDir, "videos").apply { mkdirs() }
            val target = File(videoDir, "$name")

            context.contentResolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { input.copyTo(it) }
            } ?: error("无法读取所选视频")

            val durationMs = queryDurationMs(uri) ?: 0L
            val video = Video(
                title = name.substringBeforeLast('.'),
                filePath = target.absolutePath,
                durationMs = durationMs,
                thumbnailPath = null,
                sourceType = VideoSourceType.LOCAL
            )
            repository.addVideo(video)
        }
    }

    private fun queryDisplayName(uri: Uri): String? =
        queryColumn(uri, OpenableColumns.DISPLAY_NAME) as? String

    private fun queryDurationMs(uri: Uri): Long? =
        queryColumn(uri, MediaStore.Video.Media.DURATION)?.toString()?.toLongOrNull()

    private fun queryColumn(uri: Uri, column: String): Any? {
        context.contentResolver.query(
            uri, arrayOf(column), null, null, null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return when (column) {
                    OpenableColumns.DISPLAY_NAME -> cursor.getString(0)
                    MediaStore.Video.Media.DURATION -> cursor.getLong(0)
                    else -> cursor.getString(0)
                }
            }
        }
        return null
    }
}
