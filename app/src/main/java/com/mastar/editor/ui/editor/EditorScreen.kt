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
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
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
            val durationMs = probeDurationMs(context, uri)
            if (durationMs > 0) viewModel.addVideoClip(uri.toString(), durationMs)
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
        val durationMs = if (isImage) 3000L else probeDurationMs(context, uri)
        if (durationMs > 0) {
            viewModel.addPipClip(uri.toString(), isImage, durationMs, timelineState.playheadMs)
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

            // Selected PIP clip: bordered box, draggable to reposition.
            val selectedId = selectedClipId
            if (selectedId != null && viewModel.isOverlayClip(selectedId)) {
                viewModel.clipById(selectedId)?.let { pip ->
                    PipPlacementBox(
                        positionX = pip.positionX,
                        positionY = pip.positionY,
                        scale = pip.scale,
                        onCommit = { x, y ->
                            viewModel.updateClip(selectedId) {
                                it.copy(positionX = x, positionY = y)
                            }
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

/**
 * Bordered, draggable placement box for the selected PIP clip — drag it
 * around the preview to position the layer (commits on release).
 */
@Composable
private fun androidx.compose.foundation.layout.BoxWithConstraintsScope.PipPlacementBox(
    positionX: Float,
    positionY: Float,
    scale: Float,
    onCommit: (x: Float, y: Float) -> Unit,
) {
    val density = LocalDensity.current
    val widthPx = with(density) { maxWidth.toPx() }
    val heightPx = with(density) { maxHeight.toPx() }
    var dragOffset by remember(positionX, positionY) { mutableStateOf(Offset.Zero) }

    val boxSizePx = (minOf(widthPx, heightPx) * scale).coerceAtLeast(48f)
    val centerX = positionX * widthPx + dragOffset.x
    val centerY = positionY * heightPx + dragOffset.y

    Box(
        Modifier
            .offset {
                IntOffset(
                    (centerX - boxSizePx / 2).roundToInt(),
                    (centerY - boxSizePx / 2).roundToInt(),
                )
            }
            .size(with(density) { boxSizePx.toDp() })
            .border(1.5.dp, Color.White.copy(alpha = 0.9f))
            .pointerInput(positionX, positionY) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffset += dragAmount
                    },
                    onDragEnd = {
                        onCommit(
                            ((positionX * widthPx + dragOffset.x) / widthPx).coerceIn(0f, 1f),
                            ((positionY * heightPx + dragOffset.y) / heightPx).coerceIn(0f, 1f),
                        )
                        dragOffset = Offset.Zero
                    },
                    onDragCancel = { dragOffset = Offset.Zero },
                )
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

private fun probeDurationMs(context: Context, uri: Uri): Long {
    val retriever = MediaMetadataRetriever()
    return try {
        retriever.setDataSource(context, uri)
        retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
    } catch (e: Exception) {
        0L
    } finally {
        retriever.release()
    }
}
