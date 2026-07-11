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
import androidx.compose.material.icons.filled.Timeline
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
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
import com.mastar.editor.data.db.EasingType
import com.mastar.editor.data.db.KeyframeProperty
import com.mastar.editor.engine.effects.EffectResolver
import com.mastar.editor.engine.effects.FilterLibrary
import com.mastar.editor.engine.effects.TextPayload
import com.mastar.editor.engine.effects.Transitions
import com.mastar.editor.engine.speed.SpeedCurve
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
    FILTER_LAYER(Icons.Default.Tune, "Filter", false),
    EDIT_TEXT(Icons.Default.TextFields, "Edit", true),
    SPLIT(Icons.Default.ContentCut, "Split", true),
    DUPLICATE(Icons.Default.ContentCopy, "Copy", true),
    SPEED(Icons.Default.Speed, "Speed", true),
    VOLUME(Icons.AutoMirrored.Filled.VolumeUp, "Volume", true),
    VOICE(Icons.Default.RecordVoiceOver, "Voice", true),
    FILTER(Icons.Default.Tune, "Filter", true),
    ADJUST(Icons.Default.GraphicEq, "Adjust", true),
    TRANSFORM(Icons.Default.Transform, "Transform", true),
    KEYFRAME(Icons.Default.Timeline, "Keyframe", true),
    FREEZE(Icons.Default.AcUnit, "Freeze", true),
    TRANSITION(Icons.Default.SwapHoriz, "Transition", true),
    DELETE(Icons.Default.Delete, "Delete", true),
}

