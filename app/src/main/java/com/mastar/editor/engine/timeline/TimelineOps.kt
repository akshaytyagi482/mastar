package com.mastar.editor.engine.timeline

import com.mastar.editor.data.db.ClipEntity

/**
 * Pure timeline algebra — insert-mode overlap resolution and ripple edits.
 * Kept side-effect free so the behavior is unit-tested on the JVM.
 */
object TimelineOps {

    /**
     * Insert semantics: when clips on one track overlap, later clips are
     * pushed right until nothing overlaps (CapCut's insert mode).
     * Returns only the clips whose start must change.
     */
    fun resolveOverlaps(trackClips: List<ClipEntity>): List<ClipEntity> {
        val sorted = trackClips.sortedWith(
            compareBy({ it.timelineStartMs }, { it.id })
        )
        val moved = mutableListOf<ClipEntity>()
        var cursorMs = 0L
        var allowedOverlapMs = 0L
        for (clip in sorted) {
            // A cross transition on the previous clip legitimately overlaps
            // the next clip by its duration — don't push that apart.
            val start = maxOf(clip.timelineStartMs, cursorMs - allowedOverlapMs)
            if (start != clip.timelineStartMs) {
                moved.add(clip.copy(timelineStartMs = start))
            }
            cursorMs = maxOf(cursorMs, start + clip.timelineDurationMs)
            allowedOverlapMs =
                if (com.mastar.editor.engine.effects.Transitions.overlaps(clip.transitionId)) {
                    clip.transitionDurationMs
                } else 0L
        }
        return moved
    }

    /**
     * Ripple delete: everything on the track after the deleted clip shifts
     * left by its duration, closing the hole.
     */
    fun rippleShiftAfterDelete(
        trackClips: List<ClipEntity>,
        deleted: ClipEntity,
    ): List<ClipEntity> =
        trackClips
            .filter { it.id != deleted.id && it.timelineStartMs >= deleted.timelineStartMs }
            .map {
                it.copy(
                    timelineStartMs = (it.timelineStartMs - deleted.timelineDurationMs)
                        .coerceAtLeast(deleted.timelineStartMs)
                )
            }
            .filter { changed ->
                trackClips.first { it.id == changed.id }.timelineStartMs != changed.timelineStartMs
            }
}
