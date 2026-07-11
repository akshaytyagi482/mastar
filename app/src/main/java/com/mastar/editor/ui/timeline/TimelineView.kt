package com.mastar.editor.ui.timeline

import android.graphics.Paint
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
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.media.MediaMetadataRetriever
import coil.compose.AsyncImage
import coil.request.ImageRequest
import coil.request.videoFrameMillis
import coil.request.videoFrameOption
import com.mastar.editor.data.db.ClipEntity
import com.mastar.editor.data.db.ClipType
import com.mastar.editor.data.db.TrackType
import com.mastar.editor.data.db.TrackWithClips
import com.mastar.editor.ui.theme.ClipAudio
import com.mastar.editor.ui.theme.ClipOverlay
import com.mastar.editor.ui.theme.ClipText
import com.mastar.editor.ui.theme.TrackLaneColor
import kotlin.math.ceil
import kotlin.math.roundToInt

private val VIDEO_TRACK_HEIGHT = 56.dp
private val PIP_TRACK_HEIGHT = 40.dp
private val AUDIO_TRACK_HEIGHT = 32.dp
private val OVERLAY_TRACK_HEIGHT = 22.dp
private val RULER_HEIGHT = 22.dp
private val FRAME_WIDTH = 40.dp

/**
 * CapCut-style timeline: fixed centered playhead, content scrolls beneath it,
 * filmstrip thumbnails on video clips, pinch-to-zoom, magnetic drag snapping,
 * and an "+ Add audio" hint lane.
 */
