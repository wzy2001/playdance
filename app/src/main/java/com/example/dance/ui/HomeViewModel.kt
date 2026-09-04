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
import com.example.dance.util.NetworkVideoImporter
import com.example.dance.util.UrlVideoMetadata
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** State machine of the "add from URL" flow (DESIGN 2.1). */
sealed interface UrlImportState {
    data object Idle : UrlImportState
    data object FetchingMeta : UrlImportState
    data class MetaReady(val meta: UrlVideoMetadata) : UrlImportState

    /** [progress] is 0..1, or null when total size is unknown. */
    data class Downloading(val meta: UrlVideoMetadata, val progress: Float?) : UrlImportState
    data object Done : UrlImportState
    data class Error(val duringDownload: Boolean, val detail: String?) : UrlImportState
}

class HomeViewModel(
    private val repository: VideoRepository,
    private val importer: LocalVideoImporter,
    private val networkImporter: NetworkVideoImporter
) : ViewModel() {

    val library: StateFlow<List<LibraryVideo>> = repository.observeLibrary()
        .catch { emit(emptyList()) }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5_000),
            initialValue = emptyList()
        )

    private val _urlImport = MutableStateFlow<UrlImportState>(UrlImportState.Idle)
    val urlImport: StateFlow<UrlImportState> = _urlImport.asStateFlow()

    /** Import a local video picked via the system file picker. */
    fun importLocalVideo(uri: android.net.Uri) {
        viewModelScope.launch {
            importer.import(uri)
        }
    }

    fun deleteVideo(video: LibraryVideo) {
        viewModelScope.launch {
            // Remove row; file cleanup is out of scope for milestone 1.
            repository.getVideo(video.id)?.let { repository.deleteVideo(it) }
        }
    }

    fun fetchUrlMetadata(url: String) {
        val trimmed = url.trim()
        if (trimmed.isEmpty()) return
        _urlImport.value = UrlImportState.FetchingMeta
        viewModelScope.launch {
            networkImporter.fetchMetadata(trimmed)
                .onSuccess { _urlImport.value = UrlImportState.MetaReady(it) }
                .onFailure {
                    _urlImport.value = UrlImportState.Error(duringDownload = false, detail = it.message)
                }
        }
    }

    fun downloadUrlVideo() {
        val meta = (_urlImport.value as? UrlImportState.MetaReady)?.meta ?: return
        _urlImport.value = UrlImportState.Downloading(meta, progress = 0f)
        viewModelScope.launch {
            networkImporter.download(meta) { progress ->
                _urlImport.update { state ->
                    if (state is UrlImportState.Downloading) state.copy(progress = progress) else state
                }
            }
                .onSuccess { _urlImport.value = UrlImportState.Done }
                .onFailure {
                    _urlImport.value = UrlImportState.Error(duringDownload = true, detail = it.message)
                }
        }
    }

    fun resetUrlImport() {
        _urlImport.value = UrlImportState.Idle
    }

    companion object {
        val Factory: ViewModelProvider.Factory = viewModelFactory {
            initializer {
                val app = this[ViewModelProvider.AndroidViewModelFactory.APPLICATION_KEY]
                    as DanceApplication
                HomeViewModel(
                    repository = app.videoRepository,
                    importer = LocalVideoImporter(app, app.videoRepository),
                    networkImporter = NetworkVideoImporter(app, app.videoRepository)
                )
            }
        }
    }
}
