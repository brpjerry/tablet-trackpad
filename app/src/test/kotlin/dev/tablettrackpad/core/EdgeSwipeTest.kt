package dev.tablettrackpad.core

import dev.tablettrackpad.core.EdgeSwipe.Verdict
import org.junit.Assert.assertEquals
import org.junit.Test

class EdgeSwipeTest {
    private val trigger = 36f
    private val slop = 8f

    private fun judge(inward: Float, sideways: Float) = EdgeSwipe.judge(inward, sideways, trigger, slop)

    @Test
    fun movingFarEnoughInwardIsASwipe() {
        assertEquals(Verdict.SWIPE, judge(40f, 5f))
    }

    @Test
    fun smallMovementsStayUndecided() {
        assertEquals(Verdict.UNDECIDED, judge(0f, 0f))
        assertEquals(Verdict.UNDECIDED, judge(20f, 6f))
        assertEquals(Verdict.UNDECIDED, judge(-6f, 0f))
    }

    @Test
    fun movingOutwardIsNotASwipe() {
        assertEquals(Verdict.NOT_SWIPE, judge(-10f, 0f))
    }

    @Test
    fun movingMostlySidewaysIsNotASwipe() {
        assertEquals(Verdict.NOT_SWIPE, judge(12f, 10f))
        // Far enough inward, but at too shallow an angle.
        assertEquals(Verdict.NOT_SWIPE, judge(40f, 30f))
    }

    @Test
    fun aSteepEnoughSwipeWinsDespiteSomeSidewaysMovement() {
        assertEquals(Verdict.SWIPE, judge(60f, 30f))
    }
}
