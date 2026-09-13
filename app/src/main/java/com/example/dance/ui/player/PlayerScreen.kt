package com.example.dance.ui.player

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.view.WindowManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.example.dance.R
import com.example.dance.ui.theme.AccentBlue
import com.example.dance.util.formatDurationMs
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay

/** Delay before the control overlay auto-hides (DESIGN 5.6). */
private const val CONTROLS_HIDE_DELAY_MS = 3_000L

/** A horizontal swipe across the full screen width seeks this many seconds. */
private const val SWIPE_FULL_WIDTH_SECONDS = 60

/**
 * Recording indicator color. Deliberately red, not the blue accent (2026-09-08
 * user request): "recording" must stay visually distinct from the other active
 * control states, which all use [Accent].
 */
private val RecordingAccent = Color(0xFFFF5252)

/**
 * Player-control palette (bilibili-style overlay, unified blue in 2026-09-08):
 * a blue accent for the played portion of the timeline and active controls,
 * plus translucent scrims that let the video show through. Kept local to this
 * file so the rest of the app keeps its theme.
 */
private val Accent = AccentBlue
private val ScrimTop = Color(0xB3000000)
private val ScrimBottom = Color(0xCC000000)
private val ControlIdle = Color(0xCCFFFFFF)

/** Beat-volume multiplier range: 0 = muted, 1 = level with the video, 2 = boosted. */
private const val MAX_BEAT_VOLUME = 2f

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** e.g. 1.0×, 0.5× — always one decimal place. */
private fun formatSpeed(speed: Float): String = String.format(Locale.US, "%.1f×", speed)

/** Beat volume as a percentage of the video level, e.g. "100%", "150%". */
private fun formatBeatVolume(volume: Float): String =
    "${(volume * 100).roundToInt()}%"

/**
 * Player page, Milestone 2 scope: main-player playback of a local video with
 * timeline seek, play/pause, speed 0.5–1.0, whole-video loop, immersive
 * fullscreen with tap-to-toggle controls, and FIT scaling.
 */
