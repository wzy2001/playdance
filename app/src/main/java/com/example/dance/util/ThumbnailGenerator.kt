package com.example.dance.util

import android.content.Context
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Extracts a middle-frame JPEG thumbnail from a local video file into
 * filesDir/thumbnails/. Failures are swallowed and reported as null so that
 * an import never fails just because the thumbnail could not be generated.
 */
class ThumbnailGenerator(private val context: Context) {

    /** Returns the absolute path of the generated thumbnail, or null on failure. */
    suspend fun generate(videoPath: String): String? = withContext(Dispatchers.IO) {
        val retriever = MediaMetadataRetriever()
        var target: File? = null
        try {
            retriever.setDataSource(videoPath)
            val durationMs = retriever
                .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
            // getFrameAtTime expects MICROseconds; fall back to the codec's
            // default frame when the middle-frame request yields nothing.
            val frame = retriever.getFrameAtTime(
                durationMs * 1000 / 2,
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC
            ) ?: retriever.getFrameAtTime(-1) ?: return@withContext null

            val scaled = scaleDown(frame)
            val dir = File(context.filesDir, "thumbnails").apply { mkdirs() }
            val file = File(
                dir,
                "thumb_${System.currentTimeMillis()}_${File(videoPath).nameWithoutExtension}.jpg"
            )
            target = file
            file.outputStream().use { out ->
                scaled.compress(Bitmap.CompressFormat.JPEG, JPEG_QUALITY, out)
            }
            file.absolutePath
        } catch (_: Exception) {
            target?.delete()
            null
        } finally {
            retriever.release()
        }
    }

    private fun scaleDown(source: Bitmap): Bitmap {
        val longest = maxOf(source.width, source.height)
        if (longest <= MAX_DIMENSION) return source
        val scale = MAX_DIMENSION.toFloat() / longest
        return Bitmap.createScaledBitmap(
            source,
            (source.width * scale).toInt().coerceAtLeast(1),
            (source.height * scale).toInt().coerceAtLeast(1),
            true
        )
    }

    companion object {
        private const val MAX_DIMENSION = 640
        private const val JPEG_QUALITY = 80
    }
}
