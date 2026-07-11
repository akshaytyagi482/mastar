package com.mastar.editor.ui.timeline

import android.graphics.Paint
import android.media.MediaMetadataRetriever
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.VolumeOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.request.videoFrameOption
import com.mastar.editor.data.db.ClipEntity
import com.mastar.editor.data.db.ClipType
import com.mastar.editor.data.db.TrackType
import com.mastar.editor.data.db.TrackWithClips
import com.mastar.editor.engine.audio.Waveform
import com.mastar.editor.ui.editor.ClipMenuAction
import com.mastar.editor.ui.theme.ClipAudio
import com.mastar.editor.ui.theme.ClipOverlay
import com.mastar.editor.ui.theme.ClipText
import com.mastar.editor.ui.theme.Saffron
import com.mastar.editor.ui.theme.TrackLaneColor
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

private val VIDEO_TRACK_HEIGHT = 56.dp
private val PIP_TRACK_HEIGHT = 40.dp
private val AUDIO_TRACK_HEIGHT = 32.dp
private val OVERLAY_TRACK_HEIGHT = 22.dp
private val RULER_HEIGHT = 22.dp
private val FRAME_WIDTH = 40.dp

/** Everything the timeline can ask the editor to do. */
data class TimelineActions(
    val onSelectClip: (Long?) -> Unit,
    val onMoveClip: (clipId: Long, newTimelineStartMs: Long) -> Unit,
    val onTrimClip: (clipId: Long, newSourceStartMs: Long, newSourceEndMs: Long, newTimelineStartMs: Long) -> Unit,
    val onSeek: (Long) -> Unit,
    val onAddAudio: () -> Unit,
    val onMenuAction: (clipId: Long, action: ClipMenuAction) -> Unit,
    /** Drag a keyframe diamond to a new time within its clip. */
    val onMoveKeyframe: (clipId: Long, fromMs: Long, toMs: Long) -> Unit,
)

/**
 * CapCut-style timeline: fixed centered playhead, content scrolls beneath it,
 * virtualized filmstrip thumbnails (whole clip at any zoom), waveforms on
 * audio, keyframe diamonds, magnetic snapping with haptics, and a long-press
 * clip menu.
 */
@Composable
fun TimelineView(
    state: TimelineState,
    tracks: List<TrackWithClips>,
    selectedClipId: Long?,
    multiSelection: Set<Long>,
    keyframeTimes: (clipId: Long) -> List<Long>,
    actions: TimelineActions,
    modifier: Modifier = Modifier,
) {
    val projectEndMs = tracks.flatMap { it.clips }.maxOfOrNull { it.timelineEndMs } ?: 0L

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            .pointerInput(projectEndMs) {
                detectTransformGestures { _, pan, zoom, _ ->
                    if (zoom != 1f) state.zoomBy(zoom)
                    if (pan.x != 0f) {
                        state.scrollBy(-pan.x, state.msToPx(projectEndMs))
                        state.playheadMs = state.pxToMs(state.scrollPx)
                        actions.onSeek(state.playheadMs)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { actions.onSelectClip(null) })
            },
    ) {
        val viewportWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val allClips = tracks.flatMap { it.clips }
        val hasAudio = tracks.any { it.track.type == TrackType.AUDIO && it.clips.isNotEmpty() }

        Column(Modifier.fillMaxWidth()) {
            TimeRuler(state, viewportWidthPx)
            Spacer(Modifier.height(6.dp))

            // Text/sticker/filter chips sit above everything, like CapCut.
            tracks.filter {
                it.track.type == TrackType.TEXT || it.track.type == TrackType.STICKER ||
                    it.track.type == TrackType.FILTER
            }
                .sortedByDescending { it.track.zOrder }
                .forEach { lane ->
                    if (lane.clips.isNotEmpty()) {
                        TrackLane(
                            state, lane.clips, allClips, viewportWidthPx,
                            OVERLAY_TRACK_HEIGHT, selectedClipId, multiSelection,
                            keyframeTimes, actions,
                        )
                        Spacer(Modifier.height(3.dp))
                    }
                }

            // PIP overlay lanes (video/photo over the main track) — as many
            // lanes as the edit needs, topmost first like CapCut.
            tracks.filter { it.track.type == TrackType.OVERLAY }
                .sortedByDescending { it.track.zOrder }
                .forEach { pipTrack ->
                    if (pipTrack.clips.isNotEmpty()) {
                        TrackLane(
                            state, pipTrack.clips.sortedBy { it.timelineStartMs }, allClips,
                            viewportWidthPx, PIP_TRACK_HEIGHT, selectedClipId, multiSelection,
                            keyframeTimes, actions,
                        )
                        Spacer(Modifier.height(3.dp))
                    }
                }

            // Main video track with filmstrip thumbnails.
            tracks.firstOrNull { it.track.type == TrackType.VIDEO }?.let { videoTrack ->
                TrackLane(
                    state, videoTrack.clips.sortedBy { it.timelineStartMs }, allClips,
                    viewportWidthPx, VIDEO_TRACK_HEIGHT, selectedClipId, multiSelection,
                    keyframeTimes, actions,
                )
            }
            Spacer(Modifier.height(3.dp))

            // Audio track (waveforms), or CapCut's "+ Add audio" hint.
            val audioTrack = tracks.firstOrNull { it.track.type == TrackType.AUDIO }
            if (hasAudio && audioTrack != null) {
                TrackLane(
                    state, audioTrack.clips.sortedBy { it.timelineStartMs }, allClips,
                    viewportWidthPx, AUDIO_TRACK_HEIGHT, selectedClipId, multiSelection,
                    keyframeTimes, actions,
                )
            } else {
                AddAudioHint(viewportWidthPx, state, actions.onAddAudio)
            }
        }

        // CapCut uses a thin white playhead over the timeline.
        Box(
            Modifier
                .align(Alignment.TopCenter)
                .width(1.5.dp)
                .fillMaxHeight()
                .background(Color.White)
        )
    }
}

