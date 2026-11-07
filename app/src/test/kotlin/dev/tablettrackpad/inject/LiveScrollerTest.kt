package dev.tablettrackpad.inject

import dev.tablettrackpad.core.Bounds
import dev.tablettrackpad.core.GesturePlanner
import dev.tablettrackpad.core.IPoint
import dev.tablettrackpad.inject.FakeInjector.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveScrollerTest {
    private val scheduler = ManualScheduler()
    private val injector = FakeInjector(scheduler::now)
    private val planner = GesturePlanner(17, 50).apply { bounds = Bounds(2960, 1848) }
    private val scroller = LiveScroller(injector, planner, scheduler::now)

    private val anchor = IPoint(1000, 900)

    /** Scrolls down at [anchor] and lifts with a fling. */
    private fun scrollAndFling() {
        scroller.begin(anchor)
        scroller.add(0f, 40f)
        scheduler.advanceBy(10)
        scroller.add(0f, 20f)
        scheduler.advanceBy(10)
        scroller.finish(0f, 2000f)
    }

    @Test
    fun aReleaseReplacedByOurNextGestureIsNotReplayed() {
        scrollAndFling()
        injector.of(Kind.FINISH, "scroll end").single().complete(Outcome.REPLACED)
        assertTrue(injector.of(Kind.PLAY, "scroll end").isEmpty())
    }

    @Test
    fun aCancelledReleaseReplaysAtItsOwnAnchor() {
        scrollAndFling()
        val release = injector.of(Kind.FINISH, "scroll end").single()
        // The next scroll has begun, but has not injected anything yet.
        scroller.begin(IPoint(400, 300))
        release.complete(Outcome.CANCELLED)
        assertEquals(planner.scrollRelease(anchor, 0, 0, 0f, 2000f), injector.of(Kind.PLAY, "scroll end").single().plans)
    }

    @Test
    fun aCancelledStepIsOwedAgainFromANewPress() {
        scroller.begin(anchor)
        scroller.add(0f, 40f)
        scheduler.advanceBy(10)
        scroller.add(0f, 20f)
        val step = injector.of(Kind.EXTEND, "live step").single()
        step.complete(Outcome.CANCELLED)
        scheduler.advanceBy(10)
        scroller.add(0f, 5f)
        val reopened = injector.of(Kind.OPEN, "live scroll").last()
        assertEquals(2, injector.of(Kind.OPEN, "live scroll").size)
        // The cancelled 20 px and the new 5 px, plus the touch slop the new press pays again.
        val path = reopened.paths.single()
        assertEquals(25 + 17, path.last().y - path.first().y)
    }

    @Test
    fun aScrollTakenOverByAnotherGestureNeitherPressesAgainNorFlings() {
        scroller.begin(anchor)
        scroller.add(0f, 40f)
        scheduler.advanceBy(10)
        injector.dispatch(planner.tap(IPoint(200, 200)), "edge tap")
        scroller.add(0f, 20f)
        scheduler.advanceBy(10)
        scroller.add(0f, 20f)
        scroller.finish(0f, 2000f)
        assertEquals("edge tap", injector.calls.last().label)
    }

    @Test
    fun aStrokeThatWouldStartOnOurWindowLeavesTheScrollToTheRelease() {
        // Near the bottom, fitting the first stroke on screen moves its start up, onto a "window".
        val low = IPoint(1000, 1790)
        val blocked = LiveScroller(injector, planner, scheduler::now) { it.y >= 1790 }
        blocked.begin(low)
        blocked.add(0f, 100f)
        scheduler.advanceBy(10)
        blocked.add(0f, 20f)
        blocked.finish(0f, 0f)
        assertTrue(injector.of(Kind.OPEN).isEmpty())
        assertEquals(planner.scrollRelease(low, 0, 120, 0f, 0f), injector.of(Kind.PLAY, "scroll end").single().plans)
    }

    @Test
    fun stoppingAfterTheReleaseWasSentRulesOutItsReplay() {
        scrollAndFling()
        val release = injector.of(Kind.FINISH, "scroll end").single()
        scroller.stop()
        // A new scroll that has not injected anything yet must not bring the old one back.
        scroller.begin(IPoint(400, 300))
        release.complete(Outcome.CANCELLED)
        assertTrue(injector.of(Kind.PLAY, "scroll end").isEmpty())
    }

    @Test
    fun stoppingAScrollThatHasNotInjectedYetAlsoRulesOutTheLastRelease() {
        scrollAndFling()
        val release = injector.of(Kind.FINISH, "scroll end").single()
        scroller.begin(IPoint(400, 300))
        scroller.stop()
        release.complete(Outcome.CANCELLED)
        assertTrue(injector.of(Kind.PLAY, "scroll end").isEmpty())
    }

    @Test
    fun scrollingTowardTheEdgeFromItKeepsOneStroke() {
        val bottom = planner.swipeAnchor(IPoint(1000, 1847))
        scroller.begin(bottom)
        repeat(20) {
            scroller.add(0f, 10f)
            scheduler.advanceBy(10)
        }
        assertEquals(1, injector.of(Kind.OPEN).size)
        assertEquals(19, injector.of(Kind.EXTEND).size)
    }
}