private val ROOT_TOOLS = listOf(
    EditorTool.ADD_CLIP, EditorTool.PHOTO, EditorTool.PIP, EditorTool.AUDIO,
    EditorTool.EXTRACT, EditorTool.TEXT, EditorTool.STICKER, EditorTool.FILTER_LAYER,
)
private val CLIP_TOOLS = listOf(
    EditorTool.SPLIT, EditorTool.DUPLICATE, EditorTool.SPEED, EditorTool.VOLUME,
    EditorTool.VOICE, EditorTool.FILTER, EditorTool.ADJUST, EditorTool.TRANSFORM,
    EditorTool.KEYFRAME, EditorTool.FREEZE, EditorTool.TRANSITION, EditorTool.DELETE,
)
private val TEXT_TOOLS = listOf(
    EditorTool.EDIT_TEXT, EditorTool.DUPLICATE, EditorTool.KEYFRAME, EditorTool.DELETE,
)
private val FILTER_LAYER_TOOLS = listOf(
    EditorTool.FILTER, EditorTool.DUPLICATE, EditorTool.DELETE,
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
    exportLocation: String?,
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
                if (exportLocation != null) "Saved to Gallery ✓" else "Saved ✓",
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
    snapEnabled: Boolean,
    onToggleSnap: () -> Unit,
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
            // Magnetic snapping toggle.
            IconButton(onClick = onToggleSnap) {
                Text(
                    "⌇",
                    color = if (snapEnabled) Saffron else Color.White.copy(alpha = 0.35f),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
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
    selectedClipType: ClipType?,
    activeTool: EditorTool?,
    onTool: (EditorTool) -> Unit,
) {
    val tools = when {
        !hasSelection -> ROOT_TOOLS
        selectedClipType == ClipType.TEXT || selectedClipType == ClipType.STICKER -> TEXT_TOOLS
        selectedClipType == ClipType.FILTER -> FILTER_LAYER_TOOLS
        else -> CLIP_TOOLS
    }
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
    playheadMs: Long,
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
                // Constant speed (disabled while a ramp is active).
                if (clip.speedCurveJson == null) {
                    var speed by remember(clip.id) { mutableFloatStateOf(clip.speed) }
                    PanelSlider(
                        label = "${"%.2f".format(speed)}x",
                        value = speed,
                        range = 0.25f..4f,
                        onValue = { speed = it },
                        onCommit = { viewModel.updateClip(clip.id) { it.copy(speed = speed) } },
                    )
                }
                // Speed ramp presets (CapCut's curve speed).
                Row(
                    Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    FilterChip(
                        selected = clip.speedCurveJson == null,
                        onClick = {
                            viewModel.updateClip(clip.id) { it.copy(speedCurveJson = null) }
                        },
                        label = { Text("Constant") },
                    )
                    SpeedCurve.PRESETS.forEach { (name, curve) ->
                        FilterChip(
                            selected = clip.speedCurveJson == curve.toJson(),
                            onClick = {
                                viewModel.updateClip(clip.id) {
                                    it.copy(speedCurveJson = curve.toJson())
                                }
                            },
                            label = { Text(name) },
                        )
                    }
                }
                // Interactive ramp graph: drag points vertically.
                SpeedCurve.parse(clip.speedCurveJson)?.let { curve ->
                    SpeedRampGraph(
                        curve = curve,
                        onCommit = { newCurve ->
                            viewModel.updateClip(clip.id) {
                                it.copy(speedCurveJson = newCurve.toJson())
                            }
                        },
                    )
                }
            }
            EditorTool.KEYFRAME -> {
                val allKeyframes by viewModel.keyframes.collectAsState()
                val clipKeyframes = allKeyframes.filter { it.clipId == clip.id }
                var selectedDiamond by remember(clip.id) { mutableStateOf<Long?>(null) }
                Text(
                    "Add a diamond, move the playhead, change Transform — it " +
                        "auto-keys the next diamond. Tap a diamond to set its curve.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                )
                TextButton(onClick = { viewModel.addKeyframeAtPlayhead(playheadMs) }) {
                    Text("◆  Add keyframe at playhead", color = Saffron)
                }
                if (clipKeyframes.isNotEmpty()) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        clipKeyframes.map { it.timeMs }.distinct().sorted().forEach { t ->
                            FilterChip(
                                selected = selectedDiamond == t,
                                onClick = {
                                    selectedDiamond = if (selectedDiamond == t) null else t
                                },
                                label = { Text("◆ %.1fs".format(t / 1000f)) },
                            )
                        }
                    }
                }
                selectedDiamond?.let { t ->
                    val easingAt = clipKeyframes.firstOrNull { it.timeMs == t }?.easing
                    EasingGraphRow(
                        selected = easingAt,
                        onSelect = { easing -> viewModel.setKeyframeEasing(t, easing) },
                        onDelete = {
                            viewModel.removeKeyframesAt(t)
                            selectedDiamond = null
                        },
                    )
                }
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
                // Values read AT the playhead (keyframe-aware); commits go
                // through setClipTransform, which auto-keys when diamonds
                // exist — CapCut behavior.
                val hasKeyframes = viewModel.keyframesFor(clip.id).isNotEmpty()
                val current = viewModel.transformValuesAt(clip.id, playheadMs)
                if (hasKeyframes) {
                    Text(
                        "◆ Auto-keyframing: edits drop a diamond at the playhead",
                        color = Saffron,
                        fontSize = 10.sp,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = {
                        val rot = (current?.rotationDeg ?: clip.rotationDeg) + 90f
                        viewModel.setClipTransform(
                            clip.id, playheadMs,
                            mapOf(KeyframeProperty.ROTATION to rot % 360f),
                        )
                    }) {
                        Icon(
                            Icons.Default.Rotate90DegreesCw,
                            contentDescription = "Rotate 90",
                            tint = Color.White,
                        )
                    }
                    Text(
                        "${(current?.rotationDeg ?: clip.rotationDeg).toInt()}°",
                        color = Color.White, fontSize = 12.sp,
                    )
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
                var scale by remember(clip.id, playheadMs, hasKeyframes) {
                    mutableFloatStateOf(current?.scale ?: clip.scale)
                }
                PanelSlider(
                    label = "Scale ${(scale * 100).toInt()}%",
                    value = scale,
                    range = 0.1f..3f,
                    onValue = { scale = it },
                    onCommit = {
                        viewModel.setClipTransform(
                            clip.id, playheadMs, mapOf(KeyframeProperty.SCALE to scale)
                        )
                    },
                )
                var opacity by remember(clip.id, playheadMs, hasKeyframes) {
                    mutableFloatStateOf(current?.opacity ?: clip.opacity)
                }
                PanelSlider(
                    label = "Opacity ${(opacity * 100).toInt()}%",
                    value = opacity,
                    range = 0f..1f,
                    onValue = { opacity = it },
                    onCommit = {
                        viewModel.setClipTransform(
                            clip.id, playheadMs, mapOf(KeyframeProperty.OPACITY to opacity)
                        )
                    },
                )
                var posX by remember(clip.id, playheadMs, hasKeyframes) {
                    mutableFloatStateOf(current?.positionX ?: clip.positionX)
                }
                PanelSlider(
                    label = "Position X",
                    value = posX,
                    range = 0f..1f,
                    onValue = { posX = it },
                    onCommit = {
                        viewModel.setClipTransform(
                            clip.id, playheadMs, mapOf(KeyframeProperty.POSITION_X to posX)
                        )
                    },
                )
                var posY by remember(clip.id, playheadMs, hasKeyframes) {
                    mutableFloatStateOf(current?.positionY ?: clip.positionY)
                }
                PanelSlider(
                    label = "Position Y",
                    value = posY,
                    range = 0f..1f,
                    onValue = { posY = it },
                    onCommit = {
                        viewModel.setClipTransform(
                            clip.id, playheadMs, mapOf(KeyframeProperty.POSITION_Y to posY)
                        )
                    },
                )
            }
            EditorTool.TRANSITION -> {
                Text(
                    "Cross transitions overlap the clips (the timeline gets " +
                        "shorter by the transition, like CapCut).",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 10.sp,
                )
                ChipRow(
                    options = TRANSITIONS,
                    selectedId = clip.transitionId,
                    onSelect = { id -> viewModel.setTransition(clip.id, id) },
                )
            }
            else -> Unit
        }
    }
}

