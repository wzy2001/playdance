package com.example.dance.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import com.example.dance.data.VideoRepository
import com.example.dance.data.db.Video
import com.example.dance.data.db.VideoSourceType
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Metadata probed from a remote video URL, shown on the preview card. */
data class UrlVideoMetadata(
    val url: String,
    val title: String,
    val durationMs: Long,
    val cover: Bitmap?
)

/**
 * Fetches metadata for a remote video URL and downloads it as-is (no
 * transcoding) into the app-private directory, registering a [Video] row with
 * sourceType NETWORK_DOWNLOADED. Works with direct progressive links (mp4 etc.);
 * streaming manifests (HLS/DASH) are not supported.
 */
class NetworkVideoImporter(
    private val context: Context,
    private val repository: VideoRepository
) {

    /** Probes [url] remotely for title / duration / cover frame. */
    suspend fun fetchMetadata(url: String): Result<UrlVideoMetadata> =
        withContext(Dispatchers.IO) {
            runCatching {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(url, emptyMap())
                    val durationMs = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                        ?.toLongOrNull() ?: 0L
                    val title = retriever
                        .extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                        ?.takeIf { it.isNotBlank() }
                        ?: titleFromUrl(url)
                    // Middle frame as cover; frame extraction over HTTP may fail
                    // on servers without range support — cover is optional.
                    val cover = runCatching {
                        retriever.getFrameAtTime(
                            durationMs * 1000 / 2,
                            MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                        )
                    }.getOrNull()
                    UrlVideoMetadata(url, title, durationMs, cover)
                } finally {
                    retriever.release()
                }
            }
        }

    /**
     * Downloads [meta.url] into filesDir/videos/ and inserts the library row.
     * [onProgress] receives 0..1, or null when the total size is unknown.
     * Returns the new video id.
     */
    suspend fun download(
        meta: UrlVideoMetadata,
        onProgress: (Float?) -> Unit
    ): Result<Long> = withContext(Dispatchers.IO) {
        runCatching {
            val videoDir = File(context.filesDir, "videos").apply { mkdirs() }
            val target = uniqueFile(videoDir, fileNameFromUrl(meta.url))
            try {
                copyUrlToFile(meta.url, target, onProgress)
                val durationMs = readLocalDurationMs(target) ?: meta.durationMs
                val thumbnailPath = meta.cover?.let { saveThumbnail(it) }
                repository.addVideo(
                    Video(
                        title = meta.title,
                        filePath = target.absolutePath,
                        durationMs = durationMs,
                        thumbnailPath = thumbnailPath,
                        sourceType = VideoSourceType.NETWORK_DOWNLOADED
                    )
                )
            } catch (e: Exception) {
                target.delete()
                throw e
            }
        }
    }

    private fun copyUrlToFile(url: String, target: File, onProgress: (Float?) -> Unit) {
        val connection = URL(url).openConnection() as HttpURLConnection
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        try {
            val code = connection.responseCode
            if (code !in 200..299) error("HTTP $code")
            val total = connection.contentLengthLong
            connection.inputStream.use { input ->
                target.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var copied = 0L
                    var lastPercent = -1
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        output.write(buffer, 0, read)
                        copied += read
                        if (total > 0) {
                            // Throttle callbacks to whole-percent steps.
                            val percent = (copied * 100 / total).toInt()
                            if (percent != lastPercent) {
                                lastPercent = percent
                                onProgress(copied.toFloat() / total)
                            }
                        } else {
                            onProgress(null)
                        }
                    }
                }
            }
        } finally {
            connection.disconnect()
        }
    }

    private fun readLocalDurationMs(file: File): Long? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()
        } catch (_: Exception) {
            null
        } finally {
            retriever.release()
        }
    }

    private fun saveThumbnail(cover: Bitmap): String {
        val dir = File(context.filesDir, "thumbnails").apply { mkdirs() }
        val file = File(dir, "thumb_${System.currentTimeMillis()}.jpg")
        file.outputStream().use { cover.compress(Bitmap.CompressFormat.JPEG, 85, it) }
        return file.absolutePath
    }

    private fun titleFromUrl(url: String): String =
        fileNameFromUrl(url).substringBeforeLast('.')

    private fun fileNameFromUrl(url: String): String {
        val lastSegment = url.substringBefore('?').substringBefore('#')
            .trimEnd('/').substringAfterLast('/')
        val sanitized = lastSegment.replace(Regex("""[\\/:*?"<>|]"""), "_")
        return sanitized.ifBlank { "video_${System.currentTimeMillis()}.mp4" }
    }

    private fun uniqueFile(dir: File, name: String): File {
        val candidate = File(dir, name)
        if (!candidate.exists()) return candidate
        return File(dir, "${System.currentTimeMillis()}_$name")
    }
}
