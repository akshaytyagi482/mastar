package com.mastar.editor.ui.editor

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.mastar.editor.data.db.ClipType
import com.mastar.editor.data.db.KeyframeProperty
import com.mastar.editor.engine.effects.TextPayload
import androidx.media3.ui.PlayerView
import com.mastar.editor.ui.timeline.TimelineActions
import com.mastar.editor.ui.timeline.TimelineView
import com.mastar.editor.ui.timeline.rememberTimelineState
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * CapCut-style editing workspace:
 *   top bar (close / aspect / export)
 *   preview + overlays + draggable PIP placement
 *   transport row (time, frame stepping, snap toggle, undo/redo, play)
 *   timeline (filmstrips, waveforms, trim handles, keyframe diamonds)
 *   tool panel + bottom tool tabs
 */
@UnstableApi
@Composable
fun EditorScreen(
    projectId: Long,
    onBack: () -> Unit,
) {
    val context = LocalContext.current
    val viewModel: EditorViewModel = viewModel(
        factory = EditorViewModel.factory(
            context.applicationContext as Application, projectId
        )
    )

    val project by viewModel.project.collectAsState()
    val selectedClipId by viewModel.selectedClipId.collectAsState()
    val multiSelection by viewModel.multiSelection.collectAsState()
    val allKeyframes by viewModel.keyframes.collectAsState()
    val renameTarget by viewModel.renameTarget.collectAsState()
    val exportState by viewModel.exportState.collectAsState()
    val canUndo by viewModel.canUndo.collectAsState()
    val canRedo by viewModel.canRedo.collectAsState()
    val previewPlayer by viewModel.previewEngine.playerFlow.collectAsState()
    val previewError by viewModel.previewEngine.errorFlow.collectAsState()
    val exportLocation by viewModel.exportLocation.collectAsState()
    val timelineState = rememberTimelineState()

    var activeTool by remember { mutableStateOf<EditorTool?>(null) }
    var showExportDialog by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }
    var positionMs by remember { mutableLongStateOf(0L) }

    // Follow playback: playhead + auto-scroll + transport clock at 30Hz.
    LaunchedEffect(Unit) {
        while (true) {
            isPlaying = viewModel.previewEngine.isPlaying
            if (isPlaying) {
                positionMs = viewModel.previewEngine.currentTimelinePositionMs()
                timelineState.playheadMs = positionMs
                timelineState.scrollPx = timelineState.msToPx(positionMs)
            } else {
                positionMs = timelineState.playheadMs
            }
            delay(33)
        }
    }

    fun takePersist(uri: Uri) {
        context.contentResolver.takePersistableUriPermission(
            uri, Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
    }

    // CapCut-style multi-select video import.
    val pickVideos = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        uris.forEach { uri ->
            takePersist(uri)
            val probe = probeMedia(context, uri)
            if (probe.durationMs > 0) {
                viewModel.addVideoClip(
                    uri.toString(), probe.durationMs, probe.width, probe.height
                )
            }
        }
    }

    val pickPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        takePersist(uri)
        viewModel.addImageClip(uri.toString())
    }

    val pickAudio = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        takePersist(uri)
        val durationMs = probeDurationMs(context, uri)
        if (durationMs > 0) {
            viewModel.addAudioClip(uri.toString(), durationMs, timelineState.playheadMs)
        }
    }

    // "Extract audio": pick a video, use only its soundtrack.
    val pickExtract = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        takePersist(uri)
        val durationMs = probeDurationMs(context, uri)
        if (durationMs > 0) {
            viewModel.extractAudio(uri.toString(), durationMs, timelineState.playheadMs)
        }
    }

    // PIP layer: video or photo floating over the main track.
    val pickPip = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        takePersist(uri)
        val mime = context.contentResolver.getType(uri).orEmpty()
        val isImage = mime.startsWith("image/")
        if (isImage) {
            val (w, h) = probeImageSize(context, uri)
            viewModel.addPipClip(uri.toString(), true, 3000L, timelineState.playheadMs, w, h)
        } else {
            val probe = probeMedia(context, uri)
            if (probe.durationMs > 0) {
                viewModel.addPipClip(
                    uri.toString(), false, probe.durationMs, timelineState.playheadMs,
                    probe.width, probe.height,
                )
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        EditorTopBar(
            canvasWidth = project?.project?.canvasWidth ?: 1080,
            canvasHeight = project?.project?.canvasHeight ?: 1920,
            exportState = exportState,
            exportLocation = exportLocation,
            onBack = onBack,
            onSetCanvas = { w, h -> viewModel.setCanvas(w, h) },
            onExportClick = { showExportDialog = true },
        )

        // Preview + overlays + PIP drag placement.
        BoxWithConstraints(
            Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        useController = false
                    }
                },
                update = { view ->
                    // The engine rebuilds the player per composition; keep
                    // the surface attached to the latest instance.
                    if (view.player !== previewPlayer) view.player = previewPlayer
                },
                modifier = Modifier.fillMaxSize(),
            )
            PreviewOverlays(viewModel, timelineState.playheadMs)

            // Selected visual clip: bordered box — drag to move, pinch to
            // resize, right in the preview. Auto-keys when diamonds exist.
            val selectedId = selectedClipId
            val selectedForGesture = selectedId?.let { viewModel.clipById(it) }
            if (selectedForGesture != null &&
                (selectedForGesture.type == ClipType.VIDEO ||
                    selectedForGesture.type == ClipType.IMAGE ||
                    selectedForGesture.type == ClipType.TEXT)
            ) {
                val values = viewModel.transformValuesAt(
                    selectedForGesture.id, timelineState.playheadMs
                )
                if (values != null) {
                    val mediaAspect = when {
                        selectedForGesture.type == ClipType.TEXT -> 3f
                        selectedForGesture.sourceWidth > 0 && selectedForGesture.sourceHeight > 0 ->
                            selectedForGesture.sourceWidth.toFloat() / selectedForGesture.sourceHeight
                        else -> 1f
                    }
                    TransformGestureBox(
                        positionX = values.positionX,
                        positionY = values.positionY,
                        scale = values.scale,
                        mediaAspect = mediaAspect,
                        onCommit = { x, y, sc ->
                            viewModel.setClipTransform(
                                selectedForGesture.id, timelineState.playheadMs,
                                mapOf(
                                    KeyframeProperty.POSITION_X to x,
                                    KeyframeProperty.POSITION_Y to y,
                                    KeyframeProperty.SCALE to sc,
                                ),
                            )
                        },
                    )
                }
            }

            previewError?.let { message ->
                Text(
                    text = message,
                    color = Color(0xFFFF5252),
                    modifier = Modifier
                        .align(Alignment.BottomStart)
                        .background(Color.Black.copy(alpha = 0.6f))
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
        }

        TransportRow(
            positionMs = positionMs,
            durationMs = viewModel.projectDurationMs,
            isPlaying = isPlaying,
            canUndo = canUndo,
            canRedo = canRedo,
            snapEnabled = timelineState.snappingEnabled,
            onToggleSnap = {
                timelineState.snappingEnabled = !timelineState.snappingEnabled
            },
            onPlayPause = { viewModel.togglePlayback(timelineState.playheadMs) },
            onStepFrame = { deltaMs ->
                val target = (timelineState.playheadMs + deltaMs).coerceAtLeast(0)
                timelineState.playheadMs = target
                timelineState.scrollPx = timelineState.msToPx(target)
                viewModel.seekTo(target)
            },
            onUndo = viewModel::undo,
            onRedo = viewModel::redo,
        )

        TimelineView(
            state = timelineState,
            tracks = project?.tracks.orEmpty(),
            selectedClipId = selectedClipId,
            multiSelection = multiSelection,
            keyframeTimes = { clipId ->
                allKeyframes.filter { it.clipId == clipId }.map { it.timeMs }
            },
            actions = TimelineActions(
                onSelectClip = { id ->
                    viewModel.selectClip(id)
                    if (id == null && activeTool?.needsSelection == true) activeTool = null
                },
                onMoveClip = viewModel::moveClip,
                onTrimClip = viewModel::trimClip,
                onSeek = viewModel::seekTo,
                onAddAudio = { pickAudio.launch(arrayOf("audio/*")) },
                onMenuAction = viewModel::onClipMenuAction,
            ),
            modifier = Modifier.weight(1f),
        )

        // Slide-up tool panel (speed/volume/filter/adjust/...) above the tabs.
        activeTool?.let { tool ->
            ToolPanel(
                tool = tool,
                viewModel = viewModel,
                playheadMs = timelineState.playheadMs,
                onClose = { activeTool = null },
            )
        }

        BottomToolBar(
            hasSelection = selectedClipId != null,
            selectedClipType = viewModel.selectedClip()?.type,
            activeTool = activeTool,
            onTool = { tool ->
                when (tool) {
                    EditorTool.ADD_CLIP -> pickVideos.launch(arrayOf("video/*"))
                    EditorTool.PHOTO -> pickPhoto.launch(arrayOf("image/*"))
                    EditorTool.PIP -> pickPip.launch(arrayOf("video/*", "image/*"))
                    EditorTool.AUDIO -> pickAudio.launch(arrayOf("audio/*"))
                    EditorTool.EXTRACT -> pickExtract.launch(arrayOf("video/*"))
                    EditorTool.SPLIT -> viewModel.splitSelectedClipAtPlayhead(timelineState.playheadMs)
                    EditorTool.DUPLICATE -> viewModel.duplicateSelectedClip()
                    EditorTool.FREEZE -> viewModel.freezeFrame(
                        timelineState.playheadMs,
                        context.getExternalFilesDir(null) ?: context.filesDir,
                    )
                    EditorTool.DELETE -> viewModel.deleteSelectedClip()
                    else -> activeTool = if (activeTool == tool) null else tool
                }
            },
        )
    }

    if (showExportDialog) {
        ExportDialog(
            onExport = { settings ->
                viewModel.export(
                    context.getExternalFilesDir(null) ?: context.filesDir,
                    settings,
                )
                showExportDialog = false
            },
            onDismiss = { showExportDialog = false },
        )
    }

    renameTarget?.let { clip ->
        RenameDialog(
            initial = clip.displayName.orEmpty(),
            onConfirm = { viewModel.renameClip(clip.id, it) },
            onDismiss = viewModel::dismissRename,
        )
    }

    when (activeTool) {
        EditorTool.TEXT -> AddTextDialog(
            onConfirm = { payload ->
                viewModel.addTextClip(payload.toJson(), timelineState.playheadMs)
                activeTool = null
            },
            onDismiss = { activeTool = null },
        )
        EditorTool.EDIT_TEXT -> {
            val textClip = viewModel.selectedClip()
            if (textClip?.type == ClipType.TEXT) {
                AddTextDialog(
                    initial = TextPayload.fromPayload(textClip.payload),
                    onConfirm = { payload ->
                        viewModel.updateClip(textClip.id) { it.copy(payload = payload.toJson()) }
                        activeTool = null
                    },
                    onDismiss = { activeTool = null },
                )
            } else {
                activeTool = null
            }
        }
        EditorTool.FILTER_LAYER -> FilterLayerPicker(
            onPick = { filterId ->
                viewModel.addFilterLayer(filterId, timelineState.playheadMs)
                activeTool = null
            },
            onDismiss = { activeTool = null },
        )
        EditorTool.STICKER -> StickerPicker(
            onPick = { asset ->
                viewModel.addStickerClip(asset, timelineState.playheadMs)
                activeTool = null
            },
            onDismiss = { activeTool = null },
        )
        else -> Unit
    }
}

