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
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Upload
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import androidx.media3.ui.PlayerView
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.mastar.editor.R
import com.mastar.editor.data.db.ClipType
import com.mastar.editor.engine.export.ExportEngine
import com.mastar.editor.ui.timeline.TimelineView
import com.mastar.editor.ui.timeline.rememberTimelineState
import kotlinx.coroutines.delay

/**
 * The editing workspace: preview + overlays on top, toolbar and clip
 * inspector in the middle, pro timeline at the bottom.
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
    val timelineState = rememberTimelineState()
    var showTextDialog by remember { mutableStateOf(false) }
    var showStickerPicker by remember { mutableStateOf(false) }

    var isPlaying by remember { mutableStateOf(false) }

    // While playing, follow playback: move the playhead + auto-scroll the
    // timeline. 30Hz keeps it smooth without hammering recomposition.
    LaunchedEffect(project) {
        while (true) {
            isPlaying = viewModel.previewEngine.player.isPlaying
            if (isPlaying) {
                val posMs = viewModel.previewEngine.currentTimelinePositionMs()
                timelineState.playheadMs = posMs
                timelineState.scrollPx = timelineState.msToPx(posMs)
            }
            delay(33)
        }
    }

    val pickVideo = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        context.contentResolver.takePersistableUriPermission(
            uri, android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        // Duration probe is fast (header read only); coroutine-ify in polish.
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
        // Preview surface + live overlays (text/stickers at the playhead).
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
            PreviewOverlays(viewModel, timelineState.playheadMs)
        }

        EditorToolbar(
            isPlaying = isPlaying,
            onBack = onBack,
            onAddClip = { pickVideo.launch(arrayOf("video/*")) },
            onAddText = { showTextDialog = true },
            onAddSticker = { showStickerPicker = true },
            onPlayPause = viewModel::togglePlayback,
            onSplit = { viewModel.splitSelectedClipAtPlayhead(timelineState.playheadMs) },
            onDelete = viewModel::deleteSelectedClip,
            onExport = { viewModel.export(context.getExternalFilesDir(null) ?: context.filesDir) },
        )
        ExportStatus(exportState)

        // Inspector appears only with a selection; timeline shrinks to fit.
        viewModel.selectedClip()?.let { clip ->
            ClipInspector(clip = clip, onUpdate = { viewModel.updateClip(clip.id, it) })
        }

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

    if (showTextDialog) {
        AddTextDialog(
            onConfirm = { text ->
                viewModel.addTextClip(text, timelineState.playheadMs)
                showTextDialog = false
            },
            onDismiss = { showTextDialog = false },
        )
    }

    if (showStickerPicker) {
        StickerPicker(
            onPick = { asset ->
                viewModel.addStickerClip(asset, timelineState.playheadMs)
                showStickerPicker = false
            },
            onDismiss = { showStickerPicker = false },
        )
    }
}

/** Text + Lottie sticker overlays for whatever sits under the playhead. */
@UnstableApi
@Composable
private fun PreviewOverlays(viewModel: EditorViewModel, playheadMs: Long) {
    val overlays = viewModel.overlaysAt(playheadMs)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        overlays.forEach { clip ->
            when (clip.type) {
                ClipType.TEXT -> Text(
                    text = clip.payload.orEmpty(),
                    color = Color.White,
                    fontSize = 28.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(24.dp),
                )
                ClipType.STICKER -> {
                    val composition by rememberLottieComposition(
                        LottieCompositionSpec.Asset("stickers/${clip.payload}")
                    )
                    LottieAnimation(
                        composition = composition,
                        iterations = LottieConstants.IterateForever,
                        modifier = Modifier.size(120.dp),
                    )
                }
                else -> Unit
            }
        }
    }
}

@Composable
private fun EditorToolbar(
    isPlaying: Boolean,
    onBack: () -> Unit,
    onAddClip: () -> Unit,
    onAddText: () -> Unit,
    onAddSticker: () -> Unit,
    onPlayPause: () -> Unit,
    onSplit: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
        }
        IconButton(onClick = onAddClip) {
            Icon(Icons.Default.Add, contentDescription = "Add clip")
        }
        IconButton(onClick = onAddText) {
            Icon(Icons.Default.TextFields, contentDescription = stringResource(R.string.add_text))
        }
        IconButton(onClick = onAddSticker) {
            Icon(
                Icons.Default.EmojiEmotions,
                contentDescription = stringResource(R.string.add_sticker),
            )
        }
        IconButton(onClick = onPlayPause) {
            Icon(
                if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                contentDescription = "Play/Pause",
            )
        }
        IconButton(onClick = onSplit) {
            Icon(Icons.Default.ContentCut, contentDescription = stringResource(R.string.split))
        }
        IconButton(onClick = onDelete) {
            Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.delete_clip))
        }
        IconButton(onClick = onExport) {
            Icon(Icons.Default.Upload, contentDescription = stringResource(R.string.export))
        }
    }
}

@Composable
private fun ExportStatus(exportState: ExportEngine.State) {
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

@Composable
private fun AddTextDialog(onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_text)) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                placeholder = { Text(stringResource(R.string.text_hint)) },
                singleLine = false,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (text.isNotBlank()) onConfirm(text.trim()) },
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
private fun StickerPicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_sticker)) },
        text = {
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                BUNDLED_STICKERS.forEach { asset ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        val composition by rememberLottieComposition(
                            LottieCompositionSpec.Asset("stickers/$asset")
                        )
                        LottieAnimation(
                            composition = composition,
                            iterations = LottieConstants.IterateForever,
                            modifier = Modifier.size(72.dp),
                        )
                        TextButton(onClick = { onPick(asset) }) { Text("Use") }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/** Bundled offline, like everything else. */
private val BUNDLED_STICKERS = listOf("star_spin.json", "pulse_heart.json")
