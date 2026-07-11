package com.mastar.editor.engine.effects

import android.graphics.Matrix
import androidx.media3.common.Effect
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.MatrixTransformation
import androidx.media3.effect.RgbMatrix

/**
 * Visible clip transitions built from Media3's time-varying primitives:
 * RgbMatrix (per-frame color matrix) and MatrixTransformation (per-frame
 * geometry). A transition on clip A animates A's tail (OUT phase) and the
 * next clip's head (IN phase) — same effects in preview and export.
 *
 * The C++ gl-transitions host remains for the cross-frame compositor
 * milestone; these run everywhere today.
 */
@UnstableApi
object Transitions {

    data class TransitionInfo(val id: String, val displayName: String)

    val TRANSITIONS = listOf(
        TransitionInfo("fade", "Fade"),
        TransitionInfo("flash", "Flash"),
        TransitionInfo("zoomin", "Zoom in"),
        TransitionInfo("zoomout", "Zoom out"),
        TransitionInfo("slideleft", "Slide ←"),
        TransitionInfo("slideright", "Slide →"),
    )

    /** Linear 0→1 progress inside [windowStartUs, windowEndUs]. */
    private fun progress(timeUs: Long, windowStartUs: Long, windowEndUs: Long): Float {
        if (windowEndUs <= windowStartUs) return 1f
        return ((timeUs - windowStartUs).toFloat() / (windowEndUs - windowStartUs))
            .coerceIn(0f, 1f)
    }

    private fun gainMatrix(gain: Float): FloatArray = floatArrayOf(
        gain, 0f, 0f, 0f,
        0f, gain, 0f, 0f,
        0f, 0f, gain, 0f,
        0f, 0f, 0f, 1f,
    )

    /**
     * OUT phase on the tail of a clip. [itemDurationUs] is the clip's
     * presented duration; the window is the last [windowUs] of it.
     * Unknown ids (including legacy shader ids) fall back to fade.
     */
    fun outEffects(id: String, itemDurationUs: Long, windowUs: Long): List<Effect> {
        val start = (itemDurationUs - windowUs).coerceAtLeast(0)
        val end = itemDurationUs
        return when (id) {
            "flash" -> listOf(
                RgbMatrix { timeUs, _ ->
                    gainMatrix(1f + 4f * progress(timeUs, start, end))
                }
            )
            "zoomin" -> listOf(
                MatrixTransformation { timeUs ->
                    val p = progress(timeUs, start, end)
                    Matrix().apply { setScale(1f + 0.4f * p, 1f + 0.4f * p) }
                },
                dipToBlack(start, end),
            )
            "zoomout" -> listOf(
                MatrixTransformation { timeUs ->
                    val p = progress(timeUs, start, end)
                    Matrix().apply { setScale(1f - 0.3f * p, 1f - 0.3f * p) }
                },
                dipToBlack(start, end),
            )
            "slideleft" -> listOf(
                MatrixTransformation { timeUs ->
                    // NDC spans 2 units; slide fully off-screen.
                    Matrix().apply { setTranslate(-2f * progress(timeUs, start, end), 0f) }
                }
            )
            "slideright" -> listOf(
                MatrixTransformation { timeUs ->
                    Matrix().apply { setTranslate(2f * progress(timeUs, start, end), 0f) }
                }
            )
            else -> listOf(dipToBlack(start, end)) // "fade" + legacy ids
        }
    }

    /** IN phase on the head of the next clip: first [windowUs] of it. */
    fun inEffects(id: String, windowUs: Long): List<Effect> {
        val start = 0L
        val end = windowUs.coerceAtLeast(1)
        return when (id) {
            "flash" -> listOf(
                RgbMatrix { timeUs, _ ->
                    gainMatrix(1f + 4f * (1f - progress(timeUs, start, end)))
                }
            )
            "zoomin" -> listOf(
                MatrixTransformation { timeUs ->
                    val p = 1f - progress(timeUs, start, end)
                    val s = 1f - 0.3f * p
                    Matrix().apply { setScale(s, s) }
                },
                riseFromBlack(start, end),
            )
            "zoomout" -> listOf(
                MatrixTransformation { timeUs ->
                    val p = 1f - progress(timeUs, start, end)
                    val s = 1f + 0.4f * p
                    Matrix().apply { setScale(s, s) }
                },
                riseFromBlack(start, end),
            )
            "slideleft" -> listOf(
                MatrixTransformation { timeUs ->
                    Matrix().apply {
                        setTranslate(2f * (1f - progress(timeUs, start, end)), 0f)
                    }
                }
            )
            "slideright" -> listOf(
                MatrixTransformation { timeUs ->
                    Matrix().apply {
                        setTranslate(-2f * (1f - progress(timeUs, start, end)), 0f)
                    }
                }
            )
            else -> listOf(riseFromBlack(start, end))
        }
    }

    private fun dipToBlack(startUs: Long, endUs: Long): Effect =
        RgbMatrix { timeUs, _ -> gainMatrix(1f - progress(timeUs, startUs, endUs)) }

    private fun riseFromBlack(startUs: Long, endUs: Long): Effect =
        RgbMatrix { timeUs, _ -> gainMatrix(progress(timeUs, startUs, endUs)) }
}
