package com.example.dance.ui.player

import android.media.MediaMetadataRetriever
import android.media.MediaRecorder
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.annotation.OptIn
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.VideoSize
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SilenceMediaSource
import com.example.dance.DanceApplication
import com.example.dance.data.db.RecordingChunk
import com.example.dance.data.db.Segment
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Which long-press gesture is currently held (DESIGN 2.7). */
enum class PlayerGesture { SLOW_MOTION, REVERSE }

/** UI state for the player screen (Milestone 2 playback + Milestone 3 segmentation). */
data class PlayerUiState(
    val title: String = "",
    val durationMs: Long = 0L,
    val positionMs: Long = 0L,
    val isPlaying: Boolean = false,
    val speed: Float = 1.0f,
    val loopEnabled: Boolean = true,
    /** True when the source video is wider than tall (after applying rotation metadata). */
    val isLandscapeVideo: Boolean = false,
    val videoMissing: Boolean = false,
    /** Interior split points (ms), sorted ascending; boundaries 0 and duration are implicit. */
    val splitPoints: List<Long> = emptyList(),
    /** Index into [splitPoints] of the split being edited (nudge/delete), or null. */
    val selectedSplitIndex: Int? = null,
    /** Selected block = contiguous segment index range, or null when no block is selected. */
    val blockStartIndex: Int? = null,
    val blockEndIndex: Int? = null,
    /** Active long-press gesture (slow motion / simulated reverse), or null. */
    val activeGesture: PlayerGesture? = null,
    /** True while a dubbing chunk is being recorded (DESIGN 2.5). */
    val isRecording: Boolean = false,
    /** Elapsed recording time for the timer display; 0 when not recording. */
    val recordingElapsedMs: Long = 0L,
    /** Dubbing playback switch (DESIGN 2.6): off = original sound only. */
    val dubbingEnabled: Boolean = true,
    /** Master player volume (original sound slider), 0..1. */
    val originalVolume: Float = 1f,
    /** Dubbing player volume (dubbing slider), 0..1. */
    val dubbingVolume: Float = 1f
) {
    /** Segment boundaries including both ends: [0, split..., duration]. */
    val boundaries: List<Long>
        get() = listOf(0L) + splitPoints + listOf(durationMs.coerceAtLeast(1L))
}

/**
 * Owns the main ExoPlayer (video + original audio, master clock per DESIGN 5.2)
 * and the split-point / block-selection model (DESIGN 2.4 / 5.4).
 * The player lives in the ViewModel so it survives configuration changes; it is
 * released in [onCleared].
 */
