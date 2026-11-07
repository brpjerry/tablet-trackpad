package dev.tablettrackpad.core

/**
 * Least-squares velocity over the most recent samples, used for scroll momentum at lift-off.
 */
class VelocityEstimator(
    private val windowMs: Long = 80,
    /** A finger that rested this long before lifting has no momentum left. */
    private val staleMs: Long = 50,
) {
    private val times = LongArray(CAPACITY)
    private val xs = FloatArray(CAPACITY)
    private val ys = FloatArray(CAPACITY)
    private var start = 0
    private var count = 0

    fun reset() {
        start = 0
        count = 0
    }

    fun add(timeMs: Long, x: Float, y: Float) {
        val index = (start + count) % CAPACITY
        times[index] = timeMs
        xs[index] = x
        ys[index] = y
        if (count < CAPACITY) count++ else start = (start + 1) % CAPACITY
        while (count > 1 && timeMs - times[start] > windowMs) {
            start = (start + 1) % CAPACITY
            count--
        }
    }

    /** Pixels per second along each axis at [nowMs]. */
    fun velocity(nowMs: Long, out: FloatArray) {
        out[0] = 0f
        out[1] = 0f
        if (count < 2) return
        val newest = times[(start + count - 1) % CAPACITY]
        if (nowMs - newest > staleMs) return

        var meanT = 0.0
        var meanX = 0.0
        var meanY = 0.0
        for (i in 0 until count) {
            val j = (start + i) % CAPACITY
            meanT += times[j]
            meanX += xs[j]
            meanY += ys[j]
        }
        meanT /= count
        meanX /= count
        meanY /= count

        var covX = 0.0
        var covY = 0.0
        var varT = 0.0
        for (i in 0 until count) {
            val j = (start + i) % CAPACITY
            val dt = times[j] - meanT
            covX += dt * (xs[j] - meanX)
            covY += dt * (ys[j] - meanY)
            varT += dt * dt
        }
        if (varT == 0.0) return
        out[0] = (covX / varT * 1000.0).toFloat()
        out[1] = (covY / varT * 1000.0).toFloat()
    }

    private companion object {
        const val CAPACITY = 32
    }
}
