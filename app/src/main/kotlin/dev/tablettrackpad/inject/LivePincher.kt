package dev.tablettrackpad.inject

import dev.tablettrackpad.core.Cancellable
import dev.tablettrackpad.core.GesturePlanner
import dev.tablettrackpad.core.IPoint
import dev.tablettrackpad.core.PinchPlacement
import dev.tablettrackpad.core.Scheduler
import dev.tablettrackpad.debug.DebugLog
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Pinches in real time with two injected fingers either side of the cursor that spread or close
 * as the real fingers do.
 *
 * Android ignores pinches narrower than its minimum scaling span, and wide pinches would leave
 * the app under the cursor, so the injected spacing stays within [GesturePlanner.pinchSpans] and
 * the room [GesturePlanner.pinchPlacement] finds around the cursor. When it runs out of room the
 * fingers spread to the end of the range and lift, and once that lift has played they grip again,
 * like re-gripping a real pinch. Zooming in grips at the narrow end. Zooming out grips at twice
 * that, the room the placement always has, rather than at the widest spacing, so a small zoomable
 * view still gets both fingers; it takes a few more grips. Each grip starts from the zoom delivered so
 * far, so it catches up whatever the last one could not fit. If the system cancels the strokes,
 * the rest of the zoom is played as separate pinches when the fingers lift. Once another gesture
 * takes over the injector, the pinch stops for good, whatever it still had scheduled.
 */
