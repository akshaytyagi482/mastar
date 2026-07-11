package com.mastar.editor.engine.speed

import androidx.media3.common.audio.SpeedProvider
import org.json.JSONArray
import org.json.JSONObject

/**
 * CapCut-style speed ramp: a piecewise-linear curve of speed over the clip's
 * source window. Stored as JSON {"points":[[fraction, speed], ...]} with
 * fractions 0..1 over the source span. Drives both the video
 * (SpeedChangeEffect) and audio (SpeedChangingAudioProcessor) via a stepped
 * SpeedProvider, and the timeline layout via the output-duration integral.
 */
class SpeedCurve private constructor(
    /** Sorted (fraction, speed) control points, first at 0, last at 1. */
    val points: List<Pair<Float, Float>>,
) {

    /** Speed at a source-window fraction (0..1), linear between points. */
    fun speedAt(fraction: Float): Float {
        val f = fraction.coerceIn(0f, 1f)
        val next = points.indexOfFirst { it.first >= f }
        if (next <= 0) return points[if (next == 0) 0 else points.lastIndex].second
        val (f0, s0) = points[next - 1]
        val (f1, s1) = points[next]
        if (f1 - f0 <= 1e-6f) return s1
        val t = (f - f0) / (f1 - f0)
        return s0 + (s1 - s0) * t
    }

    /** Timeline duration of a clip whose source window spans [sourceSpanMs]. */
    fun outputDurationMs(sourceSpanMs: Long): Long {
        // Numeric integral over fixed steps: robust for any curve shape.
        var totalMs = 0.0
        val steps = INTEGRAL_STEPS
        for (i in 0 until steps) {
            val f = (i + 0.5f) / steps
            totalMs += (sourceSpanMs.toDouble() / steps) / speedAt(f).coerceAtLeast(MIN_SPEED)
        }
        return totalMs.toLong().coerceAtLeast(1)
    }

    /**
     * SpeedProvider over the clip's source-media time, quantized to
     * [stepMs] steps (Media3's provider model is stepwise).
     */
    fun toSpeedProvider(sourceSpanMs: Long, stepMs: Long = 50): SpeedProvider {
        val stepCount = ((sourceSpanMs + stepMs - 1) / stepMs).toInt().coerceAtLeast(1)
        val speeds = FloatArray(stepCount) { i ->
            speedAt((i + 0.5f) / stepCount).coerceAtLeast(MIN_SPEED)
        }
        val stepUs = stepMs * 1000
        return object : SpeedProvider {
            override fun getSpeed(timeUs: Long): Float {
                val index = (timeUs / stepUs).toInt().coerceIn(0, stepCount - 1)
                return speeds[index]
            }

            override fun getNextSpeedChangeTimeUs(timeUs: Long): Long {
                val index = (timeUs / stepUs).toInt()
                var i = index
                while (i + 1 < stepCount) {
                    if (speeds[i + 1] != speeds[index.coerceIn(0, stepCount - 1)]) {
                        return (i + 1) * stepUs
                    }
                    i++
                }
                return androidx.media3.common.C.TIME_UNSET
            }
        }
    }

    fun toJson(): String = JSONObject().apply {
        put("points", JSONArray().apply {
            points.forEach { (f, s) ->
                put(JSONArray().apply { put(f.toDouble()); put(s.toDouble()) })
            }
        })
    }.toString()

    companion object {
        private const val INTEGRAL_STEPS = 200
        private const val MIN_SPEED = 0.1f
        // Touched from UI + IO threads; parse is idempotent so races are benign.
        private val cache = java.util.Collections.synchronizedMap(HashMap<String, SpeedCurve?>())

        /** CapCut-style presets. */
        val PRESETS: Map<String, SpeedCurve> = linkedMapOf(
            "Montage" to of(0f to 0.8f, 0.4f to 0.8f, 0.55f to 3.0f, 0.8f to 3.0f, 1f to 0.9f),
            "Hero" to of(0f to 2.0f, 0.35f to 2.0f, 0.5f to 0.4f, 0.65f to 2.0f, 1f to 2.0f),
            "Bullet" to of(0f to 1.0f, 0.3f to 3.5f, 0.5f to 0.3f, 0.7f to 3.5f, 1f to 1.0f),
            "Jump" to of(0f to 0.5f, 0.5f to 0.5f, 0.55f to 3.5f, 1f to 3.5f),
        )

        fun of(vararg pts: Pair<Float, Float>): SpeedCurve = SpeedCurve(normalize(pts.toList()))

        fun fromPoints(pts: List<Pair<Float, Float>>): SpeedCurve = SpeedCurve(normalize(pts))

        /** Null for null/blank/invalid JSON — old projects keep working. */
        fun parse(json: String?): SpeedCurve? {
            if (json.isNullOrBlank()) return null
            return cache.getOrPut(json) {
                runCatching {
                    val arr = JSONObject(json).getJSONArray("points")
                    val pts = ArrayList<Pair<Float, Float>>(arr.length())
                    for (i in 0 until arr.length()) {
                        val p = arr.getJSONArray(i)
                        pts.add(p.getDouble(0).toFloat() to p.getDouble(1).toFloat())
                    }
                    if (pts.size < 2) null else SpeedCurve(normalize(pts))
                }.getOrNull()
            }
        }

        private fun normalize(pts: List<Pair<Float, Float>>): List<Pair<Float, Float>> {
            val sorted = pts
                .map { it.first.coerceIn(0f, 1f) to it.second.coerceIn(MIN_SPEED, 8f) }
                .sortedBy { it.first }
                .toMutableList()
            if (sorted.first().first > 0f) sorted.add(0, 0f to sorted.first().second)
            if (sorted.last().first < 1f) sorted.add(1f to sorted.last().second)
            return sorted
        }
    }
}