/** CapCut-style "Graphs": curve thumbnails you tap to set the segment easing. */
@Composable
fun EasingGraphRow(
    selected: EasingType?,
    onSelect: (EasingType) -> Unit,
    onDelete: () -> Unit,
) {
    val graphs = listOf(
        EasingType.LINEAR to "Linear",
        EasingType.EASE_IN to "Ease In 1",
        EasingType.EASE_IN_2 to "Ease In 2",
        EasingType.EASE_IN_3 to "Ease In 3",
        EasingType.EASE_OUT to "Ease Out 1",
        EasingType.EASE_OUT_2 to "Ease Out 2",
        EasingType.EASE_OUT_3 to "Ease Out 3",
        EasingType.EASE_IN_OUT to "Smooth",
    )
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text("Graphs", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
        graphs.forEach { (easing, label) ->
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(
                        if (selected == easing) Saffron.copy(alpha = 0.25f)
                        else Color.White.copy(alpha = 0.06f)
                    )
                    .clickable { onSelect(easing) }
                    .padding(6.dp),
            ) {
                androidx.compose.foundation.Canvas(Modifier.size(width = 44.dp, height = 34.dp)) {
                    val w = size.width
                    val h = size.height
                    val steps = 24
                    var prev = Offset(0f, h)
                    for (i in 1..steps) {
                        val x = i / steps.toFloat()
                        val y = com.mastar.editor.engine.keyframe.CubicBezierEasing
                            .forType(easing, x)
                        val point = Offset(x * w, h * (1f - y))
                        drawLine(
                            color = if (selected == easing) Saffron else Color(0xFF6EE7C8),
                            start = prev,
                            end = point,
                            strokeWidth = 3f,
                        )
                        prev = point
                    }
                }
                Text(
                    label,
                    color = if (selected == easing) Saffron else Color.White.copy(alpha = 0.8f),
                    fontSize = 9.sp,
                )
            }
        }
        TextButton(onClick = onDelete) { Text("Delete ✕", color = Color(0xFFFF5252)) }
    }
}

/**
 * Interactive speed ramp: drag control points vertically to shape the curve
 * (top = 4x, bottom = 0.25x). Commits on release.
 */
@Composable
fun SpeedRampGraph(
    curve: SpeedCurve,
    onCommit: (SpeedCurve) -> Unit,
) {
    var points by remember(curve.toJson()) { mutableStateOf(curve.points) }

    fun speedToY(speed: Float, h: Float): Float {
        // Log-ish mapping so 1x sits mid-graph.
        val norm = ((speed - 0.25f) / (4f - 0.25f)).coerceIn(0f, 1f)
        return h * (1f - norm)
    }

    fun yToSpeed(y: Float, h: Float): Float {
        val norm = (1f - y / h).coerceIn(0f, 1f)
        return 0.25f + norm * (4f - 0.25f)
    }

    androidx.compose.foundation.Canvas(
        Modifier
            .fillMaxWidth()
            .height(96.dp)
            .padding(vertical = 4.dp)
            .pointerInput(curve.toJson()) {
                detectDragGestures(
                    onDrag = { change, _ ->
                        change.consume()
                        val w = size.width.toFloat()
                        val h = size.height.toFloat()
                        val nearest = points.indices.minByOrNull { i ->
                            kotlin.math.abs(points[i].first * w - change.position.x)
                        } ?: return@detectDragGestures
                        val updated = points.toMutableList()
                        updated[nearest] = updated[nearest].first to
                            yToSpeed(change.position.y, h)
                        points = updated
                    },
                    onDragEnd = { onCommit(SpeedCurve.fromPoints(points)) },
                    onDragCancel = { onCommit(SpeedCurve.fromPoints(points)) },
                )
            },
    ) {
        val w = size.width
        val h = size.height
        // 1x reference line.
        val oneY = speedToY(1f, h)
        drawLine(Color.White.copy(alpha = 0.2f), Offset(0f, oneY), Offset(w, oneY), 1f)
        // Curve polyline.
        for (i in 0 until points.size - 1) {
            drawLine(
                Saffron,
                Offset(points[i].first * w, speedToY(points[i].second, h)),
                Offset(points[i + 1].first * w, speedToY(points[i + 1].second, h)),
                3f,
            )
        }
        // Draggable control points.
        points.forEach { (f, s) ->
            drawCircle(Color.White, 8f, Offset(f * w, speedToY(s, h)))
            drawCircle(Saffron, 5f, Offset(f * w, speedToY(s, h)))
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

/** Engine-rendered transitions (visible in preview AND export). */
private val TRANSITIONS = Transitions.TRANSITIONS.map { it.id to it.displayName }

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
fun AddTextDialog(
    onConfirm: (TextPayload) -> Unit,
    onDismiss: () -> Unit,
    initial: TextPayload? = null,
) {
    var text by remember { mutableStateOf(initial?.text ?: "") }
    var color by remember { mutableStateOf(initial?.color ?: TextPayload.COLORS.first()) }
    var sizeSp by remember { mutableFloatStateOf(initial?.sizeSp?.toFloat() ?: 28f) }
    var bold by remember { mutableStateOf(initial?.bold ?: false) }
    var background by remember { mutableStateOf(initial?.background ?: false) }
    var font by remember { mutableStateOf(initial?.font ?: "sans") }

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