@OptIn(UnstableApi::class)
@Composable
fun PlayerScreen(
    videoId: Long,
    onBack: () -> Unit,
    viewModel: PlayerViewModel = viewModel(factory = PlayerViewModel.factory(videoId))
) {
    val state by viewModel.uiState.collectAsState()

    var controlsVisible by remember { mutableStateOf(true) }
    // Bumped on any control interaction to restart the auto-hide countdown.
    var interactionTick by remember { mutableIntStateOf(0) }
    var speedMenuExpanded by remember { mutableStateOf(false) }
    var beatMenuExpanded by remember { mutableStateOf(false) }
    // Off by default: only the segment bar shows; on: split editing controls appear.
    var segmentEditMode by remember { mutableStateOf(false) }
    // Swipe-seek preview in seconds; non-null while a horizontal swipe is in progress.
    var swipeDeltaSec by remember { mutableStateOf<Int?>(null) }

    val context = LocalContext.current
    val recordPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            viewModel.startRecording()
        } else {
            Toast.makeText(
                context,
                context.getString(R.string.player_record_permission_denied),
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    val view = LocalView.current
    // Immersive fullscreen + keep-screen-on while this screen is shown.
    DisposableEffect(Unit) {
        val activity = view.context.findActivity() ?: return@DisposableEffect onDispose {}
        val window = activity.window
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val controller = WindowCompat.getInsetsController(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        onDispose {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            controller.show(WindowInsetsCompat.Type.systemBars())
            activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }
    // App-to-background handling. Registered on the ACTIVITY lifecycle, not
    // LocalLifecycleOwner (the NavBackStackEntry): back navigation also stops
    // the entry, which would race the ON_STOP save against onCleared's scope
    // cancellation and break the "back = discard recording" semantics.
    DisposableEffect(Unit) {
        val activity = view.context.findActivity()
        val owner = activity as? LifecycleOwner
            ?: return@DisposableEffect onDispose {}
        val observer = LifecycleEventObserver { _, event ->
            // Config changes (rotation, the landscape lock applied on entry)
            // also fire ON_STOP on the old activity; skip those.
            if (event == Lifecycle.Event.ON_STOP && !activity.isChangingConfigurations) {
                viewModel.onHostStopped()
            }
        }
        owner.lifecycle.addObserver(observer)
        onDispose { owner.lifecycle.removeObserver(observer) }
    }
    // Landscape source → lock the activity to landscape so the picture fills the
    // screen (content rotated 90° clockwise relative to a portrait-held device)
    // and the control overlay re-lays out for landscape automatically.
    LaunchedEffect(state.isLandscapeVideo) {
        view.context.findActivity()?.requestedOrientation =
            if (state.isLandscapeVideo) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            else ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
    // Actually start the gated initial play: only once the orientation is known
    // AND, for a landscape source, the activity is already in landscape. A
    // landscape video therefore rotates before it plays (no portrait frames).
    // Portrait sources play as soon as the orientation is known, whatever the
    // current device orientation (FIT scaling letterboxes a sideways portrait).
    val configOrientation = LocalConfiguration.current.orientation
    LaunchedEffect(state.orientationKnown, state.isLandscapeVideo, configOrientation) {
        viewModel.startPlaybackWhenOriented(configOrientation)
    }
    // System bars follow the control overlay (DESIGN 2.8).
    LaunchedEffect(controlsVisible) {
        val window = view.context.findActivity()?.window ?: return@LaunchedEffect
        val controller = WindowCompat.getInsetsController(window, view)
        if (controlsVisible) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }
    }
    LaunchedEffect(controlsVisible, interactionTick, speedMenuExpanded, beatMenuExpanded, state.selectedSplitIndex, state.isRecording) {
        // No auto-hide while the speed/beat menu is open, a split point is being
        // edited, or a recording is in progress (the stop button must stay).
        if (controlsVisible && !speedMenuExpanded && !beatMenuExpanded &&
            state.selectedSplitIndex == null && !state.isRecording
        ) {
            delay(CONTROLS_HIDE_DELAY_MS)
            controlsVisible = false
        }
    }
    // Recording forces the controls up and closes split editing.
    LaunchedEffect(state.isRecording) {
        if (state.isRecording) {
            controlsVisible = true
            segmentEditMode = false
            viewModel.selectSplit(null)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures { controlsVisible = !controlsVisible }
            }
    ) {
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    useController = false
                    resizeMode = AspectRatioFrameLayout.RESIZE_MODE_FIT
                    player = viewModel.player
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        // Gesture layer (DESIGN 2.7 / 5.5): long-press left/right + horizontal
        // swipe. Sits above the video and below the control overlay; control
        // areas block it via their own hit targets. Disabled when the video is
        // missing and while recording (DESIGN 2.7).
        if (!state.videoMissing && !state.isRecording) {
            GestureLayer(
                onSlowMotionStart = viewModel::startSlowMotionGesture,
                onReverseStart = viewModel::startReverseGesture,
                onLongPressEnd = viewModel::endLongPressGesture,
                onSwipePreview = { swipeDeltaSec = it },
                onSwipeCommit = { deltaSec ->
                    swipeDeltaSec = null
                    if (deltaSec != 0) viewModel.seekTo(state.positionMs + deltaSec * 1000L)
                }
            )
        }

        // Gesture feedback pill (swipe amount or active long-press mode).
        val swipe = swipeDeltaSec
        val gestureLabel = when {
            swipe != null -> stringResource(R.string.player_gesture_seek, swipe)
            state.activeGesture == PlayerGesture.SLOW_MOTION ->
                stringResource(R.string.player_gesture_slow)
            state.activeGesture == PlayerGesture.REVERSE ->
                stringResource(R.string.player_gesture_reverse)
            else -> null
        }
        if (gestureLabel != null) {
            Text(
                text = gestureLabel,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(top = 56.dp)
                    .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        if (state.videoMissing) {
            Text(
                text = stringResource(R.string.player_video_missing),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.align(Alignment.Center)
            )
        }

        AnimatedVisibility(
            visible = controlsVisible,
            enter = fadeIn(),
            exit = fadeOut()
        ) {
            PlayerControls(
                state = state,
                speedSteps = viewModel.speedSteps,
                speedMenuExpanded = speedMenuExpanded,
                onSpeedMenuExpandedChange = { speedMenuExpanded = it; interactionTick++ },
                beatMenuExpanded = beatMenuExpanded,
                onBeatMenuExpandedChange = { beatMenuExpanded = it; interactionTick++ },
                onBack = onBack,
                onPlayPause = { viewModel.togglePlayPause(); interactionTick++ },
                onSeek = { viewModel.seekTo(it); interactionTick++ },
                onScrub = { interactionTick++ },
                onSpeedSelected = { viewModel.setSpeed(it); interactionTick++ },
                onToggleLoop = { viewModel.toggleLoop(); interactionTick++ },
                segmentEditMode = segmentEditMode,
                onToggleSegmentEdit = {
                    segmentEditMode = !segmentEditMode
                    if (!segmentEditMode) viewModel.selectSplit(null)
                    interactionTick++
                },
                onInsertSplit = { viewModel.insertSplitAtCurrentPosition(); interactionTick++ },
                onSelectSplit = { viewModel.selectSplit(it); interactionTick++ },
                onDeleteSplit = { viewModel.deleteSelectedSplit(); interactionTick++ },
                onNudgeSplit = { viewModel.nudgeSelectedSplit(it); interactionTick++ },
                onSegmentTapped = { viewModel.onSegmentTapped(it); interactionTick++ },
                onToggleDubbing = { viewModel.toggleDubbing(); interactionTick++ },
                onDubbingVolume = { viewModel.setDubbingVolume(it); interactionTick++ },
                onRecordClick = {
                    interactionTick++
                    when {
                        state.isRecording -> viewModel.stopRecording()
                        ContextCompat.checkSelfPermission(
                            context, Manifest.permission.RECORD_AUDIO
                        ) == PackageManager.PERMISSION_GRANTED -> viewModel.startRecording()
                        else -> recordPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                    }
                }
            )
        }
    }
}

@Composable
private fun PlayerControls(
    state: PlayerUiState,
    speedSteps: List<Float>,
    speedMenuExpanded: Boolean,
    onSpeedMenuExpandedChange: (Boolean) -> Unit,
    beatMenuExpanded: Boolean,
    onBeatMenuExpandedChange: (Boolean) -> Unit,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeek: (Long) -> Unit,
    onScrub: () -> Unit,
    onSpeedSelected: (Float) -> Unit,
    onToggleLoop: () -> Unit,
    segmentEditMode: Boolean,
    onToggleSegmentEdit: () -> Unit,
    onInsertSplit: () -> Unit,
    onSelectSplit: (Int?) -> Unit,
    onDeleteSplit: () -> Unit,
    onNudgeSplit: (Long) -> Unit,
    onSegmentTapped: (Int) -> Unit,
    onToggleDubbing: () -> Unit,
    onDubbingVolume: (Float) -> Unit,
    onRecordClick: () -> Unit
) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Gradient scrims span the full screen edge-to-edge (they are not inset
        // by the system bars) so no black strip shows below the controls; the
        // bars themselves apply the insets so buttons clear the system UI.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(96.dp)
                .align(Alignment.TopCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(ScrimTop, Color.Transparent)
                    )
                )
        )
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(220.dp)
                .align(Alignment.BottomCenter)
                .background(
                    Brush.verticalGradient(
                        colors = listOf(Color.Transparent, ScrimBottom)
                    )
                )
        )
        // Top bar: back + title. The empty pointerInput makes the whole bar a
        // hit target so gestures below it (GestureLayer) never see its touches.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopStart)
                .pointerInput(Unit) {}
                .windowInsetsPadding(WindowInsets.statusBars)
                .padding(horizontal = 4.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = Color.White
                )
            }
            Text(
                text = state.title,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        // Bottom control block: timeline + transport + speed. Blocks the
        // gesture layer the same way as the top bar. Only a small inset is left
        // for the gesture bar so the controls hug the screen edge like the
        // reference layout instead of floating above a black strip.
        val navBottomDp = with(LocalDensity.current) {
            WindowInsets.navigationBars.getBottom(this).toDp()
        }
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomStart)
                .pointerInput(Unit) {}
                .padding(
                    start = 12.dp,
                    end = 12.dp,
                    top = 2.dp,
                    bottom = navBottomDp.coerceAtMost(16.dp) + 2.dp
                )
        ) {
            if (segmentEditMode) {
                SplitEditRows(
                    state = state,
                    onInsertSplit = onInsertSplit,
                    onSelectSplit = onSelectSplit,
                    onDeleteSplit = onDeleteSplit,
                    onNudgeSplit = onNudgeSplit
                )
            }
            Spacer(Modifier.height(6.dp))
            SegmentBar(
                state = state,
                onSegmentTapped = onSegmentTapped
            )
            // Time above the bar, left-aligned (bilibili-style), then the thin
            // timeline itself.
            Text(
                text = "${formatDurationMs(state.positionMs)} / ${formatDurationMs(state.durationMs)}",
                color = ControlIdle,
                style = MaterialTheme.typography.labelSmall
            )
            TimelineSlider(
                positionMs = state.positionMs,
                durationMs = state.durationMs,
                boundaries = state.boundaries,
                enabled = !state.isRecording,
                onSeek = onSeek,
                onScrub = onScrub
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPlayPause, enabled = !state.isRecording) {
                    Icon(
                        imageVector = if (state.isPlaying) Icons.Filled.Pause
                        else Icons.Filled.PlayArrow,
                        contentDescription = stringResource(
                            if (state.isPlaying) R.string.player_pause else R.string.player_play
                        ),
                        tint = if (state.isRecording) Color.White.copy(alpha = 0.3f) else Color.White
                    )
                }
                IconButton(onClick = onToggleLoop, enabled = !state.isRecording) {
                    Icon(
                        Icons.Filled.Repeat,
                        contentDescription = stringResource(R.string.player_loop),
                        tint = when {
                            state.isRecording -> Color.White.copy(alpha = 0.3f)
                            state.loopEnabled -> Accent
                            else -> ControlIdle
                        }
                    )
                }
                // Record button (DESIGN 2.5): records dubbing for the selected
                // block (or the whole video when no block is selected).
                IconButton(onClick = onRecordClick) {
                    Icon(
                        imageVector = if (state.isRecording) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = stringResource(
                            if (state.isRecording) R.string.player_record_stop
                            else R.string.player_record
                        ),
                        tint = if (state.isRecording) RecordingAccent else Color.White
                    )
                }
                if (state.isRecording) {
                    Text(
                        text = "● " + formatDurationMs(state.recordingElapsedMs),
                        color = RecordingAccent,
                        style = MaterialTheme.typography.labelMedium
                    )
                }
                Spacer(Modifier.weight(1f))
                // Segment-edit toggle: reveals the split editing rows above.
                TextButton(
                    onClick = onToggleSegmentEdit,
                    enabled = !state.isRecording,
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = if (segmentEditMode) Accent else Color.White,
                        disabledContentColor = Color.White.copy(alpha = 0.3f)
                    ),
                    contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = stringResource(R.string.player_segment_edit),
                        style = MaterialTheme.typography.labelLarge
                    )
                }
                // Beat control: toggles beat playback and sets the beat volume
                // (a multiplier of the video sound, 0–2). The video itself
                // follows the system media volume, so there is no separate
                // original-sound slider (2026-09-08).
                Box {
                    TextButton(
                        onClick = { onBeatMenuExpandedChange(true) },
                        enabled = !state.isRecording,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = if (state.dubbingEnabled) Accent
                            else Color.White,
                            disabledContentColor = Color.White.copy(alpha = 0.3f)
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.player_beat),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    DropdownMenu(
                        expanded = beatMenuExpanded,
                        onDismissRequest = { onBeatMenuExpandedChange(false) }
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.player_beat_toggle)) },
                            trailingIcon = {
                                Switch(
                                    checked = state.dubbingEnabled,
                                    onCheckedChange = { onToggleDubbing() }
                                )
                            },
                            onClick = { onToggleDubbing() }
                        )
                        Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                            Text(
                                text = "${stringResource(R.string.player_beat_hint)} · " +
                                    formatBeatVolume(state.dubbingVolume),
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Slider(
                                value = state.dubbingVolume
                                    .coerceIn(0f, MAX_BEAT_VOLUME),
                                onValueChange = onDubbingVolume,
                                enabled = state.dubbingEnabled,
                                valueRange = 0f..MAX_BEAT_VOLUME,
                                modifier = Modifier.width(180.dp)
                            )
                        }
                    }
                }
                // Speed selector: single button at the bottom-right, expands a menu.
                Box {
                    TextButton(
                        onClick = { onSpeedMenuExpandedChange(true) },
                        enabled = !state.isRecording,
                        colors = ButtonDefaults.textButtonColors(
                            contentColor = Color.White,
                            disabledContentColor = Color.White.copy(alpha = 0.3f)
                        ),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
                    ) {
                        Text(
                            text = formatSpeed(state.speed),
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                    DropdownMenu(
                        expanded = speedMenuExpanded,
                        onDismissRequest = { onSpeedMenuExpandedChange(false) }
                    ) {
                        speedSteps.forEach { speed ->
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        text = formatSpeed(speed),
                                        color = if (speed == state.speed)
                                            MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.onSurface
                                    )
                                },
                                onClick = {
                                    onSpeedSelected(speed)
                                    onSpeedMenuExpandedChange(false)
                                }
                            )
                        }
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
        }
    }
}