class LivePincher(
    private val injector: Injector,
    private val planner: GesturePlanner,
    private val minSpanPx: Int,
    private val scheduler: Scheduler,
    private val clock: () -> Long,
) {
    private var center = IPoint(0, 0)
    private var placement = PinchPlacement(IPoint(0, 0), 1f, 0f, 0)
    private var active = false
    private var session = 0
    private var broken = false
    private val owners = Ownership.Issuer(injector)
    private var ownership = owners.next()

    /** The current grip's fingers; null before the first grip and while re-gripping. */
    private var chain: Chain? = null

    private var lo = 0
    private var hi = 0

    /** Where a zoom-out grip starts. */
    private var outStart = 0

    /** Injected spacing now, and when the current grip started. */
    private var span = 0
    private var gripSpan = 0

    /** Zoom delivered by finished grips; the current grip started there. */
    private var achievedBefore = 1f
    private var lastScale = 1f
    private var grips = 0

    /** The next grip, waiting for the last one's lift to play out. */
    private var regrip: Cancellable? = null

    /** A catch-up step after a grip opens, in case no new finger movement asks for one. */
    private var catchUp: Cancellable? = null

    /** The rest of a finished pinch, waiting for its last lift to play out. */
    private var rest: Cancellable? = null

    /** Uptime of the latest dispatch, to tell whether a cancelled pad touch followed one. */
    var lastDispatchMs = 0L
        private set

    /**
     * Where the injected fingers touch down for a pinch around [at]: at the spacing a grip starts
     * at, zooming in or out, as the first grip and the re-grips use them. Where they move after
     * that does not matter, since a touch stays with the window it went down on. A live pinch must
     * not start on our own windows, so the caller checks these.
     */
    fun startPoints(at: IPoint): List<IPoint> {
        val layout = layout(at) ?: return emptyList()
        return listOf(layout.lo, layout.outStart).flatMap { planner.pinchFingers(layout.placement, it).toList() }
    }

    fun begin(at: IPoint) {
        clearTasks()
        val layout = layout(at)
        session++
        active = layout != null
        broken = false
        ownership = owners.next()
        chain = null
        center = at
        if (layout != null) placement = layout.placement
        lo = layout?.lo ?: 0
        hi = layout?.hi ?: 0
        outStart = layout?.outStart ?: 0
        span = 0
        gripSpan = 0
        achievedBefore = 1f
        lastScale = 1f
        grips = 0
        lastDispatchMs = 0L
    }

    fun update(scale: Float) {
        if (!active) return
        lastScale = scale
        if (broken || regrip != null) return
        val now = clock()
        if (now - lastDispatchMs < MIN_STEP_INTERVAL_MS) return
        step(now)
    }

    /** The fingers lifted at [scale]: delivers what the current grip can fit, then the rest as separate pinches. */
    fun finish(scale: Float) {
        if (!active) return
        active = false
        lastScale = scale
        // A re-grip still waiting means the last lift may still be playing.
        val lifting = regrip != null
        clearTasks()
        DebugLog.i("Live pinch: x%.2f in %d grips".format(lastScale, grips))
        val open = chain
        chain = null
        if (!ownership.held) {
            DebugLog.v("Live pinch was replaced by a newer gesture; dropping the rest")
            return
        }
        if (broken) {
            val current = if (gripSpan > 0) span.toFloat() / gripSpan else 1f
            playRest(lastScale / (achievedBefore * current), afterLift = false)
            return
        }
        // Not throttled: this is the last chance to deliver the final movement.
        if (open != null) lift(open, targetSpan().coerceIn(lo, hi))
        playRest(lastScale / achievedBefore, afterLift = open != null || lifting)
    }

    /** Abandons the pinch where the fingers are, including a rest still waiting to play. */
    fun cancel() {
        val owned = ownership.held
        owners.revokeAll()
        clearTasks()
        if (!active) return
        active = false
        val open = chain ?: return
        chain = null
        if (!broken && owned) lift(open, span)
    }

    private fun step(now: Long) {
        if (!ownership.held) return replaced()
        val open = chain ?: return grip(zoomingIn = lastScale >= 1f, now = now)
        val target = targetSpan()
        when {
            target > hi -> regripFrom(open, toward = hi, zoomingIn = true, now = now)
            target < lo -> regripFrom(open, toward = lo, zoomingIn = false, now = now)
            target != span -> {
                val (a0, b0) = fingers(span)
                val (a1, b1) = fingers(target)
                val durationMs = (now - lastDispatchMs).coerceIn(1, MAX_STEP_MS)
                span = target
                lastDispatchMs = now
                val mine = session
                chain = injector.extendStrokes(open, listOf(listOf(a0, a1), listOf(b0, b1)), durationMs, true, "live pinch") {
                    onResult(mine, open.job, it)
                }
            }
        }
    }

    /** The spacing that delivers [lastScale] in the current grip. */
    private fun targetSpan() = (gripSpan * lastScale / achievedBefore).roundToInt()

    /**
     * Spreads (or closes) to the end of the range and lifts, then grips again once the lift has
     * played: a new gesture dispatched sooner would cancel the lift.
     */
    private fun regripFrom(open: Chain, toward: Int, zoomingIn: Boolean, now: Long) {
        lift(open, toward)
        chain = null
        lastDispatchMs = now
        regrip = scheduler.schedule(RELEASE_MS + REGRIP_GAP_MS) {
            regrip = null
            when {
                !active || broken -> Unit
                // Something else was injected meanwhile; gripping again would cut it short.
                !ownership.held -> replaced()
                else -> grip(zoomingIn, clock())
            }
        }
    }

    private fun grip(zoomingIn: Boolean, now: Long) {
        span = if (zoomingIn) lo else outStart
        gripSpan = span
        grips++
        lastDispatchMs = now
        val (a, b) = fingers(span)
        val mine = session
        var opened: Chain? = null
        opened = injector.openStrokes(listOf(listOf(a), listOf(b)), 1, "live pinch") { outcome ->
            opened?.let { onResult(mine, it.job, outcome) }
        }
        chain = opened
        ownership.claim()
        // The first grip starts from where the real pinch started, so the spread it took to
        // recognize the pinch still zooms; later grips start short of the scale asked for. Catch
        // up straight away rather than waiting for the fingers to move again.
        catchUp?.cancel()
        catchUp = scheduler.schedule(MIN_STEP_INTERVAL_MS) {
            catchUp = null
            if (active && !broken && chain != null && regrip == null) step(clock())
        }
    }

    /** Moves the fingers to [toSpan] while lifting them, and counts that grip's zoom as delivered. */
    private fun lift(open: Chain, toSpan: Int) {
        val (a0, b0) = fingers(span)
        val (a1, b1) = fingers(toSpan)
        achievedBefore *= toSpan.toFloat() / gripSpan.coerceAtLeast(1)
        span = toSpan
        gripSpan = toSpan
        injector.extendStrokes(open, listOf(pathOf(a0, a1), pathOf(b0, b1)), RELEASE_MS, false, "pinch release") {}
    }

    /** Plays a zoom of [missing] as separate pinches, after the last lift if there is one. */
    private fun playRest(missing: Float, afterLift: Boolean) {
        val pinches = planner.pinch(center, missing, minSpanPx)
        if (pinches.isEmpty()) return
        DebugLog.i("Live pinch: playing the remaining x%.2f after release".format(missing))
        if (afterLift) {
            val owner = ownership
            rest = scheduler.schedule(RELEASE_MS + REGRIP_GAP_MS) {
                rest = null
                if (owner.held) {
                    injector.dispatchAll(pinches, "pinch rest")
                } else {
                    DebugLog.v("Live pinch was replaced before its rest played")
                }
            }
        } else {
            injector.dispatchAll(pinches, "pinch replay")
        }
    }

    /** Another gesture took over the injector, and with it our fingers: the pinch is over. */
    private fun replaced() {
        DebugLog.v("Live pinch was replaced by a newer gesture")
        active = false
        chain = null
        clearTasks()
    }

    private fun fingers(spacing: Int) = planner.pinchFingers(placement, spacing)

    /** Where a pinch around one point goes, its spacing range, and where zoom-out grips start. */
    private class Layout(val placement: PinchPlacement, val lo: Int, val hi: Int, val outStart: Int)

    private fun layout(at: IPoint): Layout? {
        val (lo, max) = planner.pinchSpans(minSpanPx) ?: return null
        val placement = planner.pinchPlacement(at, lo, max)
        val outStart = min(placement.reach, (lo * GesturePlanner.MIN_PINCH_RANGE).roundToInt())
        return Layout(placement, lo, placement.reach, outStart)
    }

    private fun pathOf(from: IPoint, to: IPoint) = if (from == to) listOf(from) else listOf(from, to)

    /** A system cancellation of the current grip; other grips' and replaced results don't count. */
    private fun onResult(mine: Int, job: Int, outcome: Outcome) {
        if (outcome != Outcome.CANCELLED || mine != session || broken || job != chain?.job) return
        broken = true
        DebugLog.i("Live pinch strokes were cancelled by the system; the rest plays on release")
    }

    private fun clearTasks() {
        regrip?.cancel()
        regrip = null
        catchUp?.cancel()
        catchUp = null
        rest?.cancel()
        rest = null
    }

    private companion object {
        const val MIN_STEP_INTERVAL_MS = 6L
        const val MAX_STEP_MS = 32L
        const val RELEASE_MS = 16L

        /** Margin after a lift's last event before the next gesture, which would cancel it. */
        const val REGRIP_GAP_MS = 24L
    }
}
