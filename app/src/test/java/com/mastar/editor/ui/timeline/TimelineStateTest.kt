package com.mastar.editor.ui.timeline

import org.junit.Assert.assertEquals
import org.junit.Test

class TimelineStateTest {
    @Test
    fun zoomKeepsTimeUnderPinchCentroidStationary() {
        val state = TimelineState(initialPxPerMs = 0.06f)
        state.scrollPx = 1_200f
        val viewportWidthPx = 1_080f
        val centroidX = 600f
        val before = (state.scrollPx + centroidX - viewportWidthPx / 2f) / state.pxPerMs

        state.zoomBy(
            factor = 2f,
            focalX = centroidX,
            viewportWidthPx = viewportWidthPx,
            projectDurationMs = 65_000L,
        )

        val after = (state.scrollPx + centroidX - viewportWidthPx / 2f) / state.pxPerMs
        assertEquals(before, after, 0.5f)
        assertEquals(0.12f, state.pxPerMs, 0.0001f)
        assertEquals(state.pxToMs(state.scrollPx), state.playheadMs)
    }
}
