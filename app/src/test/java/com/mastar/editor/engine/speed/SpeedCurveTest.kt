package com.mastar.editor.engine.speed

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

// Robolectric provides the real org.json for the round-trip tests.
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SpeedCurveTest {

    @Test
    fun `constant curve matches plain division`() {
        val curve = SpeedCurve.of(0f to 2f, 1f to 2f)
        // 10s of source at 2x -> 5s of output (integration tolerance ±2%).
        val out = curve.outputDurationMs(10_000)
        assertTrue("expected ~5000, got $out", out in 4900..5100)
    }

    @Test
    fun `ramp duration sits between extremes`() {
        val curve = SpeedCurve.of(0f to 1f, 1f to 4f)
        val out = curve.outputDurationMs(10_000)
        // Faster than 1x everywhere except the very start.
        assertTrue(out < 10_000)
        assertTrue(out > 2_500)
    }

    @Test
    fun `provider speeds follow the curve and stay positive`() {
        val curve = SpeedCurve.of(0f to 0.5f, 0.5f to 0.5f, 0.6f to 3f, 1f to 3f)
        val provider = curve.toSpeedProvider(sourceSpanMs = 4000)
        assertTrue(provider.getSpeed(0) in 0.4f..0.7f)
        assertTrue(provider.getSpeed(3_900_000) in 2.5f..3.2f)
        var t = 0L
        var changes = 0
        while (true) {
            val next = provider.getNextSpeedChangeTimeUs(t)
            if (next == androidx.media3.common.C.TIME_UNSET) break
            assertTrue("monotonic change times", next > t)
            t = next
            changes++
            if (changes > 1000) error("provider never terminates")
        }
        assertTrue("ramp must actually change speed", changes > 0)
    }

    @Test
    fun `json round trip preserves shape`() {
        val curve = SpeedCurve.PRESETS.getValue("Hero")
        val restored = SpeedCurve.parse(curve.toJson())!!
        assertEquals(curve.points.size, restored.points.size)
        assertEquals(curve.speedAt(0.5f), restored.speedAt(0.5f), 1e-3f)
    }

    @Test
    fun `invalid json parses to null, not a crash`() {
        assertNull(SpeedCurve.parse("not json"))
        assertNull(SpeedCurve.parse(""))
        assertNull(SpeedCurve.parse(null))
    }

    @Test
    fun `points are normalized to cover the full range`() {
        val curve = SpeedCurve.fromPoints(listOf(0.3f to 2f, 0.7f to 1f))
        assertEquals(0f, curve.points.first().first)
        assertEquals(1f, curve.points.last().first)
    }
}