@Composable
private fun AddAudioHint(viewportWidthPx: Float, state: TimelineState, onAddAudio: () -> Unit) {
    val startPx = viewportWidthPx / 2f - state.scrollPx
    Row(
        Modifier
            .offset { IntOffset(startPx.roundToInt().coerceAtLeast(8), 0) }
            .height(AUDIO_TRACK_HEIGHT)
            .clip(RoundedCornerShape(4.dp))
            .background(TrackLaneColor)
            .clickable(onClick = onAddAudio)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            Icons.Default.Add,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp),
        )
        Icon(
            Icons.Default.MusicNote,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.7f),
            modifier = Modifier.size(14.dp),
        )
        Text(
            "Add audio",
            color = Color.White.copy(alpha = 0.7f),
            fontSize = 11.sp,
            modifier = Modifier.padding(start = 4.dp),
        )
    }
}

@Composable
private fun TimeRuler(state: TimelineState, viewportWidthPx: Float) {
    val labelPaint = remember {
        Paint().apply {
            color = android.graphics.Color.GRAY
            textSize = 24f
            textAlign = Paint.Align.CENTER
            isAntiAlias = true
        }
    }
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(RULER_HEIGHT)
    ) {
        val centerX = viewportWidthPx / 2f
        val targetMs = state.pxToMs(80f).coerceAtLeast(1)
        val tickMs = niceInterval(targetMs)
        val firstVisibleMs = state.pxToMs((state.scrollPx - centerX).coerceAtLeast(0f))
        val lastVisibleMs = state.pxToMs(state.scrollPx + centerX)

        var t = (firstVisibleMs / tickMs) * tickMs
        while (t <= lastVisibleMs) {
            val x = centerX + state.msToPx(t) - state.scrollPx
            if (x in 0f..size.width) {
                if (t % (tickMs * 2) == 0L) {
                    drawContext.canvas.nativeCanvas.drawText(
                        formatRulerTime(t), x, size.height * 0.7f, labelPaint
                    )
                } else {
                    drawCircle(
                        color = Color.Gray,
                        radius = 1.5f,
                        center = Offset(x, size.height * 0.55f),
                    )
                }
            }
            t += tickMs
        }
    }
}

private fun formatRulerTime(ms: Long): String {
    val totalSec = ms / 1000
    return "%02d:%02d".format(totalSec / 60, totalSec % 60)
}

