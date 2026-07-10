package com.mastar.editor.engine.keyframe

import com.mastar.editor.data.db.EasingType

/**
 * Cubic-bezier easing, matching the CSS/After Effects convention:
 * curve anchored at (0,0) and (1,1) with two control points.
 *
 * Pure math, no Android imports — unit-testable on the JVM.
 */
class CubicBezierEasing(
    private val x1: Float,
    private val y1: Float,
    private val x2: Float,
    private val y2: Float,
) {
    /** Maps linear progress t (0..1) to eased progress (0..1). */
    fun ease(t: Float): Float {
        if (t <= 0f) return 0f
        if (t >= 1f) return 1f
        return bezierY(solveForU(t))
    }

    // Bezier is parameterized by u, but callers give us time-fraction x.
    // Solve x(u) = t for u with Newton-Raphson, falling back to bisection.
    private fun solveForU(x: Float): Float {
        var u = x
        repeat(NEWTON_ITERATIONS) {
            val err = bezierX(u) - x
            if (kotlin.math.abs(err) < EPSILON) return u
            val dx = bezierXDerivative(u)
            if (kotlin.math.abs(dx) < 1e-6f) return@repeat
            u -= err / dx
        }
        // Bisection fallback for flat-derivative regions.
        var lo = 0f
        var hi = 1f
        u = x
        while (hi - lo > EPSILON) {
            if (bezierX(u) < x) lo = u else hi = u
            u = (lo + hi) / 2f
        }
        return u
    }

    private fun bezierX(u: Float) = cubic(u, x1, x2)
    private fun bezierY(u: Float) = cubic(u, y1, y2)

    private fun cubic(u: Float, p1: Float, p2: Float): Float {
        val inv = 1f - u
        return 3f * inv * inv * u * p1 + 3f * inv * u * u * p2 + u * u * u
    }

    private fun bezierXDerivative(u: Float): Float {
        val inv = 1f - u
        return 3f * inv * inv * x1 + 6f * inv * u * (x2 - x1) + 3f * u * u * (1f - x2)
    }

    companion object {
        private const val NEWTON_ITERATIONS = 8
        private const val EPSILON = 1e-4f

        val EASE_IN = CubicBezierEasing(0.42f, 0f, 1f, 1f)
        val EASE_OUT = CubicBezierEasing(0f, 0f, 0.58f, 1f)
        val EASE_IN_OUT = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)

        fun forType(type: EasingType, t: Float): Float = when (type) {
            EasingType.LINEAR -> t.coerceIn(0f, 1f)
            EasingType.EASE_IN -> EASE_IN.ease(t)
            EasingType.EASE_OUT -> EASE_OUT.ease(t)
            EasingType.EASE_IN_OUT -> EASE_IN_OUT.ease(t)
        }
    }
}
