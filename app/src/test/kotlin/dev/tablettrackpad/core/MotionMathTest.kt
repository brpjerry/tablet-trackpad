package dev.tablettrackpad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MotionMathTest {
    @Test
    fun accelerationIsLinearWhenDisabled() {
        val accel = PointerAcceleration(pxPerDp = 2f).apply {
            speed = 2f
            acceleration = 0f
        }
        assertEquals(2f, accel.gain(0.01f), 1e-4f)
        assertEquals(2f, accel.gain(3f), 1e-4f)
    }

    @Test
    fun accelerationGainNeverDecreasesWithSpeed() {
        val accel = PointerAcceleration(pxPerDp = 2f).apply { acceleration = 1f }
        var previous = 0f
        var v = 0f
        while (v < 5f) {
            val g = accel.gain(v)
            assertTrue("gain dropped at $v", g >= previous - 1e-4f)
            previous = g
            v += 0.01f
        }
        assertTrue(accel.gain(0f) < accel.speed)
        assertTrue(accel.gain(4f) > accel.speed * 2)
    }

    @Test
    fun velocityOfSteadyMotion() {
        val v = VelocityEstimator()
        for (i in 0..10) v.add(i * 8L, i * 8f, -i * 4f)
        val out = FloatArray(2)
        v.velocity(80, out)
        assertEquals(1000f, out[0], 1f)
        assertEquals(-500f, out[1], 1f)
    }

    @Test
    fun velocityIsZeroAfterTheFingerRests() {
        val v = VelocityEstimator()
        for (i in 0..10) v.add(i * 8L, i * 8f, 0f)
        val out = FloatArray(2)
        v.velocity(80 + 100, out)
        assertEquals(0f, out[0], 0f)
    }

    @Test
    fun axisLockFollowsTheFirstMovement() {
        val lock = ScrollAxisLock()
        val out = FloatArray(2)
        lock.filter(2f, 20f, out)
        assertEquals(ScrollAxisLock.Axis.VERTICAL, lock.axis)
        lock.filter(30f, 1f, out)
        assertEquals(0f, out[0], 0f)
        assertEquals(1f, out[1], 0f)

        lock.reset()
        lock.filter(10f, 9f, out)
        assertEquals(ScrollAxisLock.Axis.FREE, lock.axis)
    }
}
