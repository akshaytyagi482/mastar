package com.mastar.editor.engine.keyframe

import com.mastar.editor.data.db.EasingType
import com.mastar.editor.data.db.KeyframeEntity
import com.mastar.editor.data.db.KeyframeProperty
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyframeEngineTest {

    private fun kf(timeMs: Long, value: Float, easing: EasingType = EasingType.LINEAR) =
        KeyframeEntity(
            clipId = 1L,
            property = KeyframeProperty.SCALE,
            timeMs = timeMs,
            value = value,
            easing = easing,
        )

    @Test
    fun `no keyframes returns static fallback`() {
        assertEquals(1.5f, KeyframeEngine.valueAt(emptyList(), 500, 1.5f), 0f)
    }

    @Test
    fun `holds edge values outside keyframe range`() {
        val kfs = listOf(kf(1000, 1f), kf(2000, 2f))
        assertEquals(1f, KeyframeEngine.valueAt(kfs, 0, 0f), 0f)
        assertEquals(2f, KeyframeEngine.valueAt(kfs, 3000, 0f), 0f)
    }

    @Test
    fun `linear interpolation at midpoint`() {
        // CapCut-style zoom: 100% at 0ms -> 200% at 1000ms
        val kfs = listOf(kf(0, 1f), kf(1000, 2f))
        assertEquals(1.5f, KeyframeEngine.valueAt(kfs, 500, 0f), 1e-4f)
        assertEquals(1.25f, KeyframeEngine.valueAt(kfs, 250, 0f), 1e-4f)
    }

    @Test
    fun `multi-segment interpolation picks correct segment`() {
        val kfs = listOf(kf(0, 0f), kf(1000, 10f), kf(3000, 0f))
        assertEquals(5f, KeyframeEngine.valueAt(kfs, 500, 0f), 1e-4f)
        assertEquals(10f, KeyframeEngine.valueAt(kfs, 1000, 0f), 1e-4f)
        assertEquals(5f, KeyframeEngine.valueAt(kfs, 2000, 0f), 1e-4f)
    }

    @Test
    fun `ease-in starts slower than linear`() {
        val kfs = listOf(kf(0, 0f, EasingType.EASE_IN), kf(1000, 1f))
        val atQuarter = KeyframeEngine.valueAt(kfs, 250, 0f)
        assertTrue("ease-in at 25% ($atQuarter) should be < 0.25", atQuarter < 0.25f)
    }

    @Test
    fun `ease-out starts faster than linear`() {
        val kfs = listOf(kf(0, 0f, EasingType.EASE_OUT), kf(1000, 1f))
        val atQuarter = KeyframeEngine.valueAt(kfs, 250, 0f)
        assertTrue("ease-out at 25% ($atQuarter) should be > 0.25", atQuarter > 0.25f)
    }

    @Test
    fun `easing is monotonic and hits endpoints`() {
        for (type in EasingType.entries) {
            var prev = 0f
            assertEquals(0f, CubicBezierEasing.forType(type, 0f), 1e-3f)
            var t = 0.01f
            while (t <= 1f) {
                val v = CubicBezierEasing.forType(type, t)
                assertTrue("$type must be monotonic at t=$t", v >= prev - 1e-4f)
                prev = v
                t += 0.01f
            }
            assertEquals(1f, CubicBezierEasing.forType(type, 1f), 1e-3f)
        }
    }

    @Test
    fun `transformAt overlays animated properties on static values`() {
        val scaleKfs = listOf(kf(0, 1f), kf(1000, 2f))
        val transform = KeyframeEngine.transformAt(
            keyframesByProperty = mapOf(KeyframeProperty.SCALE to scaleKfs),
            timeMs = 500,
            staticPositionX = 0.5f,
            staticPositionY = 0.5f,
            staticScale = 1f,
            staticRotationDeg = 45f,
            staticOpacity = 0.8f,
            staticVolume = 1f,
        )
        assertEquals(1.5f, transform.scale, 1e-4f) // animated
        assertEquals(45f, transform.rotationDeg, 0f) // static passthrough
        assertEquals(0.8f, transform.opacity, 0f)
    }
}
