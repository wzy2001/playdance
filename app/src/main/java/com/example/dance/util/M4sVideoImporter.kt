package com.example.dance.util

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMetadataRetriever
import android.media.MediaMuxer
import android.net.Uri
import com.example.dance.data.VideoRepository
import com.example.dance.data.db.Video
import com.example.dance.data.db.VideoSourceType
import java.io.File
import java.nio.ByteBuffer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Imports bilibili-client cache files (video.m4s + audio.m4s, DASH-separated
 * tracks) by remuxing them into one standard mp4 via MediaExtractor +
 * MediaMuxer. Pure remux — no transcoding, no quality loss. Bilibili prepends
 * junk bytes before the ftyp box to defeat naive players; they are stripped
 * while copying to a temp file.
 */
class M4sVideoImporter(
    private val context: Context,
    private val repository: VideoRepository,
    private val thumbnails: ThumbnailGenerator
) {

    private class Track(val extractor: MediaExtractor, val format: MediaFormat)

    /**
     * Imports one or two m4s files (video-only, or video + audio in either
     * order — tracks are detected by MIME, not file name). Returns the new
     * video id.
     */
    suspend fun import(uris: List<Uri>, title: String): Result<Long> =
        withContext(Dispatchers.IO) {
            runCatching {
                require(uris.size in 1..2) { "请选择 1 或 2 个 m4s 文件（video.m4s 与 audio.m4s）" }
                val tempDir = File(context.cacheDir, "m4s_import").apply { mkdirs() }
                val tempFiles = uris.mapIndexed { i, uri ->
                    File(tempDir, "src_$i.m4s").also { copyStripped(uri, it) }
                }
                try {
                    val videoDir = File(context.filesDir, "videos").apply { mkdirs() }
                    val target = File(videoDir, "m4s_${System.currentTimeMillis()}.mp4")
                    try {
                        remux(tempFiles, target)
                        val durationMs = readDurationMs(target) ?: 0L
                        repository.addVideo(
                            Video(
                                title = title.ifBlank { "B站缓存视频" },
                                filePath = target.absolutePath,
                                durationMs = durationMs,
                                thumbnailPath = thumbnails.generate(target.absolutePath),
                                sourceType = VideoSourceType.LOCAL
                            )
                        )
                    } catch (e: Exception) {
                        target.delete()
                        throw e
                    }
                } finally {
                    tempFiles.forEach { it.delete() }
                }
            }
        }

    /**
     * Copies [uri] to [target], dropping any junk bytes bilibili prepends
     * before the ftyp box. A clean file (ftyp at offset 4) is copied as-is.
     */
    private fun copyStripped(uri: Uri, target: File) {
        context.contentResolver.openInputStream(uri)?.use { input ->
            val head = ByteArray(64)
            var headLen = 0
            while (headLen < head.size) {
                val read = input.read(head, headLen, head.size - headLen)
                if (read < 0) break
                headLen += read
            }
            var start = 0
            for (i in 4..headLen - 4) {
                if (head[i] == 'f'.code.toByte() && head[i + 1] == 't'.code.toByte() &&
                    head[i + 2] == 'y'.code.toByte() && head[i + 3] == 'p'.code.toByte()
                ) {
                    start = i - 4
                    break
                }
            }
            target.outputStream().use { out ->
                out.write(head, start, headLen - start)
                input.copyTo(out)
            }
        } ?: error("无法读取所选文件")
    }

    /**
     * Remuxes the first video track and first audio track found across
     * [sources] into [target], interleaved by presentation time.
     */
    private fun remux(sources: List<File>, target: File) {
        val tracks = mutableListOf<Track>()
        var hasVideo = false
        var hasAudio = false
        try {
            for (file in sources) {
                val probe = MediaExtractor()
                val wanted = try {
                    probe.setDataSource(file.absolutePath)
                    (0 until probe.trackCount).filter { i ->
                        val mime = probe.getTrackFormat(i)
                            .getString(MediaFormat.KEY_MIME).orEmpty()
                        when {
                            mime.startsWith("video/") && !hasVideo -> { hasVideo = true; true }
                            mime.startsWith("audio/") && !hasAudio -> { hasAudio = true; true }
                            else -> false
                        }
                    }
                } finally {
                    probe.release()
                }
                for (i in wanted) {
                    val extractor = MediaExtractor()
                    extractor.setDataSource(file.absolutePath)
                    extractor.selectTrack(i)
                    tracks += Track(extractor, extractor.getTrackFormat(i))
                }
            }
            check(hasVideo) { "所选文件中没有视频轨，请确认包含 video.m4s" }

            val muxer = MediaMuxer(target.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            try {
                val muxerTrackIds = tracks.map { muxer.addTrack(it.format) }
                muxer.start()
                val buffer = ByteBuffer.allocateDirect(8 shl 20)
                val info = MediaCodec.BufferInfo()
                val done = BooleanArray(tracks.size)
                while (true) {
                    // Pick the track with the earliest pending sample so the
                    // output stays interleaved.
                    var next = -1
                    var nextTime = Long.MAX_VALUE
                    for (t in tracks.indices) {
                        if (done[t]) continue
                        val time = tracks[t].extractor.sampleTime
                        if (time < 0) {
                            done[t] = true
                        } else if (time < nextTime) {
                            nextTime = time
                            next = t
                        }
                    }
                    if (next < 0) break
                    val extractor = tracks[next].extractor
                    val size = extractor.readSampleData(buffer, 0)
                    if (size < 0) {
                        done[next] = true
                        continue
                    }
                    val flags = if (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC != 0) {
                        MediaCodec.BUFFER_FLAG_KEY_FRAME
                    } else {
                        0
                    }
                    info.set(0, size, extractor.sampleTime, flags)
                    muxer.writeSampleData(muxerTrackIds[next], buffer, info)
                    extractor.advance()
                }
                muxer.stop()
            } finally {
                muxer.release()
            }
        } finally {
            tracks.forEach { it.extractor.release() }
        }
    }

    private fun readDurationMs(file: File): Long? {
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
}
