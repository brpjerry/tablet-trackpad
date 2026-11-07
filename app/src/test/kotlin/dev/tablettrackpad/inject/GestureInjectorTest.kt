package dev.tablettrackpad.inject

import android.accessibilityservice.AccessibilityService.GestureResultCallback
import android.accessibilityservice.GestureDescription
import android.os.Handler
import android.os.Looper
import dev.tablettrackpad.core.IPoint
import dev.tablettrackpad.core.Segment
import dev.tablettrackpad.core.StrokePlan
import dev.tablettrackpad.core.TimedStroke
import dev.tablettrackpad.overlay.OverlayManager.Window
import dev.tablettrackpad.overlay.PassthroughWindows
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.Duration

/** The real injector, with Android's own stroke and gesture classes; only dispatching is recorded. */
@RunWith(RobolectricTestRunner::class)
class GestureInjectorTest {
    private class Sent(val gesture: GestureDescription, private val callback: GestureResultCallback) {
        fun complete() = callback.onCompleted(gesture)
        fun cancel() = callback.onCancelled(gesture)
    }

    /** One window, the trackpad, covering x and y from 1000 to 1999. */
    private class Windows : PassthroughWindows {
        var held = 0

        override fun touchableWindowsAt(x: Int, y: Int) =
            if (x in 1000..1999 && y in 1000..1999) listOf(Window.TRACKPAD) else emptyList()

        override fun acquirePassthrough(window: Window) {
            held++
        }

        override fun releasePassthrough(window: Window) {
            held--
        }
    }

    private val sent = mutableListOf<Sent>()
    private var accepting = true
    private val windows = Windows()
    private val injector = GestureInjector(
        { gesture, callback -> accepting.also { if (it) sent += Sent(gesture, callback) } },
        windows,
        Handler(Looper.getMainLooper()),
    )

