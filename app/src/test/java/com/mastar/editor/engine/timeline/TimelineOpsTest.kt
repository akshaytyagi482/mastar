package com.mastar.editor.engine.timeline

import com.mastar.editor.data.db.ClipEntity
import com.mastar.editor.data.db.ClipType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TimelineOpsTest {

    private fun clip(id: Long, startMs: Long, durationMs: Long = 2000) = ClipEntity(
        id = id,
        trackId = 1,
        type = ClipType.VIDEO,
        sourceUri = "file:///v$id.mp4",
        sourceStartMs = 0,
        sourceEndMs = durationMs,
        sourceDurationMs = durationMs,
        timelineStartMs = startMs,
    )

    @Test
    fun `non-overlapping clips are untouched`() {
        val clips = listOf(clip(1, 0), clip(2, 2000), clip(3, 5000))
        assertTrue(TimelineOps.resolveOverlaps(clips).isEmpty())
    }

    @Test
    fun `insert mode pushes overlapped clips right`() {
        // Clip 2 dropped in the middle of clip 1.
        val clips = listOf(clip(1, 0), clip(2, 1000), clip(3, 3000))
        val moved = TimelineOps.resolveOverlaps(clips)

        val clip2 = moved.first { it.id == 2L }
        assertEquals(2000, clip2.timelineStartMs)
        // Clip 3 must be pushed too (cascade).
        val clip3 = moved.first { it.id == 3L }
        assertEquals(4000, clip3.timelineStartMs)
    }

    @Test
    fun `transition overlap is not pushed apart`() {
        val a = clip(1, 0).copy(transitionId = "fade", transitionDurationMs = 600)
        val b = clip(2, 1400) // overlaps A (0..2000) by 600 — legitimate
        assertTrue(TimelineOps.resolveOverlaps(listOf(a, b)).isEmpty())
    }

    @Test
    fun `overlap beyond the transition window is still resolved`() {
        val a = clip(1, 0).copy(transitionId = "fade", transitionDurationMs = 600)
        val b = clip(2, 800) // 1200ms overlap > 600 allowed
        val moved = TimelineOps.resolveOverlaps(listOf(a, b))
        assertEquals(1400, moved.first { it.id == 2L }.timelineStartMs)
    }

    @Test
    fun `ripple delete closes the hole`() {
        val deleted = clip(2, 2000)
        val clips = listOf(clip(1, 0), deleted, clip(3, 4000), clip(4, 7000))
        val shifted = TimelineOps.rippleShiftAfterDelete(clips, deleted)

        assertEquals(2000, shifted.first { it.id == 3L }.timelineStartMs)
        assertEquals(5000, shifted.first { it.id == 4L }.timelineStartMs)
        assertTrue(shifted.none { it.id == 1L })
    }
}
