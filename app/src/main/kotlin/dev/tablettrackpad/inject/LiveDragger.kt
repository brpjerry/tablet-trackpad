package dev.tablettrackpad.inject

import dev.tablettrackpad.core.Cancellable
import dev.tablettrackpad.core.GesturePlanner
import dev.tablettrackpad.core.IPoint
import dev.tablettrackpad.core.Scheduler
import dev.tablettrackpad.core.Segment
import dev.tablettrackpad.core.StrokePlan
import dev.tablettrackpad.debug.DebugLog
import kotlin.math.hypot

/**
 * Drags in real time: presses at the cursor when a tap-and-drag starts and moves the injected
 * finger along with the cursor, so sliders and drag-and-drop follow the pad directly.
 *
 * The path is recorded as well. If the system cancels the injected stroke, the whole drag is
 * replayed from that path when the finger lifts. Once another gesture takes over the injector,
 * the drag stops for good, replay included.
 */
class LiveDragger(
    private val injector: Injector,
    private val planner: GesturePlanner,
    private val scheduler: Scheduler,
    private val clock: () -> Long,
) {
    private val path = ArrayList<IPoint>()
    private var holdMs = 0L
    private var active = false
    private var session = 0
    private var broken = false
    private val owners = Ownership.Issuer(injector)
    private var ownership = owners.next()

    private var chain: Chain? = null
    private var strokeEnd = IPoint(0, 0)

    /** The injected press only starts moving once its hold (long-press) has played out. */
    private var holdEndMs = 0L
    private var lastDispatchMs = 0L
    private var catchUp: Cancellable? = null

    /** A lifted drag whose release waits for the press's hold to play out. */
    private var pendingRelease: Cancellable? = null

    /** Uptime of the latest dispatch, to tell whether a cancelled pad touch followed one. */
    val lastActivityMs get() = lastDispatchMs

    fun begin(at: IPoint, holdMs: Long) {
        clearTasks()
        session++
        active = true
        broken = false
        path.clear()
        path += at
        this.holdMs = holdMs
        strokeEnd = at
        val now = clock()
        holdEndMs = now + holdMs
        lastDispatchMs = now
        val mine = session
        ownership = owners.next()
        chain = injector.openStroke(listOf(at), holdMs, "drag press") { onResult(mine, it) }
        ownership.claim()
    }

    fun moveTo(p: IPoint) {
        if (!active) return
        if (path.last() != p) path += p
        pump()
    }

    fun finish() {
        if (!active) return
        active = false
        clearTasks()
        if (!ownership.held) {
            DebugLog.v("Live drag was replaced by a newer gesture; dropping its release")
            chain = null
            return
        }
        val drag = Drag(path.toList(), holdMs)
        val wait = holdEndMs - clock()
        if (wait > 0 && !broken) {
            // Continuing the stroke now would cut the press short, and with it the long-press.
            val owner = ownership
            pendingRelease = scheduler.schedule(wait) {
                pendingRelease = null
                if (owner.held) release(drag) else DebugLog.v("Live drag was replaced before its release")
            }
        } else {
            // A cancelled press has no hold left to wait for: the replay plays its own.
            release(drag)
        }
    }

    /**
     * Abandons the drag where the injected finger is, without replaying it: also while its release
     * waits. It also rules out the replay of any release already sent, this drag's or an earlier one's.
     */
    fun cancel() {
        val owned = ownership.held
        owners.revokeAll()
        active = false
        clearTasks()
        val open = chain ?: return
        chain = null
        if (!broken && owned) injector.finishStroke(open, planner.settle(strokeEnd), "drag abort")
    }

    private fun release(drag: Drag) {
        val open = chain
        chain = null
        if (open == null || broken) {
            replay(drag)
            return
        }
        val end = drag.path.last()
        val segments = ArrayList<Segment>(2)
        if (strokeEnd != end) segments += Segment(listOf(strokeEnd, end), catchUpMs(strokeEnd, end))
        segments += planner.settleSegment(end)
        val owner = ownership
        injector.finishStroke(open, StrokePlan(segments), "drag end") { outcome ->
            // Replaced means a newer gesture took over; replaying would undo it.
            if (outcome == Outcome.CANCELLED && owner.held) {
                DebugLog.i("Live drag release was cancelled; replaying the drag")
                replay(drag)
            }
        }
    }

    private fun pump() {
        val open = chain ?: return
        if (!active || broken) return
        if (!ownership.held) {
            // Another gesture took over the injector, and with it our stroke.
            DebugLog.v("Live drag was replaced by a newer gesture")
            chain = null
            active = false
            clearTasks()
            return
        }
        val now = clock()
        if (now < holdEndMs) {
            // Continuing now would queue behind the hold and lag the cursor for the whole drag.
            catchUp?.cancel()
            catchUp = scheduler.schedule(holdEndMs - now) {
                catchUp = null
                pump()
            }
            return
        }
        if (now - lastDispatchMs < MIN_STEP_INTERVAL_MS) return
        val target = path.last()
        if (target == strokeEnd) return
        val firstMove = lastDispatchMs < holdEndMs
        val durationMs = if (firstMove) catchUpMs(strokeEnd, target) else (now - lastDispatchMs).coerceIn(1, MAX_STEP_MS)
        val from = strokeEnd
        strokeEnd = target
        lastDispatchMs = now
        val mine = session
        chain = injector.extendStroke(open, listOf(from, target), durationMs) { onResult(mine, it) }
    }

    private fun onResult(mine: Int, outcome: Outcome) {
        if (outcome != Outcome.CANCELLED || mine != session || broken) return
        broken = true
        DebugLog.i("Live drag stroke was cancelled by the system; the drag will replay on release")
    }

    private fun clearTasks() {
        catchUp?.cancel()
        catchUp = null
        pendingRelease?.cancel()
        pendingRelease = null
    }

    /** Replays [drag] from scratch: the recorded path, or a press if it never moved. */
    private fun replay(drag: Drag) {
        val plan = planner.drag(drag.path, drag.holdMs)
        if (plan != null) {
            injector.play(listOf(plan), "drag replay")
        } else {
            injector.dispatch(planner.press(drag.path.first(), drag.holdMs), "press replay")
        }
    }

    private fun catchUpMs(from: IPoint, to: IPoint) =
        (hypot((to.x - from.x).toFloat(), (to.y - from.y).toFloat()) / CATCH_UP_PX_PER_MS).toLong().coerceIn(16, 200)

    /** What a finished drag replays, fixed when the finger lifts so a later drag can't change it. */
    private class Drag(val path: List<IPoint>, val holdMs: Long)

    private companion object {
        const val MIN_STEP_INTERVAL_MS = 6L
        const val MAX_STEP_MS = 32L
        const val CATCH_UP_PX_PER_MS = 3f
    }
}
