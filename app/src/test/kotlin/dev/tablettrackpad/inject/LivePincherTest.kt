package dev.tablettrackpad.inject

import dev.tablettrackpad.core.Bounds
import dev.tablettrackpad.core.GesturePlanner
import dev.tablettrackpad.core.IPoint
import dev.tablettrackpad.inject.FakeInjector.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LivePincherTest {
    private val scheduler = ManualScheduler()
    private val injector = FakeInjector(scheduler::now)
    private val planner = GesturePlanner(17, 50).apply { bounds = Bounds(2960, 1848) }
    private val minSpan = 270
    private val pincher = LivePincher(injector, planner, minSpan, scheduler, scheduler::now)

    private val center = IPoint(1480, 900)
    private val lo = planner.pinchSpans(minSpan)!!.first

    /** Spreads the real fingers from [from] to [to] times their starting spacing, a step every [stepMs]. */
    private fun ramp(to: Float, from: Float = 1f, durationMs: Long = 300, stepMs: Long = 8, pincher: LivePincher = this.pincher) {
        val steps = (durationMs / stepMs).toInt()
        for (i in 1..steps) {
            scheduler.advanceBy(stepMs)
            pincher.update(from + (to - from) * i / steps)
        }
    }

    private fun span(paths: List<List<IPoint>>, last: Boolean): Float {
        val (a, b) = paths.map { if (last) it.last() else it.first() }
        return distance(a, b)
    }

    /** The zoom the injected fingers delivered: each grip's spread, times the pinches played afterwards. */
    private fun delivered(): Float {
        val start = HashMap<Int, Float>()
        val end = HashMap<Int, Float>()
        var rest = 1f
        for (call in injector.calls) {
            when (call.kind) {
                Kind.OPEN -> {
                    start[call.job] = span(call.paths, last = false)
                    end[call.job] = start.getValue(call.job)
                }
                Kind.EXTEND -> end[call.job] = span(call.paths, last = true)
                Kind.DISPATCH_ALL -> for (gesture in call.gestures) {
                    val (a, b) = gesture
                    rest *= distance(a.points.last(), b.points.last()) / distance(a.points.first(), b.points.first())
                }
                else -> Unit
            }
        }
        return start.keys.fold(rest) { total, job -> total * end.getValue(job) / start.getValue(job) }
    }

    @Test
    fun theNextGripWaitsForTheLiftToPlayOut() {
        pincher.begin(center)
        ramp(to = 4f)
        val lift = injector.calls.first { it.label == "pinch release" }
        assertEquals(false, lift.willContinue)
        val regrip = injector.of(Kind.OPEN).first { it.job > lift.job }
        assertTrue("re-gripped ${regrip.at - lift.at} ms after the lift", regrip.at - lift.at >= 40)
        assertTrue(injector.of(Kind.REFUSED).isEmpty())
    }

    @Test
    fun regripsDeliverTheWholeZoom() {
        pincher.begin(center)
        ramp(to = 4f)
        pincher.finish(4f)
        scheduler.advanceBy(500)
        assertEquals(2, injector.of(Kind.OPEN).size)
        assertEquals(4f, delivered(), 0.04f)
    }

    @Test
    fun zoomingOutRegripsTheOtherWay() {
        pincher.begin(center)
        ramp(to = 0.25f)
        pincher.finish(0.25f)
        scheduler.advanceBy(500)
        assertEquals(0.25f, delivered(), 0.01f)
    }

    @Test
    fun zoomOutGripsStartAtTwiceTheNarrowestSpacing() {
        pincher.begin(center)
        ramp(to = 0.2f)
        scheduler.advanceBy(100)
        val grips = injector.of(Kind.OPEN)
        assertTrue(grips.size >= 3)
        for (grip in grips) assertEquals(2f * lo, span(grip.paths, last = false), 2f)
    }

    @Test
    fun aCancellationReportedForAnEarlierGripDoesNotBreakThePinch() {
        pincher.begin(center)
        ramp(to = 3.5f)
        val (first, second) = injector.of(Kind.OPEN)
        first.complete(Outcome.CANCELLED)
        ramp(from = 3.5f, to = 4f, durationMs = 80)
        pincher.finish(4f)
        scheduler.advanceBy(500)
        assertTrue(injector.of(Kind.EXTEND).last().job == second.job)
        assertTrue(injector.of(Kind.DISPATCH_ALL, "pinch replay").isEmpty())
        assertEquals(4f, delivered(), 0.04f)
    }

    @Test
    fun aCancelledGripPlaysTheRestOnRelease() {
        pincher.begin(center)
        ramp(to = 2f)
        injector.of(Kind.OPEN).single().complete(Outcome.CANCELLED)
        ramp(from = 2f, to = 4f)
        pincher.finish(4f)
        assertEquals(1, injector.of(Kind.DISPATCH_ALL, "pinch replay").size)
        assertEquals(4f, delivered(), 0.1f)
    }

    @Test
    fun liftingDeliversTheLastScaleEvenRightAfterAStep() {
        pincher.begin(center)
        pincher.update(1.1f)
        scheduler.advanceBy(6)
        val step = injector.of(Kind.EXTEND).single()
        assertEquals(lo * 1.1f, span(step.paths, last = true), 1f)
        // Too soon for another step, but this is the last chance to deliver it.
        scheduler.advanceBy(2)
        pincher.finish(1.3f)
        val lift = injector.of(Kind.EXTEND).last()
        assertEquals(false, lift.willContinue)
        assertEquals(lo * 1.3f, span(lift.paths, last = true), 1f)
    }

    /** Spreads past the end of the first grip and lifts while the re-grip still waits. */
    private fun liftDuringTheRegripGap() {
        pincher.begin(center)
        ramp(to = 3.3f)
        assertEquals(1, injector.of(Kind.OPEN).size)
        pincher.finish(3.3f)
    }

    @Test
    fun theRestPlaysOnceTheLastLiftHasPlayed() {
        liftDuringTheRegripGap()
        assertTrue(injector.of(Kind.DISPATCH_ALL).isEmpty())
        scheduler.advanceBy(40)
        assertEquals(1, injector.of(Kind.DISPATCH_ALL, "pinch rest").size)
        assertEquals(3.3f, delivered(), 0.04f)
    }

    @Test
    fun aRestStillWaitingIsDroppedWhenAnotherGestureStarts() {
        liftDuringTheRegripGap()
        injector.dispatch(planner.tap(IPoint(200, 200)), "click")
        scheduler.advanceBy(500)
        assertTrue(injector.of(Kind.DISPATCH_ALL).isEmpty())
    }

    @Test
    fun cancellingDropsARestStillWaiting() {
        liftDuringTheRegripGap()
        pincher.cancel()
        scheduler.advanceBy(500)
        assertTrue(injector.of(Kind.DISPATCH_ALL).isEmpty())
    }

    @Test
    fun anotherGestureDuringTheRegripGapEndsThePinch() {
        pincher.begin(center)
        ramp(to = 3.3f)
        injector.dispatch(planner.tap(IPoint(200, 200)), "edge tap")
        ramp(from = 3.3f, to = 4f, durationMs = 80)
        pincher.finish(4f)
        scheduler.advanceBy(500)
        // Nothing of the pinch's after the tap: no new grip, no lift, no rest.
        assertEquals("edge tap", injector.calls.last().label)
    }

    @Test
    fun startPointsAreWhereGripsTouchDown() {
        val points = pincher.startPoints(center)
        assertEquals(4, points.size)
        assertEquals(lo.toFloat(), distance(points[0], points[1]), 1f)
        assertEquals(2f * lo, distance(points[2], points[3]), 1f)
    }

    @Test
    fun everyGripTouchesDownAtACheckedStartPoint() {
        // A diagonal pinch near the bottom of a portrait screen with system bars, where a check of
        // the widest spacing alone would miss where zoom-out grips land.
        val portrait = GesturePlanner(14, 42).apply { bounds = Bounds(1848, 2960, 0, 53, 28, 84) }
        val cursor = IPoint(1500, 2550)
        for (target in listOf(0.25f, 4f)) {
            val fake = FakeInjector(scheduler::now)
            val pinch = LivePincher(fake, portrait, 302, scheduler, scheduler::now)
            val allowed = pinch.startPoints(cursor).toSet()
            pinch.begin(cursor)
            ramp(to = target, pincher = pinch)
            scheduler.advanceBy(100)
            val downs = fake.of(Kind.OPEN).flatMap { grip -> grip.paths.map { it.first() } }
            assertTrue(downs.isNotEmpty())
            assertTrue("$downs not in $allowed", allowed.containsAll(downs))
        }
    }
}
