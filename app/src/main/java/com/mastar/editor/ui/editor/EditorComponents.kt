package com.mastar.editor.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Redo
import androidx.compose.material.icons.automirrored.filled.Undo
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AcUnit
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ContentCut
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EmojiEmotions
import androidx.compose.material.icons.filled.Flip
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PictureInPicture
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Rotate90DegreesCw
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material.icons.filled.Transform
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.LottieConstants
import com.airbnb.lottie.compose.rememberLottieComposition
import com.mastar.editor.R
import com.mastar.editor.data.db.ClipType
import com.mastar.editor.engine.effects.EffectResolver
import com.mastar.editor.engine.effects.FilterLibrary
import com.mastar.editor.engine.effects.TextPayload
import com.mastar.editor.engine.export.ExportEngine
import com.mastar.editor.ui.theme.CharcoalSurface
import com.mastar.editor.ui.theme.Saffron

/** Tools on the CapCut-style bottom bar. */
enum class EditorTool(
    val icon: ImageVector,
    val label: String,
    val needsSelection: Boolean,
) {
    ADD_CLIP(Icons.Default.Add, "Video", false),
    PHOTO(Icons.Default.Image, "Photo", false),
    PIP(Icons.Default.PictureInPicture, "Overlay", false),
    AUDIO(Icons.Default.MusicNote, "Audio", false),
    EXTRACT(Icons.Default.GraphicEq, "Extract", false),
    TEXT(Icons.Default.TextFields, "Text", false),
    STICKER(Icons.Default.EmojiEmotions, "Sticker", false),
    SPLIT(Icons.Default.ContentCut, "Split", true),
    DUPLICATE(Icons.Default.ContentCopy, "Copy", true),
    SPEED(Icons.Default.Speed, "Speed", true),
    VOLUME(Icons.AutoMirrored.Filled.VolumeUp, "Volume", true),
    VOICE(Icons.Default.RecordVoiceOver, "Voice", true),
    FILTER(Icons.Default.Tune, "Filter", true),
    ADJUST(Icons.Default.GraphicEq, "Adjust", true),
    TRANSFORM(Icons.Default.Transform, "Transform", true),
    FREEZE(Icons.Default.AcUnit, "Freeze", true),
    TRANSITION(Icons.Default.SwapHoriz, "Transition", true),
    DELETE(Icons.Default.Delete, "Delete", true),
}

private val ROOT_TOOLS = listOf(
    EditorTool.ADD_CLIP, EditorTool.PHOTO, EditorTool.PIP, EditorTool.AUDIO,
    EditorTool.EXTRACT, EditorTool.TEXT, EditorTool.STICKER,
)
private val CLIP_TOOLS = listOf(
    EditorTool.SPLIT, EditorTool.DUPLICATE, EditorTool.SPEED, EditorTool.VOLUME,
    EditorTool.VOICE, EditorTool.FILTER, EditorTool.ADJUST, EditorTool.TRANSFORM,
    EditorTool.FREEZE, EditorTool.TRANSITION, EditorTool.DELETE,
)

/** CapCut-style aspect presets. */
data class AspectPreset(val label: String, val width: Int, val height: Int)

val ASPECT_PRESETS = listOf(
    AspectPreset("9:16", 1080, 1920),
    AspectPreset("16:9", 1920, 1080),
    AspectPreset("1:1", 1080, 1080),
    AspectPreset("4:5", 1080, 1350),
    AspectPreset("3:4", 1080, 1440),
)

