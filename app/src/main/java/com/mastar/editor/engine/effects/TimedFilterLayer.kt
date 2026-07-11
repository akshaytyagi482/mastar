package com.mastar.editor.engine.effects

import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.RgbMatrix
import com.mastar.editor.data.db.ClipEntity

/**
 * A CapCut-style FILTER LAYER: a filter that lives on its own timeline lane
 * and colors everything under it for its time range only. Implemented as a
 * composition-level time-windowed color matrix (identity outside the window),
 * so it grades the fully-composited frame — main track, PIP and all.
 */
@UnstableApi
object TimedFilterLayer {

    private val IDENTITY = FloatArray(16).also {
        it[0] = 1f; it[5] = 1f; it[10] = 1f; it[15] = 1f
    }

    /** Null if the clip has no usable filter recipe. */
    fun effectFor(clip: ClipEntity): Effect? {
        val matrix = FilterLibrary.colorMatrixFor(clip.filterId, clip.filterIntensity)
            ?: return null
        val startMs = clip.timelineStartMs
        val endMs = clip.timelineEndMs
        return RgbMatrix { presentationTimeUs, _ ->
            val timeMs = presentationTimeUs / 1000
            if (timeMs in startMs until endMs) matrix else IDENTITY
        }
    }
}
