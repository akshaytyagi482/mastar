package com.mastar.editor.engine.keyframe

import com.mastar.editor.data.db.KeyframeEntity
import com.mastar.editor.data.db.KeyframeProperty

/**
 * The interpolation engine. Given a clip's keyframes and a playback time,
 * produces the animated transform for that frame. Called once per frame
 * during playback, so it allocates nothing on the hot path except the
 * small result holder.
 */
object KeyframeEngine {

    /** Resolved per-frame transform, fed into the OpenGL matrix wrapper. */
    data class Transform(
        val positionX: Float,
        val positionY: Float,
        val scale: Float,
        val rotationDeg: Float,
        val opacity: Float,
        val volume: Float,
    )

    /**
     * Interpolates a single property at [timeMs] (relative to clip start).
     * [keyframes] must be sorted by time and all share the same property.
     * Returns [fallback] when the clip has no keyframes for this property.
     */
    fun valueAt(keyframes: List<KeyframeEntity>, timeMs: Long, fallback: Float): Float {
        if (keyframes.isEmpty()) return fallback

        // Before the first / after the last keyframe: hold the edge value.
        if (timeMs <= keyframes.first().timeMs) return keyframes.first().value
        if (timeMs >= keyframes.last().timeMs) return keyframes.last().value

        // Find the segment containing timeMs (binary search — keyframe lists
        // are small, but playback calls this 60x/sec for every property).
        var lo = 0
        var hi = keyframes.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (keyframes[mid].timeMs <= timeMs) lo = mid else hi = mid
        }
        val from = keyframes[lo]
        val to = keyframes[hi]

        val span = (to.timeMs - from.timeMs).toFloat()
        if (span <= 0f) return to.value
        val t = (timeMs - from.timeMs) / span
        val eased = CubicBezierEasing.forType(from.easing, t)
        return from.value + (to.value - from.value) * eased
    }

    /**
     * Resolves the full transform for a clip at [timeMs], overlaying animated
     * properties on top of the clip's static values.
     */
    fun transformAt(
        keyframesByProperty: Map<KeyframeProperty, List<KeyframeEntity>>,
        timeMs: Long,
        staticPositionX: Float,
        staticPositionY: Float,
        staticScale: Float,
        staticRotationDeg: Float,
        staticOpacity: Float,
        staticVolume: Float,
    ): Transform = Transform(
        positionX = valueAt(keyframesByProperty[KeyframeProperty.POSITION_X].orEmpty(), timeMs, staticPositionX),
        positionY = valueAt(keyframesByProperty[KeyframeProperty.POSITION_Y].orEmpty(), timeMs, staticPositionY),
        scale = valueAt(keyframesByProperty[KeyframeProperty.SCALE].orEmpty(), timeMs, staticScale),
        rotationDeg = valueAt(keyframesByProperty[KeyframeProperty.ROTATION].orEmpty(), timeMs, staticRotationDeg),
        opacity = valueAt(keyframesByProperty[KeyframeProperty.OPACITY].orEmpty(), timeMs, staticOpacity),
        volume = valueAt(keyframesByProperty[KeyframeProperty.VOLUME].orEmpty(), timeMs, staticVolume),
    )
}