/** Top bar: close / aspect ratio / export — like CapCut's header. */
@Composable
fun EditorTopBar(
    canvasWidth: Int,
    canvasHeight: Int,
    exportState: ExportEngine.State,
    onBack: () -> Unit,
    onSetCanvas: (width: Int, height: Int) -> Unit,
    onExportClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = onBack) {
            Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
        }
        Spacer(Modifier.weight(1f))

        AspectChip(canvasWidth, canvasHeight, onSetCanvas)
        Spacer(Modifier.width(10.dp))

        when (exportState) {
            is ExportEngine.State.Exporting -> Text(
                "${exportState.progressPercent}%",
                color = Saffron,
                fontSize = 13.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            is ExportEngine.State.Done -> Text(
                "Saved ✓",
                color = Color(0xFF4CAF50),
                fontSize = 13.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            is ExportEngine.State.Failed -> Text(
                "Failed",
                color = Color(0xFFFF5252),
                fontSize = 13.sp,
                modifier = Modifier.padding(end = 8.dp),
            )
            ExportEngine.State.Idle -> Unit
        }

        Box(
            Modifier
                .background(Saffron, RoundedCornerShape(16.dp))
                .clickable(onClick = onExportClick)
                .padding(horizontal = 16.dp, vertical = 6.dp),
        ) {
            Text(
                stringResource(R.string.export),
                color = Color.Black,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

@Composable
private fun AspectChip(canvasWidth: Int, canvasHeight: Int, onSetCanvas: (Int, Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val current = ASPECT_PRESETS.firstOrNull {
        it.width == canvasWidth && it.height == canvasHeight
    }?.label ?: "${canvasWidth}x${canvasHeight}"

    Box {
        Row(
            Modifier
                .background(CharcoalSurface, RoundedCornerShape(14.dp))
                .clickable { open = true }
                .padding(horizontal = 10.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(current, color = Color.White, fontSize = 12.sp)
            Icon(
                Icons.Default.KeyboardArrowDown,
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(16.dp),
            )
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            ASPECT_PRESETS.forEach { preset ->
                DropdownMenuItem(
                    text = { Text("${preset.label}  (${preset.width}x${preset.height})") },
                    onClick = { onSetCanvas(preset.width, preset.height); open = false },
                )
            }
        }
    }
}

/** Under-preview row: time, frame stepping, play, undo/redo. */
@Composable
fun TransportRow(
    positionMs: Long,
    durationMs: Long,
    isPlaying: Boolean,
    canUndo: Boolean,
    canRedo: Boolean,
    onPlayPause: () -> Unit,
    onStepFrame: (deltaMs: Long) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(44.dp),
    ) {
        Text(
            "${formatClock(positionMs)} / ${formatClock(durationMs)}",
            color = Color.White.copy(alpha = 0.8f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.CenterStart)
                .padding(start = 14.dp),
        )
        Row(
            Modifier.align(Alignment.Center),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // Frame stepping: 1 frame at 30fps.
            IconButton(onClick = { onStepFrame(-33L) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.KeyboardArrowLeft,
                    contentDescription = "Previous frame",
                    tint = Color.White.copy(alpha = 0.8f),
                )
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = Color.White,
                    modifier = Modifier.size(30.dp),
                )
            }
            IconButton(onClick = { onStepFrame(33L) }, modifier = Modifier.size(32.dp)) {
                Icon(
                    Icons.Default.KeyboardArrowRight,
                    contentDescription = "Next frame",
                    tint = Color.White.copy(alpha = 0.8f),
                )
            }
        }
        Row(Modifier.align(Alignment.CenterEnd)) {
            IconButton(onClick = onUndo, enabled = canUndo) {
                Icon(
                    Icons.AutoMirrored.Filled.Undo,
                    contentDescription = "Undo",
                    tint = if (canUndo) Color.White else Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp),
                )
            }
            IconButton(onClick = onRedo, enabled = canRedo) {
                Icon(
                    Icons.AutoMirrored.Filled.Redo,
                    contentDescription = "Redo",
                    tint = if (canRedo) Color.White else Color.White.copy(alpha = 0.3f),
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

private fun formatClock(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d".format(totalSec / 60, totalSec % 60)
}

/** Icon+label tool tabs, contextual like CapCut (clip selected = edit tools). */
@Composable
fun BottomToolBar(
    hasSelection: Boolean,
    activeTool: EditorTool?,
    onTool: (EditorTool) -> Unit,
) {
    val tools = if (hasSelection) CLIP_TOOLS else ROOT_TOOLS
    Row(
        Modifier
            .fillMaxWidth()
            .background(Color.Black)
            .horizontalScroll(rememberScrollState())
            .padding(vertical = 6.dp, horizontal = 4.dp),
    ) {
        tools.forEach { tool ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable { onTool(tool) }
                    .padding(horizontal = 12.dp, vertical = 2.dp),
            ) {
                Icon(
                    tool.icon,
                    contentDescription = tool.label,
                    tint = if (activeTool == tool) Saffron else Color.White,
                    modifier = Modifier.size(22.dp),
                )
                Text(
                    tool.label,
                    color = if (activeTool == tool) Saffron else Color.White.copy(alpha = 0.85f),
                    fontSize = 10.sp,
                )
            }
        }
    }
}

/** Slide-up panel for the active tool, CapCut-style. */
@UnstableApi
@Composable
fun ToolPanel(
    tool: EditorTool,
    viewModel: EditorViewModel,
    onClose: () -> Unit,
) {
    val clip = viewModel.selectedClip() ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .heightIn(max = 240.dp)
            .background(CharcoalSurface)
            .padding(horizontal = 14.dp, vertical = 8.dp)
            .verticalScroll(rememberScrollState()),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                tool.label,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = onClose, modifier = Modifier.size(28.dp)) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "Close panel",
                    tint = Color.White,
                    modifier = Modifier.size(16.dp),
                )
            }
        }
        when (tool) {
            EditorTool.SPEED -> {
                var speed by remember(clip.id) { mutableFloatStateOf(clip.speed) }
                PanelSlider(
                    label = "${"%.2f".format(speed)}x",
                    value = speed,
                    range = 0.25f..4f,
                    onValue = { speed = it },
                    onCommit = { viewModel.updateClip(clip.id) { it.copy(speed = speed) } },
                )
            }
            EditorTool.VOLUME -> {
                var volume by remember(clip.id) { mutableFloatStateOf(clip.volume) }
                var fadeIn by remember(clip.id) { mutableFloatStateOf(clip.fadeInMs / 1000f) }
                var fadeOut by remember(clip.id) { mutableFloatStateOf(clip.fadeOutMs / 1000f) }
                PanelSlider(
                    label = "Volume ${(volume * 100).toInt()}%",
                    value = volume,
                    range = 0f..2f,
                    onValue = { volume = it },
                    onCommit = { viewModel.updateClip(clip.id) { it.copy(volume = volume) } },
                )
                PanelSlider(
                    label = "Fade in ${"%.1f".format(fadeIn)}s",
                    value = fadeIn,
                    range = 0f..5f,
                    onValue = { fadeIn = it },
                    onCommit = {
                        viewModel.updateClip(clip.id) { it.copy(fadeInMs = (fadeIn * 1000).toLong()) }
                    },
                )
                PanelSlider(
                    label = "Fade out ${"%.1f".format(fadeOut)}s",
                    value = fadeOut,
                    range = 0f..5f,
                    onValue = { fadeOut = it },
                    onCommit = {
                        viewModel.updateClip(clip.id) { it.copy(fadeOutMs = (fadeOut * 1000).toLong()) }
                    },
                )
            }
            EditorTool.VOICE -> ChipRow(
                options = EffectResolver.VOICE_EFFECTS.map { it.id to it.displayName },
                selectedId = clip.voiceEffectId,
                onSelect = { id -> viewModel.updateClip(clip.id) { it.copy(voiceEffectId = id) } },
            )
            EditorTool.FILTER -> {
                ChipRow(
                    options = FilterLibrary.FILTERS.map { it.id to "${it.displayName} · ${it.category}" },
                    selectedId = clip.filterId,
                    onSelect = { id -> viewModel.updateClip(clip.id) { it.copy(filterId = id) } },
                )
                if (clip.filterId != null) {
                    var intensity by remember(clip.id, clip.filterId) {
                        mutableFloatStateOf(clip.filterIntensity)
                    }
                    PanelSlider(
                        label = "Intensity ${(intensity * 100).toInt()}%",
                        value = intensity,
                        range = 0f..1f,
                        onValue = { intensity = it },
                        onCommit = {
                            viewModel.updateClip(clip.id) { it.copy(filterIntensity = intensity) }
                        },
                    )
                }
            }
            EditorTool.ADJUST -> {
                AdjustSlider("Brightness", clip.adjustBrightness) { v ->
                    viewModel.updateClip(clip.id) { it.copy(adjustBrightness = v) }
                }
                AdjustSlider("Contrast", clip.adjustContrast) { v ->
                    viewModel.updateClip(clip.id) { it.copy(adjustContrast = v) }
                }
                AdjustSlider("Saturation", clip.adjustSaturation) { v ->
                    viewModel.updateClip(clip.id) { it.copy(adjustSaturation = v) }
                }
                AdjustSlider("Hue", clip.adjustHue) { v ->
                    viewModel.updateClip(clip.id) { it.copy(adjustHue = v) }
                }
                AdjustSlider("Temperature", clip.adjustTemperature) { v ->
                    viewModel.updateClip(clip.id) { it.copy(adjustTemperature = v) }
                }
                AdjustSlider("Tint", clip.adjustTint) { v ->
                    viewModel.updateClip(clip.id) { it.copy(adjustTint = v) }
                }
            }
            EditorTool.TRANSFORM -> {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        viewModel.updateClip(clip.id) {
                            it.copy(rotationDeg = (it.rotationDeg + 90f) % 360f)
                        }
                    }) {
                        Icon(
                            Icons.Default.Rotate90DegreesCw,
                            contentDescription = "Rotate 90",
                            tint = Color.White,
                        )
                    }
                    Text("${clip.rotationDeg.toInt()}°", color = Color.White, fontSize = 12.sp)
                    Spacer(Modifier.width(14.dp))
                    Icon(Icons.Default.Flip, contentDescription = null, tint = Color.White)
                    Text(" Flip H", color = Color.White, fontSize = 12.sp)
                    Switch(
                        checked = clip.flipH,
                        onCheckedChange = { v ->
                            viewModel.updateClip(clip.id) { it.copy(flipH = v) }
                        },
                    )
                    Text(" V", color = Color.White, fontSize = 12.sp)
                    Switch(
                        checked = clip.flipV,
                        onCheckedChange = { v ->
                            viewModel.updateClip(clip.id) { it.copy(flipV = v) }
                        },
                    )
                }
                var scale by remember(clip.id) { mutableFloatStateOf(clip.scale) }
                PanelSlider(
                    label = "Scale ${(scale * 100).toInt()}%",
                    value = scale,
                    range = 0.25f..2f,
                    onValue = { scale = it },
                    onCommit = { viewModel.updateClip(clip.id) { it.copy(scale = scale) } },
                )
                var opacity by remember(clip.id) { mutableFloatStateOf(clip.opacity) }
                PanelSlider(
                    label = "Opacity ${(opacity * 100).toInt()}%",
                    value = opacity,
                    range = 0f..1f,
                    onValue = { opacity = it },
                    onCommit = { viewModel.updateClip(clip.id) { it.copy(opacity = opacity) } },
                )
                // PIP layers can also be positioned on the canvas.
                if (viewModel.isOverlayClip(clip.id)) {
                    var posX by remember(clip.id) { mutableFloatStateOf(clip.positionX) }
                    PanelSlider(
                        label = "Position X",
                        value = posX,
                        range = 0f..1f,
                        onValue = { posX = it },
                        onCommit = { viewModel.updateClip(clip.id) { it.copy(positionX = posX) } },
                    )
                    var posY by remember(clip.id) { mutableFloatStateOf(clip.positionY) }
                    PanelSlider(
                        label = "Position Y",
                        value = posY,
                        range = 0f..1f,
                        onValue = { posY = it },
                        onCommit = { viewModel.updateClip(clip.id) { it.copy(positionY = posY) } },
                    )
                }
            }
            EditorTool.TRANSITION -> ChipRow(
                options = TRANSITIONS,
                selectedId = clip.transitionId,
                onSelect = { id ->
                    viewModel.updateClip(clip.id) {
                        it.copy(
                            transitionId = id,
                            transitionDurationMs = if (id == null) 0 else 500,
                        )
                    }
                },
            )
            else -> Unit
        }
    }
}

