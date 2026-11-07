package dev.tablettrackpad.core

import kotlin.math.hypot

/**
 * Maps finger motion on the pad to cursor motion, with a laptop-style acceleration curve: slow
 * movements are damped for precision and fast ones are boosted so the cursor can cross the screen.
 */
class PointerAcceleration(private val pxPerDp: Float) {
    /** Base gain applied at every speed. */
    var speed = 2f

    /** 0 gives a linear response; 1 gives the strongest curve. */
    var acceleration = 0.5f

    private var lastTimeMs = NO_TIME
    private var smoothedSpeed = 0f

    fun reset() {
        lastTimeMs = NO_TIME
    }

    fun apply(dx: Float, dy: Float, timeMs: Long, out: FloatArray) {
        val elapsed = timeMs - lastTimeMs
        val fresh = lastTimeMs == NO_TIME || elapsed > IDLE_MS
        val dt = if (fresh) FRAME_MS else elapsed.coerceAtLeast(MIN_DT_MS).toFloat()
        lastTimeMs = timeMs

        val dpPerMs = hypot(dx, dy) / pxPerDp / dt
        smoothedSpeed = if (fresh) dpPerMs else 0.5f * dpPerMs + 0.5f * smoothedSpeed
        val g = gain(smoothedSpeed)
        out[0] = dx * g
        out[1] = dy * g
    }

    /** Gain for a finger moving at [dpPerMs]. */
    fun gain(dpPerMs: Float): Float {
        val slowness = 1f - (dpPerMs / PRECISION_SPEED).coerceAtMost(1f)
        val precision = 1f - acceleration * PRECISION_DAMPING * slowness
        val boost = 1f + acceleration * ((dpPerMs - BOOST_START) / BOOST_RANGE).coerceIn(0f, MAX_BOOST)
        return speed * precision * boost
    }

    private companion object {
        const val NO_TIME = Long.MIN_VALUE
        const val IDLE_MS = 100L
        const val MIN_DT_MS = 4L
        const val FRAME_MS = 8f

        /** Below this speed (dp/ms) movements are damped. */
        const val PRECISION_SPEED = 0.12f
        const val PRECISION_DAMPING = 0.4f

        /** Above this speed (dp/ms) movements are boosted. */
        const val BOOST_START = 0.3f
        const val BOOST_RANGE = 0.8f
        const val MAX_BOOST = 3f
    }
}
