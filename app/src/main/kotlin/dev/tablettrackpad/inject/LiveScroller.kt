package dev.tablettrackpad.inject

import dev.tablettrackpad.core.GesturePlanner
import dev.tablettrackpad.core.IPoint
import dev.tablettrackpad.debug.DebugLog
import kotlin.math.roundToInt

/**
 * Scrolls while the fingers are still on the pad, by keeping one injected finger pressed at the
 * cursor and extending its stroke as the real fingers move. The app sees an ordinary drag: the
 * touch slop is paid once, and lifting with the real fingers' velocity gives its native fling.
 *
 * Steps are paced by [clock] rather than by completion callbacks, which can trail the injected
 * events by tens of milliseconds. If the system cancels the open stroke (or the injected finger
 * reaches the screen edge), the next step starts a new stroke at the cursor and the cancelled
 * distance is owed again. A new stroke must start where [canStartAt] allows, which keeps it off
 * our own windows; where it doesn't, the rest of the scroll plays on release instead. Once another
 * gesture takes over the injector, the scroll stops for good.
 */
class LiveScroller(
    private val injector: Injector,
    private val planner: GesturePlanner,
    private val clock: () -> Long,
    private val canStartAt: (IPoint) -> Boolean = { true },
) {
    private var anchor = IPoint(0, 0)
    private var owedX = 0f
    private var owedY = 0f
    private var active = false
    private val owners = Ownership.Issuer(injector)
    private var ownership = owners.next()

    /** Set once a new stroke would start on our own windows: the rest is owed until release. */
    private var deferred = false

    /** The pressed injected stroke and where its finger is now; null when none is open. */
    private var chain: Chain? = null
    private var strokeEnd = IPoint(0, 0)

    private var steps = 0
    private var restarts = 0
    private var cancelled = 0

    /** Uptime of the latest dispatch, to tell whether a cancelled pad touch followed one. */
    var lastDispatchMs = 0L
        private set

    fun begin(at: IPoint) {
        anchor = at
        owedX = 0f
        owedY = 0f
        active = true
        deferred = false
        ownership = owners.next()
        chain = null
        steps = 0
        restarts = 0
        cancelled = 0
        lastDispatchMs = 0L
    }

    fun add(dx: Float, dy: Float) {
        if (!active) return
        owedX += dx
        owedY += dy
        pump()
    }

    /**
     * Lifts the injected finger after playing what is still owed, flinging at ([velocityX],
     * [velocityY]) px/s if non-zero.
     */
    fun finish(velocityX: Float, velocityY: Float) {
        if (!active) return
        active = false
        DebugLog.i("Live scroll: $steps steps, $restarts restarts, $cancelled cancelled${if (deferred) ", rest on release" else ""}")
        val open = chain
        chain = null
        if (!ownership.held) {
            DebugLog.v("Live scroll was replaced by a newer gesture; dropping its release")
            return
        }
        val dx = owedX.roundToInt()
        val dy = owedY.roundToInt()
        // Built from this scroll's own values: by the time it runs, another scroll may have begun.
        val replay = planner.scrollRelease(anchor, dx, dy, velocityX, velocityY)
        val plan = open?.let { planner.releaseOpenStroke(strokeEnd, dx, dy, velocityX, velocityY) }
        if (open == null || plan == null) {
            // A new job, so an open stroke that cannot carry the release is cancelled, not flung.
            injector.play(replay, "scroll end")
            return
        }
        val owner = ownership
        injector.finishStroke(open, plan, "scroll end") { outcome ->
            // Replaced means a newer gesture took over; replaying would undo it.
            if (outcome == Outcome.CANCELLED && owner.held) {
                DebugLog.i("Live scroll release was cancelled; replaying it as a new drag")
                injector.play(replay, "scroll end")
            }
        }
    }

    /**
     * Abandons the scroll, lifting the injected finger without a fling. It also rules out the
     * replay of any release already sent, this scroll's or an earlier one's.
     */
    fun stop() {
        val owned = ownership.held
        owners.revokeAll()
        if (!active) return
        active = false
        val open = chain ?: return
        chain = null
        if (owned) injector.finishStroke(open, planner.settle(strokeEnd), "scroll abort")
    }

    private fun pump() {
        if (deferred) return
        if (!ownership.held) {
            // Another gesture took over the injector, and with it our stroke.
            chain = null
            active = false
            DebugLog.v("Live scroll was replaced by a newer gesture")
            return
        }
        val now = clock()
        if (now - lastDispatchMs < MIN_STEP_INTERVAL_MS) return
        val dx = owedX.toInt()
        val dy = owedY.toInt()
        if (dx == 0 && dy == 0) return
        // Spread the step over the time since the last one so the app's velocity tracker sees
        // the fingers' real speed.
        val durationMs = if (lastDispatchMs == 0L) 1L else (now - lastDispatchMs).coerceIn(1, MAX_STEP_MS)

        val open = chain
        val next = open?.let { planner.continueStep(strokeEnd, dx, dy) }
        if (open != null && next != null) {
            val from = strokeEnd
            owedX -= dx
            owedY -= dy
            strokeEnd = next
            chain = injector.extendStroke(open, listOf(from, next), durationMs) { outcome ->
                onStepResult(open.job, outcome, dx, dy)
            }
            if (chain == null) {
                // The step could not be built; the next one presses again, owing this one.
                owedX += dx
                owedY += dy
            }
        } else {
            // First step, or the injected finger reached the edge of the screen: press again at
            // the cursor. That starts a new job, which cancels the old stroke, so it does not fling.
            val step = planner.liveScrollStep(anchor, dx, dy) ?: return
            if (!canStartAt(step.from)) {
                // Fitting the stroke on screen moved its start onto one of our windows, which a
                // live stroke cannot make untouchable. Release the finger where it is and play
                // the rest on release, which can.
                DebugLog.i("Live scroll would start on our own window; the rest plays on release")
                deferred = true
                chain = null
                if (open != null) injector.finishStroke(open, planner.settle(strokeEnd), "scroll abort")
                return
            }
            if (open != null) restarts++
            owedX -= step.consumedX
            owedY -= step.consumedY
            strokeEnd = step.to
            var opened: Chain? = null
            opened = injector.openStroke(listOf(step.from, step.to), 1, "live scroll") { outcome ->
                opened?.let { onStepResult(it.job, outcome, step.consumedX, step.consumedY) }
            }
            chain = opened
            ownership.claim()
        }
        steps++
        lastDispatchMs = now
    }

    /** A step of the stroke of job [job] ended; only a system cancellation of the current one matters. */
    private fun onStepResult(job: Int, outcome: Outcome, dx: Int, dy: Int) {
        if (outcome != Outcome.CANCELLED || job != chain?.job) return
        cancelled++
        // The stroke is gone: the next step presses again at the cursor, owing this step again.
        chain = null
        if (active) {
            owedX += dx
            owedY += dy
        }
    }

    private companion object {
        /** Keeps bursts of batched input from flooding the injector. */
        const val MIN_STEP_INTERVAL_MS = 6L
        const val MAX_STEP_MS = 32L
    }
}