    private fun idle(ms: Long = 0) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))

    private fun tap(x: Int, y: Int) = listOf(TimedStroke(listOf(IPoint(x, y)), 0, 16))

    private fun swipe(vararg ys: Int) = listOf(ys.map { IPoint(500, it) })

    /** Collects the outcomes reported to it. */
    private class Outcomes : (Outcome) -> Unit {
        val all = mutableListOf<Outcome>()

        override fun invoke(outcome: Outcome) {
            all += outcome
        }
    }

    @Test
    fun aGestureThatPlaysOutIsDone() {
        val outcomes = Outcomes()
        injector.dispatch(tap(100, 100), "tap", outcomes)
        sent.single().complete()
        assertEquals(listOf(Outcome.DONE), outcomes.all)
    }

    @Test
    fun aCancellationIsTheSystemsUnlessANewerJobStarted() {
        val first = Outcomes()
        val second = Outcomes()
        injector.dispatch(tap(100, 100), "first", first)
        injector.dispatch(tap(200, 200), "second", second)
        // Android cancels the first because the second was dispatched: that was ours.
        sent[0].cancel()
        sent[1].cancel()
        assertEquals(listOf(Outcome.REPLACED), first.all)
        assertEquals(listOf(Outcome.CANCELLED), second.all)
    }

    @Test
    fun continuingAChainOfAnOlderJobIsRefused() {
        val chain = injector.openStrokes(swipe(500), 1, "press") {}!!
        injector.dispatch(tap(100, 100), "tap")
        val outcomes = Outcomes()
        assertNull(injector.extendStrokes(chain, swipe(500, 520), 16, true, "step", outcomes))
        assertEquals(2, sent.size)
        assertTrue(outcomes.all.isEmpty())
        idle()
        assertEquals(listOf(Outcome.REPLACED), outcomes.all)
    }

    @Test
    fun continuedStrokesStayInTheirJobAndLiftWhenAsked() {
        val chain = injector.openStrokes(swipe(500, 510), 1, "press") {}!!
        val next = injector.extendStrokes(chain, swipe(510, 530), 16, true, "step") {}!!
        injector.extendStrokes(next, swipe(530, 540), 16, false, "lift") {}
        assertEquals(chain.job, next.job)
        assertEquals(chain.job, injector.job)
        assertEquals(listOf(true, true, false), sent.map { it.gesture.getStroke(0).willContinue() })
    }

    @Test
    fun aStrokeAndroidRejectsFailsLikeACancellation() {
        // A lifted stroke cannot be continued; Android's StrokeDescription throws.
        val chain = injector.openStrokes(swipe(500), 1, "press") {}!!
        val lifted = injector.extendStrokes(chain, swipe(500, 520), 16, false, "lift") {}!!
        val outcomes = Outcomes()
        assertNull(injector.extendStrokes(lifted, swipe(520, 540), 16, true, "step", outcomes))
        idle()
        // Off-screen points are rejected as well.
        injector.dispatch(tap(-5, 100), "off screen", outcomes)
        idle()
        assertEquals(2, sent.size)
        assertEquals(listOf(Outcome.CANCELLED, Outcome.CANCELLED), outcomes.all)
    }

    @Test
    fun aRejectionReportedAfterANewerJobStartedIsReplaced() {
        val chain = injector.openStrokes(swipe(500), 1, "press") {}!!
        val lifted = injector.extendStrokes(chain, swipe(500, 520), 16, false, "lift") {}!!
        val outcomes = Outcomes()
        injector.extendStrokes(lifted, swipe(520, 540), 16, true, "step", outcomes)
        injector.dispatch(tap(100, 100), "newer")
        idle()
        assertEquals(listOf(Outcome.REPLACED), outcomes.all)
    }

    @Test
    fun aRefusedDispatchIsReportedAfterTheCallReturns() {
        accepting = false
        val outcomes = Outcomes()
        injector.dispatch(tap(100, 100), "tap", outcomes)
        assertTrue(outcomes.all.isEmpty())
        idle()
        assertEquals(listOf(Outcome.CANCELLED), outcomes.all)
    }

    @Test
    fun dispatchAllPlaysInTurnAndStopsOnceReplaced() {
        injector.dispatchAll(listOf(tap(100, 100), tap(200, 200), tap(300, 300)), "taps")
        assertEquals(1, sent.size)
        sent[0].complete()
        assertEquals(2, sent.size)
        injector.dispatch(tap(400, 400), "newer")
        sent[1].complete()
        // The third tap never plays: only the newer one was sent after the second.
        assertEquals(3, sent.size)
    }

    @Test
    fun aPlanPlaysItsSegmentsInTurnAsOneStroke() {
        val plan = StrokePlan(
            listOf(
                Segment(listOf(IPoint(500, 500)), 40),
                Segment(listOf(IPoint(500, 500), IPoint(500, 600)), 100),
                Segment(listOf(IPoint(500, 600), IPoint(501, 601), IPoint(500, 600)), 150),
            ),
        )
        val outcomes = Outcomes()
        injector.play(listOf(plan), "drag", outcomes)
        repeat(3) {
            assertEquals(it + 1, sent.size)
            sent.last().complete()
        }
        assertEquals(listOf(true, true, false), sent.map { it.gesture.getStroke(0).willContinue() })
        assertEquals(listOf(Outcome.DONE), outcomes.all)
    }

    @Test
    fun cancellingQueuedWorkStopsAPlanBetweenSegments() {
        val plan = StrokePlan(
            listOf(
                Segment(listOf(IPoint(500, 500)), 40),
                Segment(listOf(IPoint(500, 500), IPoint(500, 600)), 100),
            ),
        )
        val outcomes = Outcomes()
        injector.play(listOf(plan), "drag", outcomes)
        injector.cancelQueued()
        sent.single().complete()
        assertEquals(1, sent.size)
        assertEquals(listOf(Outcome.REPLACED), outcomes.all)
    }

    @Test
    fun aGestureStartingOnOurWindowWaitsForItToLetTouchesThrough() {
        val outcomes = Outcomes()
        injector.dispatch(tap(1500, 1500), "tap", outcomes)
        assertEquals(1, windows.held)
        assertTrue(sent.isEmpty())
        idle(80)
        sent.single().complete()
        assertEquals(0, windows.held)
        assertEquals(listOf(Outcome.DONE), outcomes.all)
    }

    @Test
    fun aGestureReplacedWhileWaitingForPassthroughIsNeverSent() {
        val outcomes = Outcomes()
        injector.dispatch(tap(1500, 1500), "tap", outcomes)
        injector.dispatch(tap(100, 100), "newer")
        idle(200)
        assertEquals(1, sent.size)
        assertEquals(0, windows.held)
        assertEquals(listOf(Outcome.REPLACED), outcomes.all)
        assertFalse(sent.single().gesture.getStroke(0).willContinue())
    }
}
