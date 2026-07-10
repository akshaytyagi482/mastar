package com.mastar.editor.ui.timeline

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.mastar.editor.data.db.ClipEntity
import com.mastar.editor.data.db.ClipType
import com.mastar.editor.data.db.TrackWithClips
import com.mastar.editor.ui.theme.ClipAudio
import com.mastar.editor.ui.theme.ClipOverlay
import com.mastar.editor.ui.theme.ClipText
import com.mastar.editor.ui.theme.ClipVideo
import com.mastar.editor.ui.theme.PlayheadRed
import com.mastar.editor.ui.theme.Saffron
import com.mastar.editor.ui.theme.TrackLaneColor
import kotlin.math.roundToInt

private val TRACK_HEIGHT = 56.dp
private val RULER_HEIGHT = 24.dp

/**
 * The pro timeline: a fixed centered playhead with the content scrolling
 * underneath (CapCut-style), pinch-to-zoom, and clip dragging with
 * magnetic snapping.
 */
@Composable
fun TimelineView(
    state: TimelineState,
    tracks: List<TrackWithClips>,
    selectedClipId: Long?,
    onSelectClip: (Long?) -> Unit,
    onMoveClip: (clipId: Long, newTimelineStartMs: Long) -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    val projectEndMs = tracks.flatMap { it.clips }.maxOfOrNull { it.timelineEndMs } ?: 0L

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.Black)
            // Pinch to zoom + one-finger pan to scrub, exactly like CapCut.
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

        Column(Modifier.fillMaxWidth()) {
            TimeRuler(state, viewportWidthPx)
            Spacer(Modifier.height(4.dp))
            tracks.sortedBy { it.track.zOrder }.forEach { trackWithClips ->
                TrackLane(
                    state = state,
                    clips = trackWithClips.clips.sortedBy { it.timelineStartMs },
                    allClips = allClips,
                    viewportWidthPx = viewportWidthPx,
                    selectedClipId = selectedClipId,
                    onSelectClip = onSelectClip,
                    onMoveClip = onMoveClip,
                )
                Spacer(Modifier.height(4.dp))
            }
        }
        Playhead(Modifier.align(Alignment.TopCenter))
    }
}

@Composable
private fun TimeRuler(state: TimelineState, viewportWidthPx: Float) {
    Canvas(
        Modifier
            .fillMaxWidth()
            .height(RULER_HEIGHT)
    ) {
        val centerX = viewportWidthPx / 2f
        // Tick interval adapts to zoom: aim for a tick roughly every 80px.
        val targetMs = state.pxToMs(80f).coerceAtLeast(1)
        val tickMs = niceInterval(targetMs)
        val firstVisibleMs = state.pxToMs((state.scrollPx - centerX).coerceAtLeast(0f))
        val lastVisibleMs = state.pxToMs(state.scrollPx + centerX)

        var t = (firstVisibleMs / tickMs) * tickMs
        while (t <= lastVisibleMs) {
            val x = centerX + state.msToPx(t) - state.scrollPx
            if (x in 0f..size.width) {
                drawLine(
                    color = Color.Gray,
                    start = Offset(x, size.height * 0.55f),
                    end = Offset(x, size.height),
                    strokeWidth = 1f,
                )
            }
            t += tickMs
        }
    }
}

/** Rounds a raw interval up to a "nice" ruler step. */
private fun niceInterval(ms: Long): Long {
    val steps = longArrayOf(33, 100, 250, 500, 1000, 2000, 5000, 10_000, 30_000, 60_000)
    return steps.firstOrNull { it >= ms } ?: 60_000L
}

@Composable
private fun TrackLane(
    state: TimelineState,
    clips: List<ClipEntity>,
    allClips: List<ClipEntity>,
    viewportWidthPx: Float,
    selectedClipId: Long?,
    onSelectClip: (Long?) -> Unit,
    onMoveClip: (clipId: Long, newTimelineStartMs: Long) -> Unit,
) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(TRACK_HEIGHT)
            .background(TrackLaneColor, RoundedCornerShape(4.dp))
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
) {
    val density = LocalDensity.current
    // Live drag offset in ms; committed to the DB only on drag end so Room
    // writes never happen on the gesture hot path.
    var dragOffsetMs by remember(clip.id) { mutableFloatStateOf(0f) }

    val startPx = viewportWidthPx / 2f +
        state.msToPx(clip.timelineStartMs + dragOffsetMs.toLong()) - state.scrollPx
    val widthPx = state.msToPx(clip.timelineDurationMs)

    Box(
        Modifier
            .offset { IntOffset(startPx.roundToInt(), 0) }
            .width(with(density) { widthPx.coerceAtLeast(4f).toDp() })
            .fillMaxHeight()
            .padding(vertical = 2.dp)
            .background(
                color = clipColor(clip.type),
                shape = RoundedCornerShape(6.dp),
            )
            .then(
                if (isSelected) Modifier.background(
                    Saffron.copy(alpha = 0.35f), RoundedCornerShape(6.dp)
                ) else Modifier
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
        Text(
            text = clip.payload ?: clip.sourceUri.substringAfterLast('/'),
            color = Color.White,
            fontSize = 10.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 6.dp),
        )
    }
}

private fun clipColor(type: ClipType): Color = when (type) {
    ClipType.VIDEO -> ClipVideo
    ClipType.AUDIO -> ClipAudio
    ClipType.IMAGE -> ClipOverlay
    ClipType.TEXT -> ClipText
    ClipType.STICKER -> ClipOverlay
}

@Composable
private fun Playhead(modifier: Modifier = Modifier) {
    Box(
        modifier
            .width(2.dp)
            .fillMaxHeight()
            .background(PlayheadRed)
    )
}
