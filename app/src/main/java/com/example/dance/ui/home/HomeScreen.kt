package com.example.dance.ui.home

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.dance.R
import com.example.dance.data.db.LibraryVideo
import com.example.dance.ui.HomeViewModel
import com.example.dance.ui.UrlImportState
import com.example.dance.util.UrlVideoMetadata
import com.example.dance.util.formatDurationMs

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onPlayVideo: (Long) -> Unit,
    viewModel: HomeViewModel = viewModel(factory = HomeViewModel.Factory)
) {
    val library by viewModel.library.collectAsStateWithLifecycle()
    val urlImport by viewModel.urlImport.collectAsStateWithLifecycle()
    var showAddSheet by rememberSaveable { mutableStateOf(false) }
    var showUrlDialog by rememberSaveable { mutableStateOf(false) }

    val pickVideoLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri: Uri? -> if (uri != null) viewModel.importLocalVideo(uri) }
    )

    Scaffold(
        topBar = { TopAppBar(title = { Text(stringResource(R.string.home_title)) }) },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddSheet = true }) {
                Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.add_video))
            }
        }
    ) { padding ->
        if (library.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.home_empty),
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(library, key = { it.id }) { video ->
                    VideoCard(
                        video = video,
                        onClick = { onPlayVideo(video.id) },
                        onDelete = { viewModel.deleteVideo(video) }
                    )
                }
            }
        }
    }

    if (showAddSheet) {
        AddVideoSheet(
            onDismiss = { showAddSheet = false },
            onPickLocal = {
                showAddSheet = false
                pickVideoLauncher.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.VideoOnly)
                )
            },
            onAddFromUrl = {
                showAddSheet = false
                showUrlDialog = true
            }
        )
    }

    if (showUrlDialog) {
        UrlImportDialog(
            state = urlImport,
            onFetch = viewModel::fetchUrlMetadata,
            onDownload = viewModel::downloadUrlVideo,
            onRetry = viewModel::resetUrlImport,
            onDismiss = {
                showUrlDialog = false
                viewModel.resetUrlImport()
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddVideoSheet(
    onDismiss: () -> Unit,
    onPickLocal: () -> Unit,
    onAddFromUrl: () -> Unit
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ListItem(
            headlineContent = { Text(stringResource(R.string.add_local_video)) },
            leadingContent = { Icon(Icons.Filled.PlayCircle, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onPickLocal)
        )
        ListItem(
            headlineContent = { Text(stringResource(R.string.add_from_url)) },
            supportingContent = { Text(stringResource(R.string.add_from_url_hint)) },
            leadingContent = { Icon(Icons.Filled.Add, contentDescription = null) },
            modifier = Modifier.clickable(onClick = onAddFromUrl)
        )
        Spacer(Modifier.height(24.dp))
    }
}

/**
 * "Add from URL" flow (DESIGN 2.1): input → metadata card → download.
 * Dismiss is blocked while downloading so the flow can't be detached from
 * its progress UI.
 */
@Composable
private fun UrlImportDialog(
    state: UrlImportState,
    onFetch: (String) -> Unit,
    onDownload: () -> Unit,
    onRetry: () -> Unit,
    onDismiss: () -> Unit
) {
    var url by rememberSaveable { mutableStateOf("") }
    val downloading = state is UrlImportState.Downloading

    if (state is UrlImportState.Done) {
        LaunchedEffect(Unit) { onDismiss() }
    }

    AlertDialog(
        onDismissRequest = { if (!downloading) onDismiss() },
        title = { Text(stringResource(R.string.url_dialog_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (state) {
                    UrlImportState.Idle -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text(stringResource(R.string.url_input_label)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    UrlImportState.FetchingMeta -> {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            CircularProgressIndicator(Modifier.size(24.dp))
                            Text(stringResource(R.string.url_fetching))
                        }
                    }
                    is UrlImportState.MetaReady -> UrlMetaCard(state.meta)
                    is UrlImportState.Downloading -> {
                        UrlMetaCard(state.meta)
                        val progress = state.progress
                        if (progress != null) {
                            LinearProgressIndicator(
                                progress = { progress },
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                stringResource(
                                    R.string.url_download_progress,
                                    (progress * 100).toInt()
                                )
                            )
                        } else {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(stringResource(R.string.url_downloading))
                        }
                    }
                    UrlImportState.Done -> {}
                    is UrlImportState.Error -> {
                        Text(
                            stringResource(
                                if (state.duringDownload) R.string.url_download_failed
                                else R.string.url_fetch_failed
                            ),
                            color = MaterialTheme.colorScheme.error
                        )
                        state.detail?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (state) {
                UrlImportState.Idle -> TextButton(
                    onClick = { onFetch(url) },
                    enabled = url.isNotBlank()
                ) { Text(stringResource(R.string.url_fetch_info)) }
                is UrlImportState.MetaReady -> TextButton(onClick = onDownload) {
                    Text(stringResource(R.string.url_download_local))
                }
                is UrlImportState.Error -> TextButton(onClick = onRetry) {
                    Text(stringResource(R.string.url_retry))
                }
                else -> {}
            }
        },
        dismissButton = {
            if (!downloading) {
                TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel)) }
            }
        }
    )
}

@Composable
private fun UrlMetaCard(meta: UrlVideoMetadata) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        val cover = meta.cover
        if (cover != null) {
            Image(
                bitmap = cover.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier
                    .width(96.dp)
                    .heightIn(max = 96.dp)
            )
        }
        Column(Modifier.weight(1f)) {
            Text(meta.title, style = MaterialTheme.typography.titleSmall)
            Text(
                text = formatDurationMs(meta.durationMs),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun VideoCard(
    video: LibraryVideo,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(video.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        text = formatDurationMs(video.durationMs),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (video.hasDubbing) {
                    Text(
                        text = stringResource(R.string.has_dubbing),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = onDelete) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.delete_video),
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}