/**
 * Full-screen gesture layer (DESIGN 2.7 / 5.5): long-press right = temporary
 * 0.5x, long-press left = simulated 2x reverse, horizontal swipe = seek with a
 * "±N 秒" preview. It sits below the control overlay; control bars/buttons are
 * their own hit targets, so gestures never start on them.
 */
@Composable
private fun GestureLayer(
    onSlowMotionStart: () -> Unit,
    onReverseStart: () -> Unit,
    onLongPressEnd: () -> Unit,
    onSwipePreview: (Int) -> Unit,
    onSwipeCommit: (Int) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    // Cancelled by movement being consumed (the swipe detector)
                    // or by lifting the finger before the long-press timeout.
                    val press = awaitLongPressOrCancellation(down.id) ?: return@awaitEachGesture
                    if (press.position.x >= size.width / 2f) onSlowMotionStart()
                    else onReverseStart()
                    try {
                        // Swallow the rest of the gesture so the tap-to-toggle
                        // handler underneath does not also fire on release.
                        while (true) {
                            val event = awaitPointerEvent()
                            event.changes.forEach { it.consume() }
                            if (event.changes.none { it.pressed }) break
                        }
                    } finally {
                        onLongPressEnd()
                    }
                }
            }
            .pointerInput(Unit) {
                var totalPx = 0f
                detectHorizontalDragGestures(
                    onDragStart = { totalPx = 0f },
                    onDragEnd = {
                        onSwipeCommit(
                            (totalPx / size.width * SWIPE_FULL_WIDTH_SECONDS).roundToInt()
                        )
                    },
                    onDragCancel = { onSwipeCommit(0) },
                    onHorizontalDrag = { change, dragAmount ->
                        change.consume()
                        totalPx += dragAmount
                        onSwipePreview(
                            (totalPx / size.width * SWIPE_FULL_WIDTH_SECONDS).roundToInt()
                        )
                    }
                )
            }
    )
}

