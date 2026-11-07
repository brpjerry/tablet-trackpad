package dev.tablettrackpad.inject

import dev.tablettrackpad.core.Bounds
import dev.tablettrackpad.core.GesturePlanner
import dev.tablettrackpad.core.IPoint
import dev.tablettrackpad.inject.FakeInjector.Kind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveDraggerTest {
    private val scheduler = ManualScheduler()
    private val injector = FakeInjector(scheduler::now)
    private val planner = GesturePlanner(17, 50).apply { bounds = Bounds(2960, 1848) }
    private val dragger = LiveDragger(injector, planner, scheduler, scheduler::now)

    private val start = IPoint(500, 500)
    private val end = IPoint(560, 500)

    /** A drag from [start] to [end] without a hold, lifted at once. */
    private fun dragAndLift() {
        dragger.begin(start, holdMs = 0)
        scheduler.advanceBy(10)
        dragger.moveTo(end)
        scheduler.advanceBy(10)
        dragger.finish()
    }

    private fun replays() = injector.of(Kind.PLAY, "drag replay") + injector.of(Kind.DISPATCH, "press replay")

    @Test
    fun aReleaseReplacedByTheNextDragIsNotReplayed() {
        dragAndLift()
        val release = injector.of(Kind.FINISH, "drag end").single()
        dragger.begin(IPoint(900, 900), holdMs = 0)
        release.complete(Outcome.REPLACED)
        assertEquals(emptyList<FakeInjector.Call>(), replays())
    }

    @Test
    fun aReleaseCancelledByTheSystemReplaysTheDrag() {
        dragAndLift()
        injector.of(Kind.FINISH, "drag end").single().complete(Outcome.CANCELLED)
        val plan = injector.of(Kind.PLAY, "drag replay").single().plans.single()
        assertEquals(start, plan.start)
        assertEquals(end, plan.segments.last().start)
    }

    @Test
    fun cancellingWhileTheReleaseWaitsForTheHoldDropsTheRelease() {
        dragger.begin(start, holdMs = 500)
        scheduler.advanceBy(100)
        dragger.moveTo(end)
        scheduler.advanceBy(100)
        // The hold has 300 ms left, so the release waits for it.
        dragger.finish()
        scheduler.advanceBy(100)
        dragger.cancel()
        scheduler.advanceBy(1000)
        assertTrue(injector.of(Kind.FINISH, "drag end").isEmpty())
        assertEquals(1, injector.of(Kind.FINISH, "drag abort").size)
        assertEquals(emptyList<FakeInjector.Call>(), replays())
    }

    @Test
    fun aReleaseThatWaitedForTheHoldEndsWhereTheFingerLifted() {
        dragger.begin(start, holdMs = 500)
        scheduler.advanceBy(100)
        dragger.moveTo(end)
        dragger.finish()
        assertTrue(injector.of(Kind.FINISH, "drag end").isEmpty())
        scheduler.advanceBy(500)
        val plan = injector.of(Kind.FINISH, "drag end").single().plans.single()
        assertEquals(start, plan.start)
        assertEquals(end, plan.segments.first().end)
    }

    @Test
    fun aDragTakenOverByAnotherGestureNeitherContinuesNorReplays() {
        dragger.begin(start, holdMs = 0)
        scheduler.advanceBy(10)
        dragger.moveTo(end)
        injector.dispatch(planner.tap(IPoint(900, 900)), "edge tap")
        scheduler.advanceBy(10)
        dragger.moveTo(IPoint(600, 500))
        scheduler.advanceBy(10)
        dragger.finish()
        scheduler.advanceBy(1000)
        assertEquals("edge tap", injector.calls.last().label)
        assertEquals(emptyList<FakeInjector.Call>(), replays())
    }

    @Test
    fun aDragTakenOverJustBeforeTheLiftIsNotReleasedOrReplayed() {
        dragger.begin(start, holdMs = 0)
        scheduler.advanceBy(10)
        dragger.moveTo(end)
        scheduler.advanceBy(10)
        injector.dispatch(planner.tap(IPoint(900, 900)), "edge tap")
        dragger.finish()
        scheduler.advanceBy(1000)
        assertEquals("edge tap", injector.calls.last().label)
    }

    @Test
    fun aCancelledLongPressReplaysWhenTheFingerLiftsNotAfterTheHold() {
        dragger.begin(start, holdMs = 650)
        scheduler.advanceBy(50)
        injector.of(Kind.OPEN, "drag press").single().complete(Outcome.CANCELLED)
        scheduler.advanceBy(50)
        dragger.moveTo(end)
        dragger.finish()
        assertEquals(1, injector.of(Kind.PLAY, "drag replay").size)
        // A newer gesture before the hold would have ended is not followed by anything of the drag's.
        scheduler.advanceBy(150)
        injector.dispatch(planner.tap(IPoint(900, 900)), "click")
        scheduler.advanceBy(1000)
        assertEquals("click", injector.calls.last().label)
    }

    @Test
    fun cancellingAfterTheReleaseWasSentRulesOutItsReplay() {
        dragAndLift()
        val release = injector.of(Kind.FINISH, "drag end").single()
        dragger.cancel()
        release.complete(Outcome.CANCELLED)
        assertEquals(emptyList<FakeInjector.Call>(), replays())
    }

    @Test
    fun aLaterDragDoesNotRevokeOrReviveAnEarlierRelease() {
        dragAndLift()
        val first = injector.of(Kind.FINISH, "drag end").single()
        dragger.cancel()
        dragger.begin(IPoint(900, 900), holdMs = 0)
        first.complete(Outcome.CANCELLED)
        assertEquals(emptyList<FakeInjector.Call>(), replays())
    }
}