/** Filter layer chooser: the layer grades everything under it. */
@Composable
private fun FilterLayerPicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Filter layer") },
        text = {
            Column {
                Text(
                    "Applies to everything below it for its time range — trim/drag the clip to set the range.",
                    fontSize = 11.sp,
                )
                com.mastar.editor.engine.effects.FilterLibrary.FILTERS.forEach { f ->
                    TextButton(onClick = { onPick(f.id) }) {
                        Text("${f.displayName}  ·  ${f.category}")
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Bordered, draggable placement box for the selected PIP clip — drag it
 * around the preview to position the layer (commits on release).
 */
@Composable
private fun androidx.compose.foundation.layout.BoxWithConstraintsScope.TransformGestureBox(
    positionX: Float,
    positionY: Float,
    scale: Float,
    mediaAspect: Float,
    onCommit: (x: Float, y: Float, scale: Float) -> Unit,
) {
    val density = LocalDensity.current
    val widthPx = with(density) { maxWidth.toPx() }
    val heightPx = with(density) { maxHeight.toPx() }
    var dragOffset by remember(positionX, positionY, scale) { mutableStateOf(Offset.Zero) }
    var zoomFactor by remember(positionX, positionY, scale) { mutableStateOf(1f) }

    val liveScale = (scale * zoomFactor).coerceIn(0.1f, 3f)
    // Box matches the MEDIA's aspect ratio (scale=1 spans the short side).
    val longSidePx = (minOf(widthPx, heightPx) * liveScale).coerceAtLeast(56f)
    val boxW = if (mediaAspect >= 1f) longSidePx else longSidePx * mediaAspect
    val boxH = if (mediaAspect >= 1f) longSidePx / mediaAspect else longSidePx
    val centerX = positionX * widthPx + dragOffset.x
    val centerY = positionY * heightPx + dragOffset.y

    fun commit() {
        onCommit(
            ((positionX * widthPx + dragOffset.x) / widthPx).coerceIn(0f, 1f),
            ((positionY * heightPx + dragOffset.y) / heightPx).coerceIn(0f, 1f),
            liveScale,
        )
        dragOffset = Offset.Zero
        zoomFactor = 1f
    }

    Box(
        Modifier
            .offset {
                IntOffset(
                    (centerX - boxW / 2).roundToInt(),
                    (centerY - boxH / 2).roundToInt(),
                )
            }
            .size(
                width = with(density) { boxW.toDp() },
                height = with(density) { boxH.toDp() },
            )
            .border(1.5.dp, Color.White.copy(alpha = 0.9f))
            .pointerInput(positionX, positionY, scale) {
                awaitEachGesture {
                    awaitFirstDown(requireUnconsumed = false)
                    do {
                        val event = awaitPointerEvent()
                        val zoom = event.calculateZoom()
                        val pan = event.calculatePan()
                        if (zoom != 1f || pan != Offset.Zero) {
                            zoomFactor *= zoom
                            dragOffset += pan
                            event.changes.forEach { it.consume() }
                        }
                    } while (event.changes.any { it.pressed })
                    commit()
                }
            },
    )
}

@Composable
private fun RenameDialog(
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename clip") },
        text = {
            OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true)
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text) }) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

private fun probeDurationMs(context: Context, uri: Uri): Long =
    probeMedia(context, uri).durationMs

private data class MediaProbe(val durationMs: Long, val width: Int, val height: Int)

/** Duration + native pixel size (rotation-corrected) in one retriever pass. */
private fun probeMedia(context: Context, uri: Uri): MediaProbe {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        var w = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: 0
        var h = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: 0
        val rotation = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
            ?.toIntOrNull() ?: 0
        if (rotation == 90 || rotation == 270) {
            val tmp = w; w = h; h = tmp
        }
        MediaProbe(durationMs, w, h)
    } catch (e: Exception) {
        MediaProbe(0, 0, 0)
    } finally {
        retriever.release()
    }
}

/** Pixel size of an image without decoding it. */
private fun probeImageSize(context: Context, uri: Uri): Pair<Int, Int> = runCatching {
    context.contentResolver.openInputStream(uri)?.use { input ->
        val opts = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
        android.graphics.BitmapFactory.decodeStream(input, null, opts)
        opts.outWidth.coerceAtLeast(0) to opts.outHeight.coerceAtLeast(0)
    } ?: (0 to 0)
}.getOrDefault(0 to 0)