/** Rounds a raw interval up to a "nice" ruler step. */
private fun niceInterval(ms: Long): Long {
    val steps = longArrayOf(100, 250, 500, 1000, 2000, 5000, 10_000, 30_000, 60_000)
    return steps.firstOrNull { it >= ms } ?: 60_000L
}

@Composable
private fun TrackLane(
    state: TimelineState,
    clips: List<ClipEntity>,
    allClips: List<ClipEntity>,
    viewportWidthPx: Float,
    laneHeight: Dp,
    selectedClipId: Long?,
    multiSelection: Set<Long>,
    keyframeTimes: (clipId: Long) -> List<Long>,
    actions: TimelineActions,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(laneHeight)
            .zIndex(1f)
    ) {
        clips.forEach { clip ->
            key(clip.id) {
                ClipView(
                    state = state,
                    clip = clip,
                    viewportWidthPx = viewportWidthPx,
                    isSelected = clip.id == selectedClipId,
                    isMultiSelected = clip.id in multiSelection,
                    keyframeTimesMs = keyframeTimes(clip.id),
                    snapTargets = snapTargetsFor(clip, allClips, state),
                    actions = actions,
                )
            }
        }
    }
}

/** Edges of every other clip + timeline start + playhead: the snap magnets. */
private fun snapTargetsFor(
    clip: ClipEntity,
    allClips: List<ClipEntity>,
    state: TimelineState,
): List<Long> = buildList {
    add(0L)
    add(state.playheadMs)
    allClips.filter { it.id != clip.id }.forEach {
        add(it.timelineStartMs)
        add(it.timelineEndMs)
    }
}

