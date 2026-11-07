package dev.tablettrackpad.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class GesturePlannerTest {
    private val slop = 17
    private val margin = 50
    private val planner = GesturePlanner(slop, margin).apply { bounds = Bounds(2960, 1848) }

    private fun inside(p: IPoint) =
        p.x in margin..(2960 - 1 - margin) && p.y in margin..(1848 - 1 - margin)

    @Test
    fun liveStepAddsTheSlopOnTheMovingAxisOnly() {
        val step = planner.liveScrollStep(IPoint(1000, 900), 0, 12)!!
        assertEquals(IPoint(1000, 900), step.from)
        assertEquals(IPoint(1000, 900 + 12 + slop), step.to)
        assertEquals(12, step.consumedY)
        val up = planner.liveScrollStep(IPoint(1000, 900), 0, -5)!!
        assertEquals(900 - 5 - slop, up.to.y)
    }

    @Test
    fun liveStepNearTheEdgeShiftsTheAnchorInward() {
        val step = planner.liveScrollStep(IPoint(1000, 1840), 0, 30)!!
        assertTrue(inside(step.from))
        assertTrue(inside(step.to))
        assertEquals(30 + slop, step.to.y - step.from.y)
    }

    @Test
    fun liveStepNearTheEdgeLeavesRoomForLaterSteps() {
        val headroom = slop * GesturePlanner.LIVE_HEADROOM_SLOPS
        val step = planner.liveScrollStep(IPoint(1000, 1840), 0, 10)!!
        assertEquals(1848 - 1 - margin - headroom, step.to.y)
        // Only along the way it moves, and only as far as the edge requires.
        assertEquals(1000, step.from.x)
        assertEquals(IPoint(1000, 900), planner.liveScrollStep(IPoint(1000, 900), 0, 10)!!.from)
    }

    @Test
    fun noLiveStepForZeroMovement() {
        assertNull(planner.liveScrollStep(IPoint(10, 10), 0, 0))
    }

    @Test
    fun releaseScrollSettlesWithoutFling() {
        val plans = planner.scrollRelease(IPoint(1000, 900), 0, 300, 0f, 0f)
        assertEquals(1, plans.size)
        val segments = plans[0].segments
        assertEquals(2, segments.size)
        assertEquals(300 + slop, segments[0].end.y - segments[0].start.y)
        // Ends resting in place (with a one-pixel wiggle) so the velocity tracker sees it stop.
        val settle = segments[1]
        assertEquals(settle.start, settle.end)
        assertTrue(settle.points.size > 1)
        assertEquals(GesturePlanner.SETTLE_MS, settle.durationMs)
    }

    @Test
    fun releaseScrollEndsWithAFlingAtTheRequestedSpeed() {
        val plans = planner.scrollRelease(IPoint(1000, 900), 0, 100, 0f, 3000f)
        val fling = plans.single().segments.last()
        val distance = fling.end.y - fling.start.y
        val speed = distance * 1000f / fling.durationMs
        assertEquals(3000f, speed, 20f)
    }

    @Test
    fun flingWithNoDistanceStillCrossesTheSlop() {
        val plans = planner.scrollRelease(IPoint(1000, 900), 0, 0, 0f, -2000f)
        val first = plans.single().segments.first()
        assertEquals(-(1 + slop), first.end.y - first.start.y)
    }

    @Test
    fun longScrollsSplitIntoDragsThatFitOnScreen() {
        val plans = planner.scrollRelease(IPoint(1000, 900), 0, 5000, 0f, 0f)
        assertTrue(plans.size > 1)
        var total = 0
        for (plan in plans) {
            for (s in plan.segments) s.points.forEach { assertTrue("$it off screen", inside(it)) }
            val move = plan.segments.first()
            total += abs(move.end.y - move.start.y) - slop
        }
        assertEquals(5000, total)
    }

    @Test
    fun dragKeepsEndpointsAndHolds() {
        val path = (0..50).map { IPoint(500 + it, 600) }
        val plan = planner.drag(path, 650)!!
        assertEquals(IPoint(500, 600), plan.start)
        assertEquals(650L, plan.segments[0].durationMs)
        assertEquals(IPoint(550, 600), plan.segments[1].end)
        assertEquals(IPoint(550, 600), plan.segments[2].start)
    }

    @Test
    fun dragWithoutMovementIsDropped() {
        assertNull(planner.drag(listOf(IPoint(5, 5), IPoint(5, 5)), 0))
    }

    private fun spans(gesture: List<TimedStroke>): Pair<Int, Int> {
        val (a, b) = gesture
        return (b.points.first().x - a.points.first().x) to (b.points.last().x - a.points.last().x)
    }

    @Test
    fun pinchSpansStayAboveTheMinimum() {
        val minSpan = 270
        val zoomOut = planner.pinch(IPoint(1480, 900), 0.6f, minSpan)
        val (outStart, outEnd) = spans(zoomOut.single())
        assertTrue(outEnd >= minSpan)
        assertEquals(0.6f, outEnd.toFloat() / outStart, 0.02f)
        val (inStart, inEnd) = spans(planner.pinch(IPoint(1480, 900), 2f, minSpan).single())
        assertTrue(inStart >= minSpan)
        assertEquals(2f, inEnd.toFloat() / inStart, 0.02f)
    }

    @Test
    fun largeZoomsSplitIntoSeveralPinchesCenteredOnTheCursor() {
        val gestures = planner.pinch(IPoint(1000, 900), 4f, 270)
        assertEquals(2, gestures.size)
        var total = 1f
        for (g in gestures) {
            val (start, end) = spans(g)
            total *= end.toFloat() / start
            val (a, b) = g
            assertEquals(1000, (a.points.first().x + b.points.first().x) / 2)
        }
        assertEquals(4f, total, 0.05f)
    }

    @Test
    fun pinchNearTheEdgeMovesTheCenterInward() {
        val gestures = planner.pinch(IPoint(5, 900), 2f, 270)
        gestures.flatten().forEach { s -> s.points.forEach { assertTrue("$it off screen", inside(it)) } }
    }

    @Test
    fun pinchNearASideEdgeTurnsVerticalAroundTheCursor() {
        val (a, b) = planner.pinch(IPoint(300, 900), 2f, 270).single()
        for (p in a.points + b.points) assertEquals(300, p.x)
        assertEquals(900, (a.points.first().y + b.points.first().y) / 2)
        assertEquals(2f, (b.points.last().y - a.points.last().y).toFloat() / (b.points.first().y - a.points.first().y), 0.02f)
    }

    @Test
    fun pinchNearACornerMovesTheCenterInward() {
        val (lo, hi) = planner.pinchSpans(270)!!
        val placement = planner.pinchPlacement(IPoint(60, 60), lo, hi)
        assertTrue(placement.reach >= 2 * lo)
        val (a, b) = planner.pinchFingers(placement, placement.reach)
        assertTrue(inside(a) && inside(b))
    }

    @Test
    fun tinyPinchIsIgnored() {
        assertTrue(planner.pinch(IPoint(1000, 900), 1.01f, 270).isEmpty())
        assertEquals(1, planner.pinch(IPoint(1000, 900), 1.2f, 270).size)
    }

    @Test
    fun tapsAreClampedToTheScreen() {
        val stroke = planner.tap(IPoint(-10, 5000)).single()
        assertEquals(IPoint(0, 1847), stroke.points.single())
    }

    @Test
    fun swipesAndPinchesStartClearOfVisibleSystemBars() {
        val portrait = GesturePlanner(14, 42).apply {
            bounds = Bounds(1848, 2960, insetTop = 53, insetBottom = 84)
        }
        val lowest = 2960 - 84 - 1 - 42
        val (lo, hi) = portrait.pinchSpans(310)!!
        val place = portrait.pinchPlacement(IPoint(80, 2780), lo, hi)
        for (span in listOf(lo, place.reach)) {
            val (a, b) = portrait.pinchFingers(place, span)
            assertTrue("$a $b", a.y in (53 + 42)..lowest && b.y in (53 + 42)..lowest)
        }
        assertEquals(lowest, portrait.swipeAnchor(IPoint(900, 2950)).y)
        assertEquals(53 + 42, portrait.swipeAnchor(IPoint(900, 10)).y)
        // Taps still reach the bars, where the navigation buttons are.
        assertEquals(IPoint(900, 2950), portrait.tap(IPoint(900, 2950)).single().points.single())
    }

    @Test
    fun diagonalPinchesAtFullReachStayInsideTheMargins() {
        val portrait = GesturePlanner(14, 42).apply {
            bounds = Bounds(1848, 2960, insetTop = 53, insetRight = 28, insetBottom = 84)
        }
        val (lo, hi) = portrait.pinchSpans(310)!!
        val place = portrait.pinchPlacement(IPoint(1480, 428), lo, hi)
        assertTrue(place.dirX > 0f && place.dirY > 0f)
        val (a, b) = portrait.pinchFingers(place, place.reach)
        for (p in listOf(a, b)) {
            assertTrue("$p", p.x in 42..(1848 - 28 - 1 - 42) && p.y in (53 + 42)..(2960 - 84 - 1 - 42))
        }
    }

    @Test
    fun liveStepHeadroomNeverPushesThePressOutOfTheSwipeArea() {
        // A short landscape screen: the headroom wanted for a long step up does not fit.
        val landscape = GesturePlanner(24, 72).apply { bounds = Bounds(2400, 1080, 0, 72, 0, 144) }
        val step = landscape.liveScrollStep(IPoint(1200, 800), 0, -500)!!
        val bottom = 1080 - 144 - 1 - 72
        assertTrue("${step.from}", step.from.y <= bottom)
        assertTrue("${step.to}", step.to.y >= 72 + 72)
    }

    /** Swipe area edges for [b], computed the way the planner documents them. */
    private fun area(b: Bounds, edgeMargin: Int): IntArray {
        val w = b.width - b.insetLeft - b.insetRight
        val h = b.height - b.insetTop - b.insetBottom
        val m = minOf(edgeMargin, minOf(w, h) / 4)
        return intArrayOf(b.insetLeft + m, b.insetTop + m, b.insetLeft + w - 1 - m, b.insetTop + h - 1 - m)
    }

    private fun IntArray.holds(p: IPoint) = p.x in this[0]..this[2] && p.y in this[1]..this[3]

    @Test
    fun liveStepsAndPinchesStayInsideTheSwipeAreaOnAnyScreen() {
        val screens = listOf(
            Bounds(2960, 1848),
            Bounds(1848, 2960, 0, 53, 28, 84),
            Bounds(2960, 1848, 28, 53, 0, 84),
            Bounds(2400, 1080, 0, 72, 0, 144),
            Bounds(1080, 2400, 0, 96, 0, 63),
            Bounds(1280, 800, 0, 48, 0, 96),
        )
        val random = java.util.Random(7)
        for (screen in screens) {
            for ((slop, margin) in listOf(14 to 42, 24 to 72)) {
                val p = GesturePlanner(slop, margin).apply { bounds = screen }
                val a = area(screen, margin)
                repeat(500) {
                    val at = IPoint(random.nextInt(screen.width), random.nextInt(screen.height))
                    val dx = if (random.nextInt(3) == 0) 0 else random.nextInt(3001) - 1500
                    val dy = if (dx != 0 && random.nextBoolean()) 0 else random.nextInt(3001) - 1500
                    p.liveScrollStep(at, dx, dy)?.let { step ->
                        assertTrue("$screen $at ($dx, $dy): ${step.from}", a.holds(step.from))
                        assertTrue("$screen $at ($dx, $dy): ${step.to}", a.holds(step.to))
                    }
                    val (lo, hi) = p.pinchSpans(310) ?: return@repeat
                    val place = p.pinchPlacement(at, lo, hi)
                    for (span in listOf(lo, minOf(place.reach, 2 * lo), place.reach)) {
                        val (f1, f2) = p.pinchFingers(place, span)
                        assertTrue("$screen $at pinch $span: $f1 $f2", a.holds(f1) && a.holds(f2))
                    }
                }
            }
        }
    }
}
