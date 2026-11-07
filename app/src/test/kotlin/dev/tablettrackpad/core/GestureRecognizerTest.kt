package dev.tablettrackpad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GestureRecognizerTest {
    private class FakeScheduler : Scheduler {
        private val pending = mutableListOf<Pair<Long, () -> Unit>>()
        var now = 0L

        override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
            val entry = (now + delayMs) to action
            pending += entry
            return Cancellable { pending.remove(entry) }
        }

        fun advanceTo(time: Long) {
            now = time
            val due = pending.filter { it.first <= time }
            pending.removeAll(due)
            due.forEach { it.second() }
        }
    }

    private class Recorder : GestureRecognizer.Listener {
        val events = mutableListOf<String>()
        var moveX = 0f
        var moveY = 0f
        var scrollX = 0f
        var scrollY = 0f
        var dragX = 0f
        var lastScale = 0f

        override fun onPointerMove(dx: Float, dy: Float, timeMs: Long) {
            if (events.lastOrNull() != "move") events += "move"
            moveX += dx
            moveY += dy
        }

        override fun onClick() { events += "click" }
        override fun onDoubleClick() { events += "double" }
        override fun onTwoFingerTap() { events += "right" }
        override fun onThreeFingerTap() { events += "tap3" }
        override fun onThreeFingerSwipe(direction: SwipeDirection) { events += "swipe3 $direction" }
        override fun onScrollBegin(inZone: Boolean) { events += if (inZone) "zoneScrollBegin" else "scrollBegin" }

        override fun onScroll(dx: Float, dy: Float, timeMs: Long) {
            scrollX += dx
            scrollY += dy
        }

        override fun onScrollEnd(velocityX: Float, velocityY: Float) { events += "scrollEnd" }
        override fun onPinchBegin() { events += "pinchBegin" }
        override fun onPinch(scale: Float) { lastScale = scale }
        override fun onPinchEnd(scale: Float) { events += "pinchEnd" }
        override fun onDragBegin(longPress: Boolean) { events += if (longPress) "dragBegin long" else "dragBegin" }
        override fun onDragMove(dx: Float, dy: Float, timeMs: Long) { dragX += dx }
        override fun onDragEnd() { events += "dragEnd" }
        override fun onGestureCancelled() { events += "cancel" }
    }

    private val scheduler = FakeScheduler()
    private val recorder = Recorder()
    private val config = GestureRecognizer.Config(
        tapSlopPx = 10f,
        scrollSlopPx = 12f,
        pinchSlopPx = 30f,
        swipeDistancePx = 100f,
    )
    private val recognizer = GestureRecognizer(config, scheduler, recorder)

    private fun send(action: TouchAction, changed: Int, t: Long, vararg pts: Triple<Int, Float, Float>) {
        scheduler.advanceTo(t)
        recognizer.onTouch(action, changed, pts.map { TouchPoint(it.first, it.second, it.third) }, t)
    }

    private fun p(id: Int, x: Float, y: Float) = Triple(id, x, y)

    private fun tap(t: Long, x: Float = 100f, y: Float = 100f) {
        send(TouchAction.DOWN, 0, t, p(0, x, y))
        send(TouchAction.UP, 0, t + 60, p(0, x, y))
    }

    @Test
    fun tapClicksAfterTheDragTimeout() {
        tap(0)
        assertEquals(emptyList<String>(), recorder.events)
        scheduler.advanceTo(60 + config.tapDragTimeoutMs)
        assertEquals(listOf("click"), recorder.events)
    }

    @Test
    fun aTapWhoseLiftMovedPastTheSlopMovesInsteadOfClicking() {
        send(TouchAction.DOWN, 0, 0, p(0, 100f, 100f))
        send(TouchAction.UP, 0, 60, p(0, 130f, 100f))
        scheduler.advanceTo(60 + config.tapDragTimeoutMs)
        assertEquals(listOf("move"), recorder.events)
        assertEquals(30f, recorder.moveX, 0.01f)
    }

    @Test
    fun theLastScrollMovementArrivesWithTheLift() {
        send(TouchAction.DOWN, 0, 0, p(0, 100f, 100f))
        send(TouchAction.POINTER_DOWN, 1, 10, p(0, 100f, 100f), p(1, 200f, 100f))
        send(TouchAction.MOVE, 0, 30, p(0, 100f, 130f), p(1, 200f, 130f))
        send(TouchAction.POINTER_UP, 1, 40, p(0, 100f, 150f), p(1, 200f, 150f))
        send(TouchAction.UP, 0, 50, p(0, 100f, 150f))
        assertEquals(listOf("scrollBegin", "scrollEnd"), recorder.events)
        assertEquals(50f, recorder.scrollY, 0.01f)
    }

    @Test
    fun tapClicksImmediatelyWithoutTapAndDrag() {
        recognizer.config = config.copy(tapAndDrag = false)
        tap(0)
        assertEquals(listOf("click"), recorder.events)
    }

    @Test
    fun restingBeforeTheDragMovesLongPressesFirst() {
        tap(0)
        send(TouchAction.DOWN, 0, 120, p(0, 100f, 100f))
        send(TouchAction.MOVE, 0, 700, p(0, 140f, 100f))
        send(TouchAction.UP, 0, 716, p(0, 140f, 100f))
        assertEquals(listOf("dragBegin long", "dragEnd"), recorder.events)
    }

    private fun withHoldForLongPress(tapAndDrag: Boolean = true) {
        recognizer.config = config.copy(holdForLongPress = true, tapAndDrag = tapAndDrag)
    }

    @Test
    fun doubleTapAndHoldLongPresses() {
        withHoldForLongPress()
        tap(0)
        send(TouchAction.DOWN, 0, 120, p(0, 100f, 100f))
        scheduler.advanceTo(120 + config.holdMs)
        assertEquals(listOf("dragBegin long"), recorder.events)
        // Moving afterwards drags what was long-pressed; lifting ends it, with no click.
        send(TouchAction.MOVE, 0, 500, p(0, 130f, 100f))
        send(TouchAction.UP, 0, 516, p(0, 130f, 100f))
        scheduler.advanceTo(2000)
        assertEquals(listOf("dragBegin long", "dragEnd"), recorder.events)
        assertEquals(30f, recorder.dragX, 0.01f)
    }

    @Test
    fun doubleTapStillDoubleClicksWithHoldForLongPress() {
        withHoldForLongPress()
        tap(0)
        tap(150)
        scheduler.advanceTo(2000)
        assertEquals(listOf("double"), recorder.events)
    }

    @Test
    fun twoFingerTapDoesNothingWithHoldForLongPress() {
        withHoldForLongPress()
        send(TouchAction.DOWN, 0, 0, p(0, 100f, 100f))
        send(TouchAction.POINTER_DOWN, 1, 20, p(0, 100f, 100f), p(1, 200f, 100f))
        send(TouchAction.POINTER_UP, 1, 100, p(0, 100f, 100f), p(1, 200f, 100f))
        send(TouchAction.UP, 0, 110, p(0, 100f, 100f))
        scheduler.advanceTo(2000)
        assertEquals(emptyList<String>(), recorder.events)
    }

    @Test
    fun holdForLongPressWorksWithoutTapAndDrag() {
        withHoldForLongPress(tapAndDrag = false)
        tap(0)
        // A second touch that moves is a click followed by pointer motion, not a drag.
        send(TouchAction.DOWN, 0, 120, p(0, 100f, 100f))
        send(TouchAction.MOVE, 0, 150, p(0, 130f, 100f))
        send(TouchAction.UP, 0, 166, p(0, 130f, 100f))
        scheduler.advanceTo(2000)
        assertEquals(listOf("click", "move"), recorder.events)
        // A second touch that holds still is still a long press.
        recorder.events.clear()
        tap(3000)
        send(TouchAction.DOWN, 0, 3120, p(0, 100f, 100f))
        scheduler.advanceTo(3120 + config.holdMs)
        assertEquals(listOf("dragBegin long"), recorder.events)
    }

    @Test
    fun longTouchIsNotATap() {
        send(TouchAction.DOWN, 0, 0, p(0, 100f, 100f))
        send(TouchAction.UP, 0, 500, p(0, 100f, 100f))
        scheduler.advanceTo(2000)
        assertEquals(emptyList<String>(), recorder.events)
    }

    @Test
    fun twoQuickTapsDoubleClick() {
        tap(0)
        tap(150)
        scheduler.advanceTo(2000)
        assertEquals(listOf("double"), recorder.events)
    }

    @Test
    fun movementWithinSlopDoesNotMoveThePointer() {
        send(TouchAction.DOWN, 0, 0, p(0, 100f, 100f))
        send(TouchAction.MOVE, 0, 16, p(0, 105f, 103f))
        assertEquals(emptyList<String>(), recorder.events)
        send(TouchAction.MOVE, 0, 32, p(0, 130f, 100f))
        // Once past the slop, the whole movement since touch-down comes through.
        assertEquals(30f, recorder.moveX, 0.01f)
        send(TouchAction.UP, 0, 48, p(0, 130f, 100f))
        scheduler.advanceTo(2000)
        assertEquals(listOf("move"), recorder.events)
    }

    @Test
    fun tapThenTouchAndMoveDrags() {
        tap(0)
        send(TouchAction.DOWN, 0, 120, p(0, 100f, 100f))
        send(TouchAction.MOVE, 0, 400, p(0, 140f, 100f))
        send(TouchAction.MOVE, 0, 416, p(0, 160f, 100f))
        send(TouchAction.UP, 0, 432, p(0, 160f, 100f))
        scheduler.advanceTo(2000)
        assertEquals(listOf("dragBegin", "dragEnd"), recorder.events)
        assertEquals(60f, recorder.dragX, 0.01f)
    }

    @Test
    fun tapThenTwoFingersClicksFirst() {
        tap(0)
        send(TouchAction.DOWN, 0, 120, p(0, 100f, 100f))
        send(TouchAction.POINTER_DOWN, 1, 130, p(0, 100f, 100f), p(1, 200f, 100f))
        assertEquals(listOf("click"), recorder.events)
    }

    @Test
    fun twoFingerTapRightClicks() {
        send(TouchAction.DOWN, 0, 0, p(0, 100f, 100f))
        send(TouchAction.POINTER_DOWN, 1, 20, p(0, 100f, 100f), p(1, 200f, 100f))
        send(TouchAction.POINTER_UP, 1, 100, p(0, 100f, 100f), p(1, 200f, 100f))
        send(TouchAction.UP, 0, 110, p(0, 100f, 100f))
        assertEquals(listOf("right"), recorder.events)
    }

    @Test
    fun twoFingerDragScrollsAndRemainingFingerDoesNothing() {
        send(TouchAction.DOWN, 0, 0, p(0, 100f, 100f))
        send(TouchAction.POINTER_DOWN, 1, 10, p(0, 100f, 100f), p(1, 200f, 100f))
        for (i in 1..10) {
            val y = 100f + i * 10f
            send(TouchAction.MOVE, 0, 10L + i * 16, p(0, 100f, y), p(1, 200f, y))
        }
        assertEquals(100f, recorder.scrollY, 0.01f)
        assertEquals(0f, recorder.scrollX, 0.01f)
        send(TouchAction.POINTER_UP, 1, 180, p(0, 100f, 200f), p(1, 200f, 200f))
        send(TouchAction.MOVE, 0, 196, p(0, 300f, 300f))
        send(TouchAction.UP, 0, 212, p(0, 300f, 300f))
        assertEquals(listOf("scrollBegin", "scrollEnd"), recorder.events)
    }

    private fun withScrollZone() {
        // The right-hand strip of a 500 px wide pad.
        recognizer.config = config.copy(inScrollZone = { x, _ -> x > 440f })
    }

    @Test
    fun oneFingerInTheScrollZoneScrollsVertically() {
        withScrollZone()
        send(TouchAction.DOWN, 0, 0, p(0, 470f, 100f))
        send(TouchAction.MOVE, 0, 16, p(0, 472f, 106f))
        assertEquals(emptyList<String>(), recorder.events)
        for (i in 1..10) send(TouchAction.MOVE, 0, 16L + i * 16, p(0, 470f + i * 3f, 100f + i * 10f))
        send(TouchAction.UP, 0, 200, p(0, 500f, 200f))
        scheduler.advanceTo(2000)
        assertEquals(listOf("zoneScrollBegin", "scrollEnd"), recorder.events)
        // All of the vertical travel, none of the sideways drift, and the pointer stays put.
        assertEquals(100f, recorder.scrollY, 0.01f)
        assertEquals(0f, recorder.scrollX, 0.01f)
        assertEquals(0f, recorder.moveX, 0.01f)
    }

    @Test
    fun sidewaysOutOfTheScrollZoneMovesThePointer() {
        withScrollZone()
        send(TouchAction.DOWN, 0, 0, p(0, 470f, 100f))
        send(TouchAction.MOVE, 0, 16, p(0, 440f, 104f))
        send(TouchAction.MOVE, 0, 32, p(0, 400f, 104f))
        send(TouchAction.UP, 0, 48, p(0, 400f, 104f))
        scheduler.advanceTo(2000)
        assertEquals(listOf("move"), recorder.events)
        assertEquals(-70f, recorder.moveX, 0.01f)
    }

    @Test
    fun tapInTheScrollZoneStillClicks() {
        withScrollZone()
        tap(0, x = 470f)
        scheduler.advanceTo(2000)
        assertEquals(listOf("click"), recorder.events)
    }

    @Test
    fun twoFingersFromTheScrollZoneScrollNormally() {
        withScrollZone()
        send(TouchAction.DOWN, 0, 0, p(0, 470f, 100f))
        send(TouchAction.POINTER_DOWN, 1, 10, p(0, 470f, 100f), p(1, 370f, 100f))
        send(TouchAction.MOVE, 0, 26, p(0, 490f, 150f), p(1, 390f, 150f))
        send(TouchAction.UP, 0, 42, p(0, 490f, 150f))
        assertEquals(listOf("scrollBegin", "scrollEnd"), recorder.events)
        assertEquals(20f, recorder.scrollX, 0.01f)
    }

    @Test
    fun liftingAndAddingFingersDoesNotJumpTheScroll() {
        send(TouchAction.DOWN, 0, 0, p(0, 100f, 100f))
        send(TouchAction.POINTER_DOWN, 1, 10, p(0, 100f, 100f), p(1, 200f, 100f))
        send(TouchAction.MOVE, 0, 26, p(0, 100f, 150f), p(1, 200f, 150f))
        // A third finger far away must not move the centroid-based scroll.
        send(TouchAction.POINTER_DOWN, 2, 30, p(0, 100f, 150f), p(1, 200f, 150f), p(2, 150f, 600f))
        send(TouchAction.MOVE, 0, 46, p(0, 100f, 160f), p(1, 200f, 160f), p(2, 150f, 610f))
        assertEquals(60f, recorder.scrollY, 0.01f)
    }

    @Test
    fun spreadingFingersPinches() {
        send(TouchAction.DOWN, 0, 0, p(0, 200f, 100f))
        send(TouchAction.POINTER_DOWN, 1, 10, p(0, 200f, 100f), p(1, 300f, 100f))
        // One finger held still, the other moves away: a pinch, not a scroll.
        send(TouchAction.MOVE, 0, 26, p(0, 200f, 100f), p(1, 350f, 100f))
        send(TouchAction.MOVE, 0, 42, p(0, 200f, 100f), p(1, 400f, 100f))
        send(TouchAction.POINTER_UP, 1, 58, p(0, 200f, 100f), p(1, 400f, 100f))
        send(TouchAction.UP, 0, 70, p(0, 200f, 100f))
        assertEquals(listOf("pinchBegin", "pinchEnd"), recorder.events)
        assertEquals(2f, recorder.lastScale, 0.01f)
    }

    @Test
    fun threeFingerSwipeFiresOnce() {
        send(TouchAction.DOWN, 0, 0, p(0, 100f, 300f))
        send(TouchAction.POINTER_DOWN, 1, 5, p(0, 100f, 300f), p(1, 200f, 300f))
        send(TouchAction.POINTER_DOWN, 2, 10, p(0, 100f, 300f), p(1, 200f, 300f), p(2, 300f, 300f))
        for (i in 1..10) {
            val y = 300f - i * 20f
            send(TouchAction.MOVE, 0, 10L + i * 16, p(0, 100f, y), p(1, 200f, y), p(2, 300f, y))
        }
        assertEquals(listOf("swipe3 UP"), recorder.events)
    }

    @Test
    fun threeFingerTap() {
        send(TouchAction.DOWN, 0, 0, p(0, 100f, 300f))
        send(TouchAction.POINTER_DOWN, 1, 5, p(0, 100f, 300f), p(1, 200f, 300f))
        send(TouchAction.POINTER_DOWN, 2, 10, p(0, 100f, 300f), p(1, 200f, 300f), p(2, 300f, 300f))
        send(TouchAction.POINTER_UP, 2, 80, p(0, 100f, 300f), p(1, 200f, 300f), p(2, 300f, 300f))
        send(TouchAction.POINTER_UP, 1, 85, p(0, 100f, 300f), p(1, 200f, 300f))
        send(TouchAction.UP, 0, 90, p(0, 100f, 300f))
        assertEquals(listOf("tap3"), recorder.events)
    }

    @Test
    fun cancelReportsAndResets() {
        send(TouchAction.DOWN, 0, 0, p(0, 100f, 100f))
        send(TouchAction.MOVE, 0, 16, p(0, 150f, 100f))
        send(TouchAction.CANCEL, 0, 32, p(0, 150f, 100f))
        assertEquals(listOf("move", "cancel"), recorder.events)
        tap(100)
        scheduler.advanceTo(1000)
        assertTrue(recorder.events.last() == "click")
    }

    @Test
    fun missedUpIsTreatedAsCancel() {
        send(TouchAction.DOWN, 0, 0, p(0, 100f, 100f))
        send(TouchAction.MOVE, 0, 16, p(0, 150f, 100f))
        send(TouchAction.DOWN, 0, 500, p(0, 100f, 100f))
        assertEquals(listOf("move", "cancel"), recorder.events)
    }
}