@Composable
private fun PanelSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onValue: (Float) -> Unit,
    onCommit: () -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 11.sp,
            modifier = Modifier.width(110.dp),
        )
        Slider(
            value = value,
            onValueChange = onValue,
            onValueChangeFinished = onCommit,
            valueRange = range,
            modifier = Modifier
                .weight(1f)
                .height(28.dp),
        )
    }
}

/** -1..1 adjustment slider committed on release. */
@Composable
private fun AdjustSlider(label: String, initial: Float, onCommit: (Float) -> Unit) {
    var value by remember(label, initial) { mutableFloatStateOf(initial) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            "$label ${(value * 100).toInt()}",
            color = Color.White.copy(alpha = 0.9f),
            fontSize = 11.sp,
            modifier = Modifier.width(110.dp),
        )
        Slider(
            value = value,
            onValueChange = { value = it },
            onValueChangeFinished = { onCommit(value) },
            valueRange = -1f..1f,
            modifier = Modifier
                .weight(1f)
                .height(28.dp),
        )
    }
}

@Composable
private fun ChipRow(
    options: List<Pair<String, String>>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = selectedId == null,
            onClick = { onSelect(null) },
            label = { Text(stringResource(R.string.none)) },
        )
        options.forEach { (id, name) ->
            FilterChip(
                selected = selectedId == id,
                onClick = { onSelect(id) },
                label = { Text(name) },
            )
        }
    }
}