/**
 * Timeline bar in the reference style: a thin rounded track, an accent-pink
 * played portion, a small round thumb, and faint tick marks at segment
 * boundaries. Drawn by hand (Canvas + drag gestures) rather than with
 * Material's Slider, whose custom track/thumb API is experimental here.
 */
@Composable
private fun TimelineSlider(
    positionMs: Long,
    durationMs: Long,
    boundaries: List<Long>,
    enabled: Boolean,
    onSeek: (Long) -> Unit,
    onScrub: () -> Unit
) {
    // While scrubbing, show the drag position instead of the live player position.
    var scrubPositionMs by remember { mutableStateOf<Long?>(null) }
    val shown = scrubPositionMs ?: positionMs
    val safeDuration = durationMs.coerceAtLeast(1L)

    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .pointerInput(enabled, safeDuration) {
                if (!enabled) return@pointerInput
                detectHorizontalDragGestures(
                    onDragStart = { offset ->
                        scrubPositionMs = (offset.x / size.width * safeDuration)
                            .toLong().coerceIn(0L, safeDuration)
                        onScrub()
                    },
                    onDragEnd = {
                        scrubPositionMs?.let(onSeek)
                        scrubPositionMs = null
                    },
                    onDragCancel = {
                        scrubPositionMs = null
                    },
                    onHorizontalDrag = { change, _ ->
                        change.consume()
                        scrubPositionMs = (change.position.x / size.width * safeDuration)
                            .toLong().coerceIn(0L, safeDuration)
                        onScrub()
                    }
                )
            }
            .pointerInput(enabled, safeDuration) {
                if (!enabled) return@pointerInput
                detectTapGestures { offset ->
                    onSeek(
                        (offset.x / size.width * safeDuration)
                            .toLong().coerceIn(0L, safeDuration)
                    )
                }
            }
    ) {
        val midY = size.height / 2f
        val trackHeight = 3.dp.toPx()
        val thumbRadius = 7.dp.toPx()
        val progressX = (shown.toFloat() / safeDuration * size.width)
            .coerceIn(0f, size.width)
        val trackAlpha = if (enabled) 1f else 0.4f

        // Unplayed track.
        drawRoundRect(
            color = Color.White.copy(alpha = 0.25f * trackAlpha),
            topLeft = Offset(0f, midY - trackHeight / 2f),
            size = Size(size.width, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        )
        // Played portion in the accent color.
        drawRoundRect(
            color = Accent.copy(alpha = trackAlpha),
            topLeft = Offset(0f, midY - trackHeight / 2f),
            size = Size(progressX, trackHeight),
            cornerRadius = CornerRadius(trackHeight / 2f, trackHeight / 2f)
        )
        // Segment-boundary ticks (interior splits only).
        boundaries.drop(1).dropLast(1).forEach { splitMs ->
            val x = (splitMs.toFloat() / safeDuration * size.width).coerceIn(0f, size.width)
            drawRoundRect(
                color = Color.White.copy(alpha = 0.5f * trackAlpha),
                topLeft = Offset(x - 0.5.dp.toPx(), midY - 5.dp.toPx()),
                size = Size(1.dp.toPx(), 10.dp.toPx()),
                cornerRadius = CornerRadius(0.5f, 0.5f)
            )
        }
        // Thumb: a small white circle with a faint halo, like the reference.
        drawCircle(
            color = Color.Black.copy(alpha = 0.25f * trackAlpha),
            radius = thumbRadius + 1.5.dp.toPx(),
            center = Offset(progressX, midY)
        )
        drawCircle(
            color = Color.White.copy(alpha = trackAlpha),
            radius = thumbRadius,
            center = Offset(progressX, midY)
        )
    }
}

