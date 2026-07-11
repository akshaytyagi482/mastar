package com.mastar.editor.ui.editor

import android.app.Application
import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.mastar.editor.ui.timeline.TimelineView
import com.mastar.editor.ui.timeline.rememberTimelineState
import kotlinx.coroutines.delay

/**
 * CapCut-style editing workspace:
 *   top bar (close / aspect / export)
 *   preview + overlays
 *   transport row (time, frame stepping, undo/redo, play)
 *   timeline with filmstrips + trim handles
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

        // Preview + live overlays.
        Box(
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
            previewError?.let { message ->
                androidx.compose.material3.Text(
                    text = message,
                    color = Color(0xFFFF5252),
                    modifier = Modifier.align(androidx.compose.ui.Alignment.BottomStart)
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
            onSelectClip = { id ->
                viewModel.selectClip(id)
                if (id == null && activeTool?.needsSelection == true) activeTool = null
            },
            onMoveClip = viewModel::moveClip,
            onTrimClip = viewModel::trimClip,
            onSeek = viewModel::seekTo,
            onAddAudio = { pickAudio.launch(arrayOf("audio/*")) },
            modifier = Modifier.weight(1f),
        )

        // Slide-up tool panel (speed/volume/filter/adjust/...) above the tabs.
        activeTool?.let { tool ->
            ToolPanel(
                tool = tool,
                viewModel = viewModel,
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
