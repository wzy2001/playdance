package com.example.dance.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.example.dance.DanceApplication
import com.example.dance.data.VideoRepository
import com.example.dance.data.db.LibraryVideo
import com.example.dance.util.LocalVideoImporter
import com.example.dance.util.M4sVideoImporter
import com.example.dance.util.ThumbnailGenerator
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** State of the bilibili m4s cache import flow. */
sealed interface M4sImportState {
    data object Idle : M4sImportState
    data object Importing : M4sImportState
    data object Done : M4sImportState
    data class Error(val detail: String?) : M4sImportState
}

class HomeViewModel(
    private val repository: VideoRepository,
    private val importer: LocalVideoImporter,
    private val m4sImporter: M4sVideoImporter,
    private val thumbnails: ThumbnailGenerator,
    private val recordingsDir: File
) : ViewModel() {

    init {
        backfillMissingThumbnails()
        cleanOrphanRecordingsOnce()
    }

    /**
     * Generates thumbnails for videos imported before thumbnail support
     * existed. Idempotent: only rows with thumbnailPath IS NULL are touched,
     * via an in-place UPDATE (REPLACE-insert would cascade-delete segments
     * and chunks).
     */
    private fun backfillMissingThumbnails() {
        viewModelScope.launch(Dispatchers.IO) {
            repository.getVideosWithoutThumbnail().forEach { video ->
                if (!File(video.filePath).exists()) return@forEach
                thumbnails.generate(video.filePath)?.let { path ->
                    repository.updateVideo(video.copy(thumbnailPath = path))
                }
            }
        }
    }

    /**
     * Sweeps recordings/ for files no chunk row references — leftovers of a
     * process killed mid-recording. Runs once per process; the 60s age gate
     * protects a just-recorded file whose row insert is still in flight.
     */
    private fun cleanOrphanRecordingsOnce() {
        if (!orphanSweepDone.compareAndSet(false, true)) return
        viewModelScope.launch(Dispatchers.IO) {
            val referenced = repository.getAllChunkAudioPaths().toSet()
            recordingsDir.listFiles()?.forEach { file ->
                val isOrphan = file.absolutePath !in referenced
                val isSettled =
                    System.currentTimeMillis() - file.lastModified() > ORPHAN_MIN_AGE_MS
                if (isOrphan && isSettled) file.delete()
            }
        }
    }

    val library: StateFlow<List<LibraryVideo>> = repository.observeLibrary()
        .catch { emit(emptyList()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _m4sImport = MutableStateFlow<M4sImportState>(M4sImportState.Idle)
    val m4sImport: StateFlow<M4sImportState> = _m4sImport.asStateFlow()

    /** Import a local video picked via the system file picker. */
    fun importLocalVideo(uri: android.net.Uri) {
        viewModelScope.launch {
            importer.import(uri)
        }
    }

    /**
     * Deletes the video row (segments/chunks cascade) and then its files.
     * File paths are collected before the row delete because the cascade
     * removes the chunk rows. DB first, files second: a failed file delete
     * only leaves an orphan file, never a row pointing at nothing.
     */
    fun deleteVideo(video: LibraryVideo) {
        viewModelScope.launch {
            val row = repository.getVideo(video.id) ?: return@launch
            val chunks = repository.getChunks(video.id)
            repository.deleteVideo(row)
            withContext(Dispatchers.IO) {
                (listOf(row.filePath, row.thumbnailPath) + chunks.map { it.audioPath })
                    .filterNotNull()
                    .forEach { path -> runCatching { File(path).delete() } }
            }
        }
    }

    /**
     * Renames the display title only; files on disk keep their names. Uses the
     * in-place @Update (REPLACE-insert would cascade-delete segments/chunks).
     */
    fun renameVideo(video: LibraryVideo, newTitle: String) {
        val trimmed = newTitle.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            val row = repository.getVideo(video.id) ?: return@launch
            repository.updateVideo(row.copy(title = trimmed))
        }
    }

    fun importM4s(uris: List<android.net.Uri>, title: String) {
        _m4sImport.value = M4sImportState.Importing
        viewModelScope.launch {
            m4sImporter.import(uris, title)
                .onSuccess { _m4sImport.value = M4sImportState.Done }
                .onFailure { _m4sImport.value = M4sImportState.Error(it.message) }
        }
    }

    fun resetM4sImport() {
        _m4sImport.value = M4sImportState.Idle
    }

    companion object {
        private const val ORPHAN_MIN_AGE_MS = 60_000L

        /** Process-wide guard so the orphan sweep runs once, not per ViewModel. */
        private val orphanSweepDone = java.util.concurrent.atomic.AtomicBoolean(false)

        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as DanceApplication
                val thumbnails = ThumbnailGenerator(app)
                HomeViewModel(
                    repository = app.videoRepository,
                    importer = LocalVideoImporter(app, app.videoRepository, thumbnails),
                    m4sImporter = M4sVideoImporter(app, app.videoRepository, thumbnails),
                    thumbnails = thumbnails,
                    recordingsDir = File(app.filesDir, "recordings")
                )
            }
        }
    }
}