/**
 * Split-point editing (DESIGN 5.4): an insert button plus one chip per split
 * point; selecting a chip reveals the nudge (±10/±100 ms) and delete row.
 */
@Composable
private fun SplitEditRows(
    state: PlayerUiState,
    onInsertSplit: () -> Unit,
    onSelectSplit: (Int?) -> Unit,
    onDeleteSplit: () -> Unit,
    onNudgeSplit: (Long) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TextButton(
            onClick = onInsertSplit,
            colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
        ) {
            Text(
                text = "＋" + stringResource(R.string.player_split_insert),
                style = MaterialTheme.typography.labelLarge
            )
        }
        state.splitPoints.forEachIndexed { index, splitMs ->
            val selected = index == state.selectedSplitIndex
            TextButton(
                onClick = { onSelectSplit(if (selected) null else index) },
                colors = ButtonDefaults.textButtonColors(
                    contentColor = if (selected) MaterialTheme.colorScheme.primary else Color.White
                ),
                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
            ) {
                Text(text = formatDurationMs(splitMs), style = MaterialTheme.typography.labelMedium)
            }
        }
    }
    if (state.selectedSplitIndex != null) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            listOf(-100L, -10L, 10L, 100L).forEach { delta ->
                TextButton(
                    onClick = { onNudgeSplit(delta) },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.White),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                ) {
                    Text(
                        text = if (delta > 0) "+${delta}ms" else "${delta}ms",
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            Spacer(Modifier.weight(1f))
            TextButton(
                onClick = onDeleteSplit,
                colors = ButtonDefaults.textButtonColors(
                    contentColor = MaterialTheme.colorScheme.error
                ),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 0.dp)
            ) {
                Text(
                    text = stringResource(R.string.player_split_delete),
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}

/**
 * Segment/block selection bar (DESIGN 2.4): one box per segment, proportional
 * to its length. Tap: select a segment as the block, tap outside the block to
 * extend it, tap inside to collapse, tap the single selected segment to clear.
 */
@Composable
private fun SegmentBar(
    state: PlayerUiState,
    onSegmentTapped: (Int) -> Unit
) {
    val boundaries = state.boundaries
    val durationMs = state.durationMs.coerceAtLeast(1L)
    val blockStart = state.blockStartIndex
    val blockEnd = state.blockEndIndex
    val blockColor = Accent
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(14.dp)
            .padding(vertical = 2.dp)
            .pointerInput(boundaries) {
                detectTapGestures { offset ->
                    val tappedMs = (offset.x / size.width * durationMs).toLong()
                    val index = boundaries.zipWithNext()
                        .indexOfFirst { (s, e) -> tappedMs in s until e }
                    if (index >= 0) onSegmentTapped(index)
                }
            }
    ) {
        val width = size.width
        boundaries.zipWithNext().forEachIndexed { index, (startMs, endMs) ->
            val x0 = startMs.toFloat() / durationMs * width + 1.5f
            val x1 = endMs.toFloat() / durationMs * width - 1.5f
            if (x1 <= x0) return@forEachIndexed
            val inBlock = blockStart != null && blockEnd != null && index in blockStart..blockEnd
            drawRoundRect(
                color = if (inBlock) blockColor else Color.White.copy(alpha = 0.25f),
                topLeft = Offset(x0, 0f),
                size = Size(x1 - x0, size.height),
                cornerRadius = CornerRadius(1.5f, 1.5f)
            )
        }
    }
}
