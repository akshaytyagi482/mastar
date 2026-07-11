package com.mastar.editor.engine.effects

import android.graphics.Matrix
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix

/**
 * Visible clip transitions built from Media3's time-varying primitives:
 * RgbMatrix (per-frame color) and MatrixTransformation (per-frame geometry).
 * A transition on clip A animates A's tail (OUT phase) and the next clip's
 * head (IN phase) — same effects in preview and export. All lambdas reuse
 * their Matrix/FloatArray: zero allocation on the render hot path.
 */
@UnstableApi
object Transitions {

    data class TransitionInfo(val id: String, val displayName: String)

    val TRANSITIONS = listOf(
        TransitionInfo("fade", "Cross fade"),
        TransitionInfo("slideleft", "Push ←"),
        TransitionInfo("slideright", "Push →"),
        TransitionInfo("zoomin", "Zoom"),
        TransitionInfo("spin", "Spin"),
        TransitionInfo("flash", "Flash"),
    )

    /** Flash is a gain ramp at the cut; everything else truly overlaps. */
    fun overlaps(id: String?): Boolean = id != null && id != "flash"

    /** Full transition length stored on the clip. */
    const val DEFAULT_DURATION_MS = 600L

    /**
     * Single-clip animations (CapCut "Animation"): the same ramp primitives,
     * but applied to ONE clip's head (In) or tail (Out) — no neighbour needed.
     */
    val IN_ANIMATIONS = listOf(
        TransitionInfo("fade", "Fade in"),
        TransitionInfo("zoomin", "Zoom in"),
        TransitionInfo("zoomout", "Zoom out"),
        TransitionInfo("slideleft", "Slide ←"),
        TransitionInfo("slideright", "Slide →"),
    )
    val OUT_ANIMATIONS = listOf(
        TransitionInfo("fade", "Fade out"),
        TransitionInfo("zoomin", "Zoom in"),
        TransitionInfo("zoomout", "Zoom out"),
        TransitionInfo("slideleft", "Slide ←"),
        TransitionInfo("slideright", "Slide →"),
    )

    const val DEFAULT_ANIMATION_MS = 500L

    /** Linear 0→1 progress inside [windowStartUs, windowEndUs]. */
    private fun progress(timeUs: Long, windowStartUs: Long, windowEndUs: Long): Float {
        if (windowEndUs <= windowStartUs) return 1f
        return ((timeUs - windowStartUs).toFloat() / (windowEndUs - windowStartUs))
            .coerceIn(0f, 1f)
    }

    private fun gainEffect(gainAt: (timeUs: Long) -> Float): Effect {
        val m = FloatArray(16)
        return RgbMatrix { timeUs, _ ->
            val g = gainAt(timeUs)
            m.fill(0f)
            m[0] = g; m[5] = g; m[10] = g; m[15] = 1f
            m
        }
    }

    private fun matrixEffect(configure: (matrix: Matrix, timeUs: Long) -> Unit): Effect {
        val matrix = Matrix()
        return MatrixTransformation { timeUs ->
            matrix.reset()
            configure(matrix, timeUs)
            matrix
        }
    }

    /**
     * OUT phase on the tail of a clip. [itemDurationUs] is the clip's
     * presented duration; the window is the last [windowUs] of it.
     * Unknown ids (including legacy shader ids) fall back to fade.
     */
    fun outEffects(id: String, itemDurationUs: Long, windowUs: Long): List<Effect> {
        val start = (itemDurationUs - windowUs).coerceAtLeast(0)
        val end = itemDurationUs
        return when (id) {
            "flash" -> listOf(gainEffect { t -> 1f + 4f * progress(t, start, end) })
            "zoomin" -> listOf(
                matrixEffect { m, t ->
                    val s = 1f + 0.5f * progress(t, start, end)
                    m.postScale(s, s)
                },
                gainEffect { t -> 1f - progress(t, start, end) },
            )
            "zoomout" -> listOf(
                matrixEffect { m, t ->
                    val s = 1f - 0.4f * progress(t, start, end)
                    m.postScale(s, s)
                },
                gainEffect { t -> 1f - progress(t, start, end) },
            )
            "slideleft" -> listOf(
                // NDC spans 2 units; slide fully off-screen.
                matrixEffect { m, t -> m.postTranslate(-2f * progress(t, start, end), 0f) }
            )
            "slideright" -> listOf(
                matrixEffect { m, t -> m.postTranslate(2f * progress(t, start, end), 0f) }
            )
            else -> listOf(gainEffect { t -> 1f - progress(t, start, end) }) // fade/spin/legacy
        }
    }

    /** IN phase on the head of the next clip: first [windowUs] of it. */
    fun inEffects(id: String, windowUs: Long): List<Effect> {
        val start = 0L
        val end = windowUs.coerceAtLeast(1)
        return when (id) {
            "flash" -> listOf(gainEffect { t -> 1f + 4f * (1f - progress(t, start, end)) })
            "zoomin" -> listOf(
                matrixEffect { m, t ->
                    val s = 1f - 0.4f * (1f - progress(t, start, end))
                    m.postScale(s, s)
                },
                gainEffect { t -> progress(t, start, end) },
            )
            "zoomout" -> listOf(
                matrixEffect { m, t ->
                    val s = 1f + 0.5f * (1f - progress(t, start, end))
                    m.postScale(s, s)
                },
                gainEffect { t -> progress(t, start, end) },
            )
            "slideleft" -> listOf(
                matrixEffect { m, t ->
                    m.postTranslate(2f * (1f - progress(t, start, end)), 0f)
                }
            )
            "slideright" -> listOf(
                matrixEffect { m, t ->
                    m.postTranslate(-2f * (1f - progress(t, start, end)), 0f)
                }
            )
            else -> listOf(gainEffect { t -> progress(t, start, end) })
        }
    }
}
