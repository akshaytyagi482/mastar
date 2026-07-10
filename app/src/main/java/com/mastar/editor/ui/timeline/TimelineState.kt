package com.mastar.editor.ui.timeline

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import kotlin.math.abs

/**
 * Zoom + scroll + snapping state for the timeline. All positions are held in
 * milliseconds and converted to pixels at draw time, so pinch-zooming only
 * changes one float — recomposition stays cheap at 60fps.
 */
class TimelineState(
    initialPxPerMs: Float = DEFAULT_PX_PER_MS,
) {
    /** Zoom level: pixels per millisecond of timeline. */
    var pxPerMs by mutableFloatStateOf(initialPxPerMs)
        private set

    /** Horizontal scroll offset in px (0 = timeline start at playhead). */
    var scrollPx by mutableFloatStateOf(0f)

    /** Current playhead position on the project timeline. */
    var playheadMs by mutableLongStateOf(0L)

    fun msToPx(ms: Long): Float = ms * pxPerMs

    fun pxToMs(px: Float): Long = (px / pxPerMs).toLong()

    /** Pinch-to-zoom, clamped so 1 frame (33ms) never exceeds ~120px. */
    fun zoomBy(factor: Float) {
        pxPerMs = (pxPerMs * factor).coerceIn(MIN_PX_PER_MS, MAX_PX_PER_MS)
    }

    fun scrollBy(deltaPx: Float, contentWidthPx: Float) {
        scrollPx = (scrollPx + deltaPx).coerceIn(0f, contentWidthPx.coerceAtLeast(0f))
    }

    /**
     * Magnetic snapping: pulls [candidateMs] onto the nearest snap target
     * (other clips' edges or the playhead) when within [SNAP_THRESHOLD_PX].
     */
    fun snap(candidateMs: Long, snapTargetsMs: List<Long>): Long {
        val thresholdMs = pxToMs(SNAP_THRESHOLD_PX)
        var best = candidateMs
        var bestDistance = thresholdMs + 1
        for (target in snapTargetsMs) {
            val d = abs(target - candidateMs)
            if (d < bestDistance) {
                bestDistance = d
                best = target
            }
        }
        return if (bestDistance <= thresholdMs) best else candidateMs
    }

    companion object {
        /** 0.06 px/ms ≈ 60px per second: whole reels fit on screen by default. */
        const val DEFAULT_PX_PER_MS = 0.06f
        const val MIN_PX_PER_MS = 0.005f

        /** ~3.6px per frame at 30fps — enough for sub-frame trim precision. */
        const val MAX_PX_PER_MS = 3.6f

        const val SNAP_THRESHOLD_PX = 12f
    }
}

@Composable
fun rememberTimelineState(): TimelineState = remember { TimelineState() }