@Composable
private fun ClipView(
    state: TimelineState,
    clip: ClipEntity,
    viewportWidthPx: Float,
    isSelected: Boolean,
    isMultiSelected: Boolean,
    keyframeTimesMs: List<Long>,
    snapTargets: List<Long>,
    actions: TimelineActions,
) {
    val density = LocalDensity.current
    val haptic = LocalHapticFeedback.current
    var menuOpen by remember(clip.id) { mutableStateOf(false) }
    // Live gesture offsets in ms; committed to the DB only on drag end so
    // Room writes never happen on the gesture hot path.
    var dragOffsetMs by remember(clip.id) { mutableFloatStateOf(0f) }
    var trimLeftMs by remember(clip.id) { mutableFloatStateOf(0f) }
    var trimRightMs by remember(clip.id) { mutableFloatStateOf(0f) }

    val minDurationMs = 100f

    fun clampedLeft(): Long {
        val maxRight = clip.timelineDurationMs - minDurationMs
        val minLeft = -(clip.sourceStartMs / clip.speed)
        return trimLeftMs.coerceIn(minLeft, maxRight).toLong()
    }

    fun clampedRight(): Long {
        val minLeft = -(clip.timelineDurationMs - minDurationMs)
        // Real media is bounded by the source file; synthetic media (photos,
        // text, stickers, filters) stretches as far as you like — the 3s cap
        // on image overlays was this clamp using their default duration.
        val sourceHeadroom = when (clip.type) {
            ClipType.VIDEO, ClipType.AUDIO ->
                if (clip.sourceDurationMs > 0) clip.sourceDurationMs - clip.sourceEndMs
                else Long.MAX_VALUE / 2
            else -> Long.MAX_VALUE / 2
        }
        val maxRight = sourceHeadroom / clip.speed
        return trimRightMs.coerceIn(minLeft, maxRight.toFloat()).toLong()
    }

    val liveStartMs = clip.timelineStartMs + dragOffsetMs.toLong() + clampedLeft()
    val liveDurationMs = clip.timelineDurationMs - clampedLeft() + clampedRight()

    val startPx = viewportWidthPx / 2f + state.msToPx(liveStartMs) - state.scrollPx
    val widthPx = state.msToPx(liveDurationMs)
    val widthDp = with(density) { widthPx.coerceAtLeast(8f).toDp() }

    // Cull fully offscreen clips — nothing offscreen composes or decodes.
    if (startPx > viewportWidthPx || startPx + widthPx < 0f) return

    fun commitTrim() {
        val left = clampedLeft()
        val right = clampedRight()
        trimLeftMs = 0f
        trimRightMs = 0f
        if (left == 0L && right == 0L) return
        actions.onTrimClip(
            clip.id,
            clip.sourceStartMs + (left * clip.speed).toLong(),
            clip.sourceEndMs + (right * clip.speed).toLong(),
            clip.timelineStartMs + left,
        )
    }

    Box(
        Modifier
            .offset { IntOffset(startPx.roundToInt(), 0) }
            .width(widthDp)
            .fillMaxHeight()
            .graphicsLayer(alpha = if (clip.hidden) 0.35f else 1f)
    ) {
        // Duration label above the selected clip (CapCut shows "2.84s").
        if (isSelected) {
            Text(
                "%.2fs".format(liveDurationMs / 1000f),
                color = Color.White,
                fontSize = 9.sp,
                modifier = Modifier
                    .offset(y = (-14).dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(3.dp))
                    .padding(horizontal = 4.dp)
                    .zIndex(3f),
            )
        }

        Box(
            Modifier
                .fillMaxSize()
                .clip(RoundedCornerShape(4.dp))
                .background(clipColor(clip.type))
                .border(
                    width = if (isSelected || isMultiSelected) 2.dp else 0.dp,
                    color = when {
                        isMultiSelected -> Saffron
                        isSelected -> Color.White
                        else -> Color.Transparent
                    },
                    shape = RoundedCornerShape(4.dp),
                )
                .pointerInput(clip.id) {
                    detectTapGestures(
                        onTap = { actions.onSelectClip(clip.id) },
                        onLongPress = {
                            actions.onSelectClip(clip.id)
                            menuOpen = true
                        },
                    )
                }
                .pointerInput(clip.id, snapTargets, clip.locked) {
                    if (clip.locked) return@pointerInput
                    detectDragGestures(
                        onDragStart = { actions.onSelectClip(clip.id) },
                        onDrag = { change, dragAmount ->
                            change.consume()
                            dragOffsetMs += dragAmount.x / state.pxPerMs
                        },
                        onDragEnd = {
                            val rawStart =
                                (clip.timelineStartMs + dragOffsetMs.toLong()).coerceAtLeast(0)
                            val snappedStart = state.snap(rawStart, snapTargets)
                            if (snappedStart != rawStart) {
                                // Snap engaged: give the finger a tick.
                                haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                            }
                            dragOffsetMs = 0f
                            actions.onMoveClip(clip.id, snappedStart)
                        },
                        onDragCancel = { dragOffsetMs = 0f },
                    )
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            when (clip.type) {
                ClipType.VIDEO, ClipType.IMAGE ->
                    if (clip.type == ClipType.VIDEO) {
                        Filmstrip(state, clip, startPx, widthDp, viewportWidthPx)
                    } else {
                        AsyncImage(
                            model = clip.sourceUri,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                ClipType.AUDIO -> AudioWaveform(clip)
                ClipType.TEXT -> ClipLabel("T  ${previewText(clip)}")
                ClipType.STICKER -> ClipLabel("★ ${clip.displayName ?: "sticker"}")
                ClipType.FILTER -> ClipLabel("◐ ${clip.displayName ?: clip.filterId ?: "filter"}")
            }

            // Status badges: locked / muted / hidden.
            Row(
                Modifier
                    .align(Alignment.TopEnd)
                    .padding(2.dp)
            ) {
                if (clip.locked) Badge(Icons.Default.Lock)
                if (clip.muted) Badge(Icons.Default.VolumeOff)
                if (clip.hidden) Badge(Icons.Default.VisibilityOff)
            }

            // Keyframe diamonds along the clip — hold & drag to retime
            // (enabled when the clip is selected).
            keyframeTimesMs.distinct().forEach { timeMs ->
                key(clip.id, timeMs) {
                    var kfDragMs by remember(clip.id, timeMs) { mutableFloatStateOf(0f) }
                    val liveKfMs = (timeMs + kfDragMs.toLong())
                        .coerceIn(0, clip.timelineDurationMs)
                    val x = state.msToPx(liveKfMs)
                    Box(
                        Modifier
                            .offset { IntOffset(x.roundToInt() - 10, 0) }
                            .align(Alignment.CenterStart)
                            .size(20.dp) // generous touch target
                            .pointerInput(clip.id, timeMs, isSelected) {
                                if (!isSelected) return@pointerInput
                                detectDragGestures(
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        kfDragMs += dragAmount.x / state.pxPerMs
                                    },
                                    onDragEnd = {
                                        val target = (timeMs + kfDragMs.toLong())
                                            .coerceIn(0, clip.timelineDurationMs)
                                        kfDragMs = 0f
                                        if (target != timeMs) {
                                            actions.onMoveKeyframe(clip.id, timeMs, target)
                                        }
                                    },
                                    onDragCancel = { kfDragMs = 0f },
                                )
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(8.dp)
                                .graphicsLayer(rotationZ = 45f)
                                .background(if (kfDragMs != 0f) Saffron else Color.White)
                                .border(1.dp, Color.Black.copy(alpha = 0.4f))
                        )
                    }
                }
            }

            if (isSelected && !clip.locked) {
                TrimHandle(
                    alignment = Alignment.CenterStart,
                    onDrag = { deltaPx -> trimLeftMs += deltaPx / state.pxPerMs },
                    onDone = ::commitTrim,
                )
                TrimHandle(
                    alignment = Alignment.CenterEnd,
                    onDrag = { deltaPx -> trimRightMs += deltaPx / state.pxPerMs },
                    onDone = ::commitTrim,
                )
            }
        }

        ClipMenu(
            expanded = menuOpen,
            clip = clip,
            hasMultiSelection = isMultiSelected,
            onAction = { action ->
                menuOpen = false
                actions.onMenuAction(clip.id, action)
            },
            onDismiss = { menuOpen = false },
        )
    }
}

@Composable
private fun ClipMenu(
    expanded: Boolean,
    clip: ClipEntity,
    hasMultiSelection: Boolean,
    onAction: (ClipMenuAction) -> Unit,
    onDismiss: () -> Unit,
) {
    DropdownMenu(expanded = expanded, onDismissRequest = onDismiss) {
        @Composable
        fun item(label: String, action: ClipMenuAction) {
            DropdownMenuItem(text = { Text(label) }, onClick = { onAction(action) })
        }
        item("Rename", ClipMenuAction.RENAME)
        item("Duplicate", ClipMenuAction.DUPLICATE)
        item(if (clip.locked) "Unlock" else "Lock", ClipMenuAction.LOCK)
        item(if (clip.muted) "Unmute" else "Mute", ClipMenuAction.MUTE)
        item(if (clip.hidden) "Show" else "Hide", ClipMenuAction.HIDE)
        item("Select (multi)", ClipMenuAction.SELECT_ADD)
        if (hasMultiSelection) item("Group selected", ClipMenuAction.GROUP)
        if (clip.groupId != null) item("Ungroup", ClipMenuAction.UNGROUP)
        if (!clip.locked) {
            item("Ripple delete", ClipMenuAction.RIPPLE_DELETE)
            item("Delete", ClipMenuAction.DELETE)
        }
    }
}

@Composable
private fun Badge(icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Icon(
        icon,
        contentDescription = null,
        tint = Color.White,
        modifier = Modifier
            .padding(start = 2.dp)
            .size(10.dp)
            .background(Color.Black.copy(alpha = 0.5f), RoundedCornerShape(2.dp)),
    )
}

@Composable
private fun ClipLabel(text: String) {
    Text(
        text = text,
        color = Color.White,
        fontSize = 10.sp,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(horizontal = 6.dp),
    )
}

private fun previewText(clip: ClipEntity): String =
    clip.displayName
        ?: com.mastar.editor.engine.effects.TextPayload.fromPayload(clip.payload).text

/**
 * Virtualized CapCut-style filmstrip: frames are positioned by index and only
 * the on-screen ones compose — the whole clip stays thumbnailed at ANY zoom
 * (v0.5 capped at 60 frames and long clips went blank past ~2400dp).
 */
@Composable
private fun Filmstrip(
    state: TimelineState,
    clip: ClipEntity,
    clipStartPx: Float,
    clipWidth: Dp,
    viewportWidthPx: Float,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val frameWidthPx = with(density) { FRAME_WIDTH.toPx() }
    val clipWidthPx = with(density) { clipWidth.toPx() }
    val totalFrames = ceil(clipWidthPx / frameWidthPx).toInt().coerceAtLeast(1)
    val sourceSpanMs = (clip.sourceEndMs - clip.sourceStartMs).coerceAtLeast(1)

    // Only frames intersecting the viewport are composed.
    val firstVisible = floor((-clipStartPx) / frameWidthPx).toInt().coerceAtLeast(0)
    val lastVisible = ceil((viewportWidthPx - clipStartPx) / frameWidthPx).toInt()
        .coerceAtMost(totalFrames - 1)

    Box(Modifier.fillMaxSize()) {
        for (i in firstVisible..lastVisible) {
            // Quantize to whole seconds so zoom changes re-hit Coil's cache;
            // nearest sync frame decode is ~10x faster than exact frames.
            val frameTimeMs =
                ((clip.sourceStartMs + sourceSpanMs * i / totalFrames) / 1000) * 1000
            AsyncImage(
                model = ImageRequest.Builder(context)
                    .data(clip.sourceUri)
                    .videoFrameMillis(frameTimeMs)
                    .videoFrameOption(MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                    .size(64)
                    .build(),
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .offset { IntOffset((i * frameWidthPx).roundToInt(), 0) }
                    .width(FRAME_WIDTH)
                    .fillMaxHeight(),
            )
        }
    }
}

/** Amplitude bars — creators cut on the beats they can see. */
@Composable
private fun AudioWaveform(clip: ClipEntity) {
    val context = LocalContext.current
    val peaks by produceState<FloatArray?>(initialValue = null, clip.sourceUri) {
        value = Waveform.peaks(context, clip.sourceUri, clip.sourceDurationMs)
    }
    Box(Modifier.fillMaxSize()) {
        val data = peaks
        if (data == null || data.isEmpty()) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp),
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
                ClipLabel(clip.displayName ?: clip.sourceUri.substringAfterLast('/'))
            }
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val startBucket = (clip.sourceStartMs / Waveform.BUCKET_MS).toInt()
                val endBucket = (clip.sourceEndMs / Waveform.BUCKET_MS).toInt()
                    .coerceAtMost(data.size - 1)
                val buckets = (endBucket - startBucket).coerceAtLeast(1)
                val barWidth = size.width / buckets
                val midY = size.height / 2f
                for (b in 0 until buckets) {
                    val amp = data.getOrElse(startBucket + b) { 0f }.coerceIn(0.04f, 1f)
                    val h = amp * size.height * 0.9f
                    drawLine(
                        color = Color.White.copy(alpha = 0.85f),
                        start = Offset(b * barWidth + barWidth / 2, midY - h / 2),
                        end = Offset(b * barWidth + barWidth / 2, midY + h / 2),
                        strokeWidth = (barWidth * 0.6f).coerceAtLeast(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.TrimHandle(
    alignment: Alignment,
    onDrag: (deltaPx: Float) -> Unit,
    onDone: () -> Unit,
) {
    Box(
        Modifier
            .align(alignment)
            .width(14.dp)
            .fillMaxHeight()
            .background(Color.White.copy(alpha = 0.9f))
            .pointerInput(Unit) {
                detectDragGestures(
                    onDrag = { change, dragAmount ->
                        change.consume()
                        onDrag(dragAmount.x)
                    },
                    onDragEnd = onDone,
                    onDragCancel = onDone,
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(2.dp)
                .height(14.dp)
                .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(1.dp))
        )
    }
}

private fun clipColor(type: ClipType): Color = when (type) {
    ClipType.VIDEO -> Color(0xFF2A2A2E)
    ClipType.AUDIO -> ClipAudio.copy(alpha = 0.8f)
    ClipType.IMAGE -> Color(0xFF2A2A2E)
    ClipType.TEXT -> ClipText.copy(alpha = 0.85f)
    ClipType.STICKER -> ClipOverlay.copy(alpha = 0.85f)
    ClipType.FILTER -> Saffron.copy(alpha = 0.55f)
}