/** id -> display name; ids match assets/transitions/<id>.glsl */
private val TRANSITIONS = listOf(
    "fade" to "Fade",
    "directionalwipe" to "Wipe",
    "circleopen" to "Circle",
    "wiperight" to "Slide",
)

/** CapCut-style export sheet: resolution + quality. */
@Composable
fun ExportDialog(
    onExport: (ExportEngine.Settings) -> Unit,
    onDismiss: () -> Unit,
) {
    var resolution by remember { mutableStateOf(1080) }
    var bitrate by remember { mutableStateOf(10_000_000) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.export)) },
        text = {
            Column {
                Text("Resolution", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(480, 720, 1080, 1440).forEach { r ->
                        FilterChip(
                            selected = resolution == r,
                            onClick = { resolution = r },
                            label = { Text(if (r == 1440) "2K" else "${r}p") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                Text("Quality", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    listOf(
                        "Low" to 4_000_000,
                        "Medium" to 10_000_000,
                        "High" to 18_000_000,
                    ).forEach { (label, b) ->
                        FilterChip(
                            selected = bitrate == b,
                            onClick = { bitrate = b },
                            label = { Text(label) },
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onExport(ExportEngine.Settings(resolution = resolution, bitrate = bitrate))
            }) { Text(stringResource(R.string.export)) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

/**
 * Lottie sticker overlays at the playhead. Text is no longer rendered here —
 * it's burned into the preview composition itself (true WYSIWYG).
 */
@UnstableApi
@Composable
fun PreviewOverlays(viewModel: EditorViewModel, playheadMs: Long) {
    val stickers = viewModel.stickersAt(playheadMs)
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        stickers.forEach { clip ->
            val composition by rememberLottieComposition(
                LottieCompositionSpec.Asset("stickers/${clip.payload}")
            )
            LottieAnimation(
                composition = composition,
                iterations = LottieConstants.IterateForever,
                modifier = Modifier.size(120.dp),
            )
        }
    }
}

private fun composeFont(font: String): FontFamily = when (font) {
    "serif" -> FontFamily.Serif
    "mono" -> FontFamily.Monospace
    "cursive" -> FontFamily.Cursive
    else -> FontFamily.SansSerif
}

/** Styled text editor: content, color, size, weight, font, background. */
@Composable
fun AddTextDialog(onConfirm: (TextPayload) -> Unit, onDismiss: () -> Unit) {
    var text by remember { mutableStateOf("") }
    var color by remember { mutableStateOf(TextPayload.COLORS.first()) }
    var sizeSp by remember { mutableFloatStateOf(28f) }
    var bold by remember { mutableStateOf(false) }
    var background by remember { mutableStateOf(false) }
    var font by remember { mutableStateOf("sans") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_text)) },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    placeholder = { Text(stringResource(R.string.text_hint)) },
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextPayload.COLORS.forEach { c ->
                        Box(
                            Modifier
                                .size(24.dp)
                                .clip(CircleShape)
                                .background(Color(c))
                                .then(
                                    if (color == c) Modifier.padding(2.dp).clip(CircleShape)
                                        .background(Color(c)) else Modifier
                                )
                                .clickable { color = c },
                        ) {
                            if (color == c) {
                                Box(
                                    Modifier
                                        .align(Alignment.Center)
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(
                                            if (c == 0xFF000000) Color.White else Color.Black
                                        )
                                )
                            }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TextPayload.FONTS.forEach { f ->
                        FilterChip(
                            selected = font == f,
                            onClick = { font = f },
                            label = { Text(f, fontFamily = composeFont(f)) },
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Size ${sizeSp.toInt()}", fontSize = 11.sp, modifier = Modifier.width(56.dp))
                    Slider(
                        value = sizeSp,
                        onValueChange = { sizeSp = it },
                        valueRange = 14f..64f,
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Bold", fontSize = 11.sp)
                    Switch(checked = bold, onCheckedChange = { bold = it })
                    Spacer(Modifier.width(10.dp))
                    Text("Background", fontSize = 11.sp)
                    Switch(checked = background, onCheckedChange = { background = it })
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (text.isNotBlank()) {
                        onConfirm(
                            TextPayload(
                                text = text.trim(),
                                color = color,
                                sizeSp = sizeSp.toInt(),
                                bold = bold,
                                background = background,
                                font = font,
                            )
                        )
                    }
                },
            ) { Text("OK") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel") }
        },
    )
}

@Composable
fun StickerPicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
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
