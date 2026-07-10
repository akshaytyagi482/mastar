package com.mastar.editor.ui.editor

import android.app.Application
import android.media.MediaMetadataRetriever
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.ui.PlayerView
import com.mastar.editor.engine.export.ExportEngine
import com.mastar.editor.ui.timeline.TimelineView
import com.mastar.editor.ui.timeline.rememberTimelineState

/**
 * The editing workspace: preview on top, toolbar in the middle,
 * pro timeline at the bottom.
 */
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
    val timelineState = rememberTimelineState()

    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        // Duration probe is fast (header read only); coroutine-ify in Phase 2.
        val retriever = MediaMetadataRetriever()
        val durationMs = try {
            retriever.setDataSource(context, uri)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
        if (durationMs > 0) viewModel.addVideoClip(uri.toString(), durationMs)
    }

    Column(Modifier.fillMaxSize()) {
        // Video preview surface driven by the Media3 preview engine.
        Box(
            Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        player = viewModel.previewEngine.player
                        useController = false
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }

        EditorToolbar(
            onBack = onBack,
            onAddClip = { pickVideo.launch(arrayOf("video/*")) },
            onPlayPause = viewModel::togglePlayback,
            onSplit = { viewModel.splitSelectedClipAtPlayhead(timelineState.playheadMs) },
            onDelete = viewModel::deleteSelectedClip,
            onExport = { viewModel.export(context.getExternalFilesDir(null) ?: context.filesDir) },
            exportState = exportState,
        )

        TimelineView(
            state = timelineState,
            tracks = project?.tracks.orEmpty(),
            selectedClipId = selectedClipId,
            onSelectClip = viewModel::selectClip,
            onMoveClip = viewModel::moveClip,
            onSeek = viewModel::seekTo,
            modifier = Modifier.weight(1f),
        )
    }
}

@Composable
private fun EditorToolbar(
    onBack: () -> Unit,
    onAddClip: () -> Unit,
    onPlayPause: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
    exportState: ExportEngine.State,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        IconButton(onClick = onAddClip) {
            Icon(Icons.Default.Add, contentDescription = "Add clip")
        }
        IconButton(onClick = onPlayPause) {
            Icon(Icons.Default.PlayArrow, contentDescription = "Play/Pause")
        }
        IconButton(onClick = onSplit) {
            Icon(Icons.Default.ContentCut, contentDescription = "Split")
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = "Delete")
        }
        IconButton(onClick = onExport) {
            Icon(Icons.Default.Upload, contentDescription = "Export")
        }
    }
    when (exportState) {
        is ExportEngine.State.Exporting -> Text(
            "Exporting… ${exportState.progressPercent}%",
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        is ExportEngine.State.Done -> Text(
            "Saved: ${exportState.outputFile.name}",
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        is ExportEngine.State.Failed -> Text(
            "Export failed: ${exportState.cause.message}",
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        ExportEngine.State.Idle -> Unit
    }
}