class PlayerViewModel(
    private val app: DanceApplication,
    private val videoId: Long
) : ViewModel() {

    /** Available speed steps, DESIGN 2.6: 0.5–1.0 step 0.1. */
    val speedSteps: List<Float> = listOf(0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f)

    val player: ExoPlayer = ExoPlayer.Builder(app).build()

    /**
     * Audio-only dubbing player (DESIGN 5.2). It plays the video's dubbing
     * track (chunks + silence gaps) and is position-slaved to [player].
     */
    private val dubbingPlayer: ExoPlayer = ExoPlayer.Builder(app).build()

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    // Dubbing timeline state. Must be declared BEFORE init: viewModelScope uses
    // Dispatchers.Main.immediate, so the ticker coroutine launched in init runs
    // its first iteration (including syncDubbing) before later property
    // initializers have executed — reference fields declared below init would
    // still be null at that point.
    private var latestChunks: List<RecordingChunk> = emptyList()

    /**
     * Absolute video-time start (ms) of each item in the dubbing playlist.
     * Empty when the video has no playable dubbing chunks.
     */
    private var dubItemStartsMs: LongArray = LongArray(0)
    private var dubTimelineDurationMs = 0L
    private var lastDubCorrectionRealtime = 0L

    init {
        player.repeatMode = Player.REPEAT_MODE_ONE
        // Surface dubbing playback failures in logcat; the dubbing player is
        // otherwise silent about errors (no UI of its own).
        dubbingPlayer.addListener(object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                Log.w(TAG, "Dubbing player error", error)
            }
        })
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _uiState.update { it.copy(isPlaying = isPlaying) }
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_READY) {
                    // Player-reported duration is more accurate than the DB value.
                    val duration = player.duration
                    if (duration > 0) {
                        if (duration != _uiState.value.durationMs) {
                            _uiState.update { it.copy(durationMs = duration) }
                        }
                        // Rebuild whenever the dubbing timeline was built against
                        // a different duration. Comparing against the timeline's
                        // own duration (not the UI state) covers the race where
                        // chunks are emitted before the video row loads: the
                        // timeline is then unbuilt (0) even though the UI
                        // duration already matches the player's.
                        if (duration != dubTimelineDurationMs) rebuildDubbingSources()
                    }
                }
            }

            override fun onPositionDiscontinuity(
                oldPosition: Player.PositionInfo,
                newPosition: Player.PositionInfo,
                reason: Int
            ) {
                // Any master jump (user seek, block-loop restart, whole-video
                // repeat wrap) re-anchors the dubbing player (DESIGN 5.2).
                if (dubItemStartsMs.isNotEmpty()) seekDubbingTo(newPosition.positionMs)
            }

            override fun onVideoSizeChanged(videoSize: VideoSize) {
                if (videoSize.width == 0 || videoSize.height == 0) return
                // Rotation metadata may be unapplied (e.g. TextureView path); normalize.
                val rotated = videoSize.unappliedRotationDegrees == 90 ||
                    videoSize.unappliedRotationDegrees == 270
                val width = if (rotated) videoSize.height else videoSize.width
                val height = if (rotated) videoSize.width else videoSize.height
                _uiState.update { it.copy(isLandscapeVideo = width > height) }
            }
        })

        viewModelScope.launch {
            val video = app.videoRepository.getVideo(videoId)
            if (video == null || !File(video.filePath).exists()) {
                _uiState.update { it.copy(videoMissing = true) }
                return@launch
            }
            // Restore persisted split points (interior boundaries of stored segments).
            val stored = app.videoRepository.getSegments(videoId)
            val splits = if (stored.size >= 2) {
                stored.sortedBy { it.index }.dropLast(1).map { it.endMs }
            } else {
                emptyList()
            }
            _uiState.update {
                it.copy(title = video.title, durationMs = video.durationMs, splitPoints = splits)
            }
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(video.filePath))))
            player.prepare()
            player.play()
        }

        // Rebuild the dubbing timeline whenever the chunk set changes (initial
        // load and every time a new recording is saved).
        viewModelScope.launch {
            app.videoRepository.observeChunks(videoId).collect { chunks ->
                latestChunks = chunks
                rebuildDubbingSources()
            }
        }

        // Position ticker; drives the timeline, block loop, recording auto-stop
        // and dubbing follower.
        viewModelScope.launch {
            while (isActive) {
                enforceBlockLoop()
                autoStopRecordingAtBlockEnd()
                syncDubbing()
                _uiState.update {
                    it.copy(
                        positionMs = player.currentPosition.coerceAtLeast(0L),
                        recordingElapsedMs = if (it.isRecording) {
                            SystemClock.elapsedRealtime() - recordStartRealtime
                        } else 0L
                    )
                }
                delay(POSITION_POLL_MS)
            }
        }
    }

    fun togglePlayPause() {
        if (_uiState.value.isRecording) return
        if (player.isPlaying) player.pause() else player.play()
    }

    fun seekTo(positionMs: Long) {
        if (_uiState.value.isRecording) return
        player.seekTo(positionMs.coerceIn(0L, _uiState.value.durationMs))
        _uiState.update { it.copy(positionMs = positionMs) }
    }

    /** Applies [speed] to both players so video and dubbing stay in sync (DESIGN 2.6). */
    fun setSpeed(speed: Float) {
        if (_uiState.value.isRecording) return
        player.setPlaybackSpeed(speed)
        dubbingPlayer.setPlaybackSpeed(speed)
        _uiState.update { it.copy(speed = speed) }
    }

    fun toggleLoop() {
        if (_uiState.value.isRecording) return
        val enabled = !_uiState.value.loopEnabled
        player.repeatMode = if (enabled) Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF
        _uiState.update { it.copy(loopEnabled = enabled) }
    }

    // --- Segmentation (DESIGN 2.4 / 5.4) ---

    /** Inserts a split point at the current playback position; no-op if too close to a boundary. */
    fun insertSplitAtCurrentPosition() {
        val s = _uiState.value
        if (s.isRecording || s.durationMs <= 0) return
        val pos = player.currentPosition.coerceIn(0L, s.durationMs)
        if (s.boundaries.any { abs(it - pos) < MIN_SEGMENT_MS }) return
        val newSplits = (s.splitPoints + pos).sorted()
        _uiState.update {
            it.copy(
                splitPoints = newSplits,
                selectedSplitIndex = newSplits.indexOf(pos),
                // Segment indices shift; drop the block selection to stay consistent.
                blockStartIndex = null,
                blockEndIndex = null
            )
        }
        persistSegments()
    }

    fun selectSplit(index: Int?) {
        // Clearing the selection is always allowed; selecting is blocked while recording.
        if (index != null && _uiState.value.isRecording) return
        _uiState.update { it.copy(selectedSplitIndex = index) }
        // Park the playhead on the split so the user can check alignment.
        if (index != null) {
            _uiState.value.splitPoints.getOrNull(index)?.let { seekTo(it) }
        }
    }

    /** Deletes the selected split point, merging its two adjacent segments. */
    fun deleteSelectedSplit() {
        val s = _uiState.value
        if (s.isRecording) return
        val idx = s.selectedSplitIndex ?: return
        if (idx !in s.splitPoints.indices) return
        val newSplits = s.splitPoints.toMutableList().apply { removeAt(idx) }
        _uiState.update {
            it.copy(
                splitPoints = newSplits,
                selectedSplitIndex = null,
                blockStartIndex = null,
                blockEndIndex = null
            )
        }
        persistSegments()
    }

    /** Nudges the selected split by [deltaMs] (±10 / ±100), clamped to keep segments ≥ MIN_SEGMENT_MS. */
    fun nudgeSelectedSplit(deltaMs: Long) {
        val s = _uiState.value
        if (s.isRecording) return
        val idx = s.selectedSplitIndex ?: return
        if (idx !in s.splitPoints.indices) return
        val lower = (if (idx == 0) 0L else s.splitPoints[idx - 1]) + MIN_SEGMENT_MS
        val upper =
            (if (idx == s.splitPoints.lastIndex) s.durationMs else s.splitPoints[idx + 1]) -
                MIN_SEGMENT_MS
        if (lower > upper) return
        val moved = (s.splitPoints[idx] + deltaMs).coerceIn(lower, upper)
        if (moved == s.splitPoints[idx]) return
        val newSplits = s.splitPoints.toMutableList().apply { this[idx] = moved }
        _uiState.update { it.copy(splitPoints = newSplits) }
        persistSegments()
        // Follow the split with the playhead so the beat alignment is audible/visible.
        seekTo(moved)
    }

    /**
     * Block selection on segment tap: no block → select the tapped segment; tap
     * outside the block → extend to cover it; tap inside a multi-segment block →
     * collapse to the tapped segment; tap the only selected segment → clear.
     */
    fun onSegmentTapped(index: Int) {
        val s = _uiState.value
        if (s.isRecording) return
        if (index !in 0 until s.boundaries.size - 1) return
        val start = s.blockStartIndex
        val end = s.blockEndIndex
        val newRange: Pair<Int, Int>? = when {
            start == null || end == null -> index to index
            index in start..end -> if (start == end) null else index to index
            else -> min(start, index) to max(end, index)
        }
        _uiState.update { it.copy(blockStartIndex = newRange?.first, blockEndIndex = newRange?.second) }
        // Jump to the block start so the loop begins from the block head.
        newRange?.let { seekTo(s.boundaries[it.first]) }
    }

    fun clearBlock() {
        _uiState.update { it.copy(blockStartIndex = null, blockEndIndex = null) }
    }

    // --- Gestures (DESIGN 2.7 / 5.5) ---

    private var preGestureSpeed: Float? = null
    private var resumeAfterReverse = false
    private var reverseJob: Job? = null

    /** Long-press right: temporary 0.5x; the pre-gesture speed is restored on release. */
    fun startSlowMotionGesture() {
        val s = _uiState.value
        if (s.activeGesture != null || s.isRecording) return
        preGestureSpeed = s.speed
        player.setPlaybackSpeed(SLOW_MOTION_SPEED)
        dubbingPlayer.setPlaybackSpeed(SLOW_MOTION_SPEED)
        _uiState.update { it.copy(activeGesture = PlayerGesture.SLOW_MOTION) }
    }

    /**
     * Long-press left: simulated 2x reverse (DESIGN 5.5). Media3 does not support
     * negative playback speed, so playback pauses and the position is seeked
     * backwards on a timer (1000ms back every 500ms = 2x reverse rate).
     */
    fun startReverseGesture() {
        val s = _uiState.value
        if (s.activeGesture != null || s.isRecording) return
        resumeAfterReverse = player.isPlaying
        player.pause()
        _uiState.update { it.copy(activeGesture = PlayerGesture.REVERSE) }
        reverseJob = viewModelScope.launch {
            while (isActive) {
                val target = (player.currentPosition - REVERSE_STEP_MS).coerceAtLeast(0L)
                player.seekTo(target)
                _uiState.update { it.copy(positionMs = target) }
                if (target == 0L) break
                delay(REVERSE_INTERVAL_MS)
            }
        }
    }

    /** Releases whichever long-press gesture is held and restores normal playback. */
    fun endLongPressGesture() {
        when (_uiState.value.activeGesture) {
            PlayerGesture.SLOW_MOTION -> {
                preGestureSpeed?.let {
                    player.setPlaybackSpeed(it)
                    dubbingPlayer.setPlaybackSpeed(it)
                }
                preGestureSpeed = null
            }
            PlayerGesture.REVERSE -> {
                reverseJob?.cancel()
                reverseJob = null
                if (resumeAfterReverse) player.play()
            }
            null -> return
        }
        _uiState.update { it.copy(activeGesture = null) }
    }

    // --- Dubbing recording (DESIGN 2.5 / 5.3) ---

    private var recorder: MediaRecorder? = null
    private var recordFile: File? = null
    private var recordBlockStartMs = 0L
    private var recordBlockEndMs = 0L
    private var recordStartRealtime = 0L
    private var preRecordRepeatMode = Player.REPEAT_MODE_ONE

    /**
     * Starts recording a dubbing chunk for the selected block (or the whole
     * video when no block is selected). The block plays once at 1x with the
     * original sound audible; the microphone records voice only. Callers must
     * have obtained RECORD_AUDIO first.
     */
    fun startRecording() {
        val s = _uiState.value
        if (s.isRecording || s.durationMs <= 0 || s.videoMissing) return
        val b = s.boundaries
        val start = s.blockStartIndex?.let { b.getOrNull(it) } ?: 0L
        val end = s.blockEndIndex?.let { b.getOrNull(it + 1) } ?: s.durationMs

        val dir = File(app.filesDir, "recordings").apply { mkdirs() }
        val file = File(dir, "rec_${videoId}_${start}_${System.currentTimeMillis()}.m4a")

        val newRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(app)
        } else {
            @Suppress("DEPRECATION") MediaRecorder()
        }
        try {
            newRecorder.setAudioSource(MediaRecorder.AudioSource.MIC)
            newRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            newRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
            newRecorder.setAudioSamplingRate(RECORD_SAMPLE_RATE)
            newRecorder.setAudioEncodingBitRate(RECORD_BIT_RATE)
            newRecorder.setOutputFile(file.absolutePath)
            newRecorder.prepare()
            newRecorder.start()
        } catch (e: Exception) {
            newRecorder.release()
            file.delete()
            return
        }

        recorder = newRecorder
        recordFile = file
        recordBlockStartMs = start
        recordBlockEndMs = end
        recordStartRealtime = SystemClock.elapsedRealtime()
        preRecordRepeatMode = player.repeatMode

        // Recording locks playback to 1x, no loop, from the block start (DESIGN 5.3).
        player.pause()
        player.setPlaybackSpeed(1.0f)
        dubbingPlayer.setPlaybackSpeed(1.0f)
        player.repeatMode = Player.REPEAT_MODE_OFF
        player.seekTo(start)
        player.play()
        _uiState.update { it.copy(isRecording = true, recordingElapsedMs = 0L) }
    }

    /** Stops recording (manual stop button or auto-stop at block end) and persists the chunk. */
    fun stopRecording() {
        val s = _uiState.value
        if (!s.isRecording) return
        val activeRecorder = recorder ?: return
        val file = recordFile
        recorder = null
        recordFile = null

        player.pause()
        // Restore the pre-recording speed/loop; park at the block start for review.
        player.setPlaybackSpeed(s.speed)
        dubbingPlayer.setPlaybackSpeed(s.speed)
        player.repeatMode = preRecordRepeatMode
        player.seekTo(recordBlockStartMs)
        _uiState.update { it.copy(isRecording = false, recordingElapsedMs = 0L) }

        val elapsed = SystemClock.elapsedRealtime() - recordStartRealtime
        val stopped = try {
            activeRecorder.stop()
            true
        } catch (e: RuntimeException) {
            // Stopped with no valid data (e.g. immediately after start): discard.
            false
        } finally {
            activeRecorder.release()
        }
        if (!stopped || file == null || !file.exists()) {
            file?.delete()
            return
        }

        val blockStart = recordBlockStartMs
        val blockEnd = recordBlockEndMs
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val recordedMs = readAudioDurationMs(file, fallback = elapsed)
                    .coerceAtMost(blockEnd - blockStart)
                val chunk = RecordingChunk(
                    videoId = videoId,
                    audioPath = file.absolutePath,
                    blockStartMs = blockStart,
                    blockEndMs = blockEnd,
                    recordedMs = recordedMs
                )
                val replaced = app.videoRepository.replaceChunkForBlock(chunk)
                replaced.forEach { File(it.audioPath).delete() }
            }
        }
    }

    /** Auto-stop when the recorded block has played through (DESIGN 2.5). */
    private fun autoStopRecordingAtBlockEnd() {
        val s = _uiState.value
        if (!s.isRecording) return
        if (player.currentPosition >= recordBlockEndMs ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopRecording()
        }
    }

    /** Reads the true duration of the recorded m4a; falls back to wall-clock time. */
    private fun readAudioDurationMs(file: File, fallback: Long): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: fallback
        } catch (e: Exception) {
            fallback
        } finally {
            retriever.release()
        }
    }

    // --- Dubbing playback (DESIGN 2.6 / 5.2) ---
    // (Timeline state fields are declared above init; see the note there.)

    /** Dubbing on/off (DESIGN 2.6): off = original sound only. */
    fun toggleDubbing() {
        val s = _uiState.value
        if (s.isRecording) return
        val enabled = !s.dubbingEnabled
        dubbingPlayer.volume = if (enabled) s.dubbingVolume else 0f
        if (!enabled) dubbingPlayer.pause()
        _uiState.update { it.copy(dubbingEnabled = enabled) }
    }

    fun setOriginalVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        player.volume = v
        _uiState.update { it.copy(originalVolume = v) }
    }

    fun setDubbingVolume(volume: Float) {
        val v = volume.coerceIn(0f, 1f)
        if (_uiState.value.dubbingEnabled) dubbingPlayer.volume = v
        _uiState.update { it.copy(dubbingVolume = v) }
    }

    /**
     * Builds the dubbing track as a sequential playlist covering the whole
     * video: each chunk's audible part [blockStart, blockStart + recordedMs)
     * sits at its absolute position, gaps are filled with silence (DESIGN 3 /
     * 5.2). Spanning the whole video (rather than only the selected block)
     * makes the dubbing offset equal to the master position, so block changes
     * need no rebuild and loop restarts are handled by mirroring the master's
     * restart seek in onPositionDiscontinuity.
     */
    @OptIn(UnstableApi::class)
    private fun rebuildDubbingSources() {
        val duration = _uiState.value.durationMs
        if (duration <= 0) return
        val usable = latestChunks
            .filter { it.recordedMs > 0 && File(it.audioPath).exists() }
            .sortedBy { it.blockStartMs }
        if (usable.isEmpty()) {
            dubItemStartsMs = LongArray(0)
            // Record the duration the (empty) timeline was built against so
            // STATE_READY does not keep re-triggering a no-op rebuild.
            dubTimelineDurationMs = duration
            dubbingPlayer.clearMediaItems()
            Log.d(TAG, "Dubbing timeline: no usable chunks (duration=$duration)")
            return
        }
        val mediaSourceFactory = DefaultMediaSourceFactory(app)
        val sources = mutableListOf<MediaSource>()
        val starts = mutableListOf<Long>()
        var cursor = 0L
        for (chunk in usable) {
            // Chunks are non-overlapping by the replace semantics; the
            // coercions only guard against anomalous data.
            val start = chunk.blockStartMs.coerceAtLeast(cursor)
            if (start >= duration) break
            val audibleEnd = chunk.blockStartMs +
                min(chunk.recordedMs, chunk.blockEndMs - chunk.blockStartMs)
            val end = audibleEnd.coerceAtMost(duration)
            if (end <= start) continue
            if (start > cursor) {
                starts += cursor
                sources += SilenceMediaSource((start - cursor) * 1000)
            }
            // Clip in the audio file's own timeline, where 0 == blockStartMs.
            val item = MediaItem.Builder()
                .setUri(Uri.fromFile(File(chunk.audioPath)))
                .setClippingConfiguration(
                    MediaItem.ClippingConfiguration.Builder()
                        .setStartPositionMs(start - chunk.blockStartMs)
                        .setEndPositionMs(end - chunk.blockStartMs)
                        .build()
                )
                .build()
            starts += start
            sources += mediaSourceFactory.createMediaSource(item)
            cursor = end
        }
        if (cursor < duration) {
            starts += cursor
            sources += SilenceMediaSource((duration - cursor) * 1000)
        }
        dubItemStartsMs = starts.toLongArray()
        dubTimelineDurationMs = duration
        dubbingPlayer.playWhenReady = false
        dubbingPlayer.setMediaSources(sources)
        dubbingPlayer.prepare()
        seekDubbingTo(player.currentPosition)
        Log.d(TAG, "Dubbing timeline rebuilt: ${sources.size} items, duration=$duration")
    }

    /** Seeks the dubbing playlist to the given absolute video position. */
    private fun seekDubbingTo(videoPositionMs: Long) {
        val starts = dubItemStartsMs
        if (starts.isEmpty()) return
        val pos = videoPositionMs.coerceIn(0L, dubTimelineDurationMs - 1)
        val index = starts.indexOfLast { it <= pos }.coerceAtLeast(0)
        dubbingPlayer.seekTo(index, pos - starts[index])
        lastDubCorrectionRealtime = SystemClock.elapsedRealtime()
    }

    /**
     * Ticker-driven follower: mirrors the master's play/pause state onto the
     * dubbing player and corrects drift beyond [DUB_SYNC_TOLERANCE_MS].
     * Discontinuities (seeks, loop restarts) are handled eagerly in
     * onPositionDiscontinuity; recording and the reverse gesture keep the
     * dubbing player paused (DESIGN 5.5).
     */
    private fun syncDubbing() {
        if (dubItemStartsMs.isEmpty()) return
        val s = _uiState.value
        dubbingPlayer.volume = if (s.dubbingEnabled) s.dubbingVolume else 0f
        val shouldPlay = s.dubbingEnabled && !s.isRecording && player.isPlaying
        if (!shouldPlay) {
            if (dubbingPlayer.isPlaying) dubbingPlayer.pause()
            return
        }
        val masterPos = player.currentPosition
        val dubPos = dubItemStartsMs.getOrElse(dubbingPlayer.currentMediaItemIndex) { 0L } +
            dubbingPlayer.currentPosition
        // Rate-limit corrective seeks so a briefly buffering dubbing player is
        // not seeked again on every tick.
        val sinceCorrection = SystemClock.elapsedRealtime() - lastDubCorrectionRealtime
        if (abs(dubPos - masterPos) > DUB_SYNC_TOLERANCE_MS &&
            sinceCorrection > DUB_CORRECTION_MIN_INTERVAL_MS
        ) {
            seekDubbingTo(masterPos)
        }
        if (!dubbingPlayer.isPlaying) dubbingPlayer.play()
    }

    /** While a block is selected and loop is on, keep playback inside [blockStart, blockEnd). */
    private fun enforceBlockLoop() {
        val s = _uiState.value
        val start = s.blockStartIndex ?: return
        val end = s.blockEndIndex ?: return
        if (!s.loopEnabled || s.durationMs <= 0) return
        // Simulated reverse may seek before the block start; don't fight it.
        // Recording plays the block once through without loop enforcement.
        if (s.activeGesture == PlayerGesture.REVERSE || s.isRecording) return
        val b = s.boundaries
        val blockStartMs = b.getOrNull(start) ?: return
        val blockEndMs = b.getOrNull(end + 1) ?: return
        val pos = player.currentPosition
        if (pos < blockStartMs || pos >= blockEndMs) {
            player.seekTo(blockStartMs)
        }
    }

    /** Persists the current split model as the video's full segment list (empty when unsplit). */
    private fun persistSegments() {
        val s = _uiState.value
        if (s.durationMs <= 0) return
        val segments = if (s.splitPoints.isEmpty()) {
            emptyList()
        } else {
            s.boundaries.zipWithNext().mapIndexed { i, (startMs, endMs) ->
                Segment(videoId = videoId, index = i, startMs = startMs, endMs = endMs)
            }
        }
        viewModelScope.launch {
            app.videoRepository.replaceSegments(videoId, segments)
        }
    }

    override fun onCleared() {
        // If the screen is destroyed mid-recording, discard the partial take
        // (proper interruption recovery is Milestone 8 scope).
        recorder?.let {
            try {
                it.stop()
            } catch (e: RuntimeException) {
                // No valid data; nothing to keep.
            }
            it.release()
        }
        recorder = null
        recordFile?.delete()
        recordFile = null
        dubbingPlayer.release()
        player.release()
    }

    companion object {
        private const val TAG = "PlayerViewModel"

        private const val POSITION_POLL_MS = 200L

        /** Minimum allowed segment length; insert/nudge keep every segment at least this long. */
        private const val MIN_SEGMENT_MS = 200L

        /** Temporary speed while the right side of the screen is long-pressed. */
        private const val SLOW_MOTION_SPEED = 0.5f

        /** Simulated reverse: seek back [REVERSE_STEP_MS] every [REVERSE_INTERVAL_MS] (2x rate). */
        private const val REVERSE_STEP_MS = 1_000L
        private const val REVERSE_INTERVAL_MS = 500L

        /** Dubbing recording: AAC in m4a (DESIGN 5.3), voice-friendly quality. */
        private const val RECORD_SAMPLE_RATE = 44_100
        private const val RECORD_BIT_RATE = 128_000

        /** Dubbing sync: max tolerated drift, and min gap between corrective seeks. */
        private const val DUB_SYNC_TOLERANCE_MS = 300L
        private const val DUB_CORRECTION_MIN_INTERVAL_MS = 500L

        fun factory(videoId: Long): ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as DanceApplication
                PlayerViewModel(app, videoId)
            }
        }
    }
}