@Composable
fun TimelineView(
    state: TimelineState,
    tracks: List<TrackWithClips>,
    selectedClipId: Long?,
    onSelectClip: (Long?) -> Unit,
    onMoveClip: (clipId: Long, newTimelineStartMs: Long) -> Unit,
    onTrimClip: (clipId: Long, newSourceStartMs: Long, newSourceEndMs: Long, newTimelineStartMs: Long) -> Unit,
    onSeek: (Long) -> Unit,
    onAddAudio: () -> Unit,
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
                        onSeek(state.playheadMs)
                    }
                }
            }
            .pointerInput(Unit) {
                detectTapGestures(onTap = { onSelectClip(null) })
            },
    ) {
        val viewportWidthPx = with(LocalDensity.current) { maxWidth.toPx() }
        val allClips = tracks.flatMap { it.clips }
        val hasAudio = tracks.any { it.track.type == TrackType.AUDIO && it.clips.isNotEmpty() }

        Column(Modifier.fillMaxWidth()) {
            TimeRuler(state, viewportWidthPx)
            Spacer(Modifier.height(6.dp))

            // Overlay lanes (text/stickers) sit above the video like CapCut.
            tracks.filter { it.track.type == TrackType.TEXT || it.track.type == TrackType.STICKER }
                .sortedByDescending { it.track.zOrder }
                .forEach { lane ->
                    if (lane.clips.isNotEmpty()) {
                        TrackLane(
                            state, lane.clips, allClips, viewportWidthPx,
                            OVERLAY_TRACK_HEIGHT, selectedClipId, onSelectClip, onMoveClip, onTrimClip,
                        )
                        Spacer(Modifier.height(3.dp))
                    }
                }

            // PIP overlay layer (video/photo over the main track).
            tracks.firstOrNull { it.track.type == TrackType.OVERLAY }?.let { pipTrack ->
                if (pipTrack.clips.isNotEmpty()) {
                    TrackLane(
                        state, pipTrack.clips.sortedBy { it.timelineStartMs }, allClips,
                        viewportWidthPx, PIP_TRACK_HEIGHT, selectedClipId, onSelectClip, onMoveClip, onTrimClip,
                    )
                    Spacer(Modifier.height(3.dp))
                }
            }

            // Main video track with filmstrip thumbnails.
            tracks.firstOrNull { it.track.type == TrackType.VIDEO }?.let { videoTrack ->
                TrackLane(
                    state, videoTrack.clips.sortedBy { it.timelineStartMs }, allClips,
                    viewportWidthPx, VIDEO_TRACK_HEIGHT, selectedClipId, onSelectClip, onMoveClip, onTrimClip,
                )
            }
            Spacer(Modifier.height(3.dp))

            // Audio track, or CapCut's "+ Add audio" hint when it's empty.
            val audioTrack = tracks.firstOrNull { it.track.type == TrackType.AUDIO }
            if (hasAudio && audioTrack != null) {
                TrackLane(
                    state, audioTrack.clips.sortedBy { it.timelineStartMs }, allClips,
                    viewportWidthPx, AUDIO_TRACK_HEIGHT, selectedClipId, onSelectClip, onMoveClip, onTrimClip,
                )
            } else {
                AddAudioHint(viewportWidthPx, state, onAddAudio)
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
    laneHeight: androidx.compose.ui.unit.Dp,
    selectedClipId: Long?,
    onSelectClip: (Long?) -> Unit,
    onMoveClip: (clipId: Long, newTimelineStartMs: Long) -> Unit,
    onTrimClip: (clipId: Long, newSourceStartMs: Long, newSourceEndMs: Long, newTimelineStartMs: Long) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(laneHeight)
    ) {
        clips.forEach { clip ->
            key(clip.id) {
                ClipView(
                    state = state,
                    clip = clip,
                    viewportWidthPx = viewportWidthPx,
                    isSelected = clip.id == selectedClipId,
                    snapTargets = snapTargetsFor(clip, allClips, state),
                    onSelect = { onSelectClip(clip.id) },
                    onMove = { newStart -> onMoveClip(clip.id, newStart) },
                    onTrim = { ss, se, ts -> onTrimClip(clip.id, ss, se, ts) },
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
    snapTargets: List<Long>,
    onSelect: () -> Unit,
    onMove: (Long) -> Unit,
    onTrim: (newSourceStartMs: Long, newSourceEndMs: Long, newTimelineStartMs: Long) -> Unit,
) {
    val density = LocalDensity.current
    // Live gesture offsets in ms; committed to the DB only on drag end so
    // Room writes never happen on the gesture hot path.
    var dragOffsetMs by remember(clip.id) { mutableFloatStateOf(0f) }
    var trimLeftMs by remember(clip.id) { mutableFloatStateOf(0f) }
    var trimRightMs by remember(clip.id) { mutableFloatStateOf(0f) }

    val minDurationMs = 100f

    // Clamp live trim offsets against the source's real bounds.
    fun clampedLeft(): Long {
        val maxRight = clip.timelineDurationMs - minDurationMs
        val minLeft = -(clip.sourceStartMs / clip.speed)
        return trimLeftMs.coerceIn(minLeft, maxRight).toLong()
    }

    fun clampedRight(): Long {
        val minLeft = -(clip.timelineDurationMs - minDurationMs)
        val sourceHeadroom =
            if (clip.sourceDurationMs > 0) clip.sourceDurationMs - clip.sourceEndMs else Long.MAX_VALUE / 2
        val maxRight = sourceHeadroom / clip.speed
        return trimRightMs.coerceIn(minLeft, maxRight.toFloat()).toLong()
    }

    val liveStartMs = clip.timelineStartMs + dragOffsetMs.toLong() + clampedLeft()
    val liveDurationMs = clip.timelineDurationMs - clampedLeft() + clampedRight()

    val startPx = viewportWidthPx / 2f + state.msToPx(liveStartMs) - state.scrollPx
    val widthPx = state.msToPx(liveDurationMs)
    val widthDp = with(density) { widthPx.coerceAtLeast(8f).toDp() }

    fun commitTrim() {
        val left = clampedLeft()
        val right = clampedRight()
        trimLeftMs = 0f
        trimRightMs = 0f
        if (left == 0L && right == 0L) return
        onTrim(
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
            .clip(RoundedCornerShape(4.dp))
            .background(clipColor(clip.type))
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) Color.White else Color.Transparent,
                shape = RoundedCornerShape(4.dp),
            )
            .pointerInput(clip.id) {
                detectTapGestures(onTap = { onSelect() })
            }
            .pointerInput(clip.id, snapTargets) {
                detectDragGestures(
                    onDragStart = { onSelect() },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        dragOffsetMs += dragAmount.x / state.pxPerMs
                    },
                    onDragEnd = {
                        val rawStart =
                            (clip.timelineStartMs + dragOffsetMs.toLong()).coerceAtLeast(0)
                        val snappedStart = state.snap(rawStart, snapTargets)
                        dragOffsetMs = 0f
                        onMove(snappedStart)
                    },
                    onDragCancel = { dragOffsetMs = 0f },
                )
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        when (clip.type) {
            ClipType.VIDEO -> Filmstrip(clip, widthDp)
            ClipType.AUDIO -> Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 6.dp),
            ) {
                Icon(
                    Icons.Default.MusicNote,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(12.dp),
                )
                ClipLabel(clip.sourceUri.substringAfterLast('/'))
            }
            ClipType.TEXT -> ClipLabel("T  ${previewText(clip.payload)}")
            ClipType.STICKER -> ClipLabel("★ sticker")
            ClipType.IMAGE -> ClipLabel("🖼")
        }

        // CapCut-style trim handles on the selected clip's edges.
        if (isSelected) {
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

private fun previewText(payload: String?): String =
    com.mastar.editor.engine.effects.TextPayload.fromPayload(payload).text

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

/**
 * CapCut-style filmstrip: evenly spaced frames from the clip's source window,
 * decoded by Coil's video decoder and cached — scrolling stays at 60fps.
 */
@Composable
private fun Filmstrip(clip: ClipEntity, clipWidth: androidx.compose.ui.unit.Dp) {
    val context = LocalContext.current
    val frameCount = ceil(clipWidth / FRAME_WIDTH).toInt().coerceIn(1, 60)
    val sourceSpanMs = (clip.sourceEndMs - clip.sourceStartMs).coerceAtLeast(1)

    Row(Modifier.fillMaxSize()) {
        repeat(frameCount) { i ->
            // Quantize to whole seconds so zoom changes re-hit Coil's cache,
            // and grab the nearest sync frame — an order of magnitude faster
            // than exact-frame seeks on long videos.
            val frameTimeMs =
                ((clip.sourceStartMs + sourceSpanMs * i / frameCount) / 1000) * 1000
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
                    .width(FRAME_WIDTH)
                    .fillMaxHeight(),
            )
        }
    }
}

private fun clipColor(type: ClipType): Color = when (type) {
    ClipType.VIDEO -> Color(0xFF2A2A2E)
    ClipType.AUDIO -> ClipAudio.copy(alpha = 0.8f)
    ClipType.IMAGE -> ClipOverlay
    ClipType.TEXT -> ClipText.copy(alpha = 0.85f)
    ClipType.STICKER -> ClipOverlay.copy(alpha = 0.85f)
}
