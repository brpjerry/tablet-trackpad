package dev.tablettrackpad.inject

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.accessibilityservice.GestureDescription.StrokeDescription
import android.graphics.Path
import android.os.Handler
import android.os.SystemClock
import dev.tablettrackpad.core.IPoint
import dev.tablettrackpad.core.Segment
import dev.tablettrackpad.core.StrokePlan
import dev.tablettrackpad.core.TimedStroke
import dev.tablettrackpad.debug.DebugLog
import dev.tablettrackpad.overlay.PassthroughWindows

/**
 * Plays planned gestures through [AccessibilityService.dispatchGesture].
 *
 * Two platform rules shape this class. Dispatching a gesture cancels any injected gesture still
 * running, so only the newest job matters: [job] counts jobs, older chains stop at their next step,
 * and a cancellation that a newer job of ours caused reports [Outcome.REPLACED] rather than
 * [Outcome.CANCELLED]. And an injected touch hits whatever window is on top, including our own
 * overlays, so when a gesture starts on top of one, that window is made untouchable until it ends.
 */
class GestureInjector(
    private val dispatcher: GestureDispatcher,
    private val overlays: PassthroughWindows,
    private val handler: Handler,
) : Injector {
    /** Sends a gesture to Android, as [AccessibilityService.dispatchGesture] does; false if it refused. */
    fun interface GestureDispatcher {
        fun dispatch(gesture: GestureDescription, callback: AccessibilityService.GestureResultCallback): Boolean
    }

    /** The current job. Continued strokes stay in the job that opened them. */
    override var job = 0
        private set

    override fun dispatch(strokes: List<TimedStroke>, label: String, onDone: ((Outcome) -> Unit)?) {
        if (strokes.isEmpty()) return
        dispatchIn(++job, strokes, label) { onDone?.invoke(it) }
    }

    override fun dispatchAll(gestures: List<List<TimedStroke>>, label: String) {
        val all = gestures.filter { it.isNotEmpty() }
        if (all.isNotEmpty()) dispatchFrom(all, 0, ++job, label)
    }

    override fun play(plans: List<StrokePlan>, label: String, onDone: ((Outcome) -> Unit)?) {
        if (plans.isEmpty()) {
            onDone?.invoke(Outcome.DONE)
            return
        }
        val mine = ++job
        withPassthrough(plans.map { it.start }, mine, onDone) { release ->
            runPlans(plans, 0, mine, label) { outcome ->
                release()
                onDone?.invoke(outcome)
            }
        }
    }

    override fun openStrokes(paths: List<List<IPoint>>, durationMs: Long, label: String, onDone: (Outcome) -> Unit): Chain? {
        val mine = ++job
        val strokes = build(label, mine, onDone) { paths.map { StrokeDescription(it.toPath(), 0, durationMs, true) } }
            ?: return null
        send(strokes.toGesture(), mine, label, quiet = true, onDone)
        return Chain(mine, strokes)
    }

    override fun extendStrokes(
        chain: Chain,
        paths: List<List<IPoint>>,
        durationMs: Long,
        willContinue: Boolean,
        label: String,
        onDone: (Outcome) -> Unit,
    ): Chain? {
        if (chain.job != job) {
            handler.post { onDone(Outcome.REPLACED) }
            return null
        }
        val strokes = build(label, chain.job, onDone) {
            chain.strokes.zip(paths) { stroke, path -> stroke.continueStroke(path.toPath(), 0, durationMs, willContinue) }
        } ?: return null
        send(strokes.toGesture(), chain.job, label, quiet = willContinue, onDone)
        return Chain(chain.job, strokes)
    }

    override fun finishStroke(chain: Chain, plan: StrokePlan, label: String, onDone: ((Outcome) -> Unit)?) {
        runSegments(plan, 0, chain.strokes.single(), chain.job, label) { onDone?.invoke(it) }
    }

    /** Stops every queued chain, and turns the results of gestures still playing into [Outcome.REPLACED]. */
    fun cancelQueued() {
        job++
    }

    /** One gesture of job [mine], once our windows under its first points let touches through. */
    private fun dispatchIn(mine: Int, strokes: List<TimedStroke>, label: String, onDone: (Outcome) -> Unit) {
        withPassthrough(strokes.map { it.points.first() }, mine, onDone) { release ->
            val finish: (Outcome) -> Unit = { outcome ->
                release()
                onDone(outcome)
            }
            val gesture = build(label, mine, finish) {
                strokes.map { StrokeDescription(it.points.toPath(), it.startMs, it.durationMs) }.toGesture()
            }
            if (gesture != null) send(gesture, mine, label, quiet = false, finish)
        }
    }

    private fun dispatchFrom(gestures: List<List<TimedStroke>>, index: Int, mine: Int, label: String) {
        if (index == gestures.size || mine != job) return
        dispatchIn(mine, gestures[index], label) { outcome ->
            if (outcome == Outcome.DONE) dispatchFrom(gestures, index + 1, mine, label)
        }
    }

    private fun runPlans(plans: List<StrokePlan>, index: Int, mine: Int, label: String, finish: (Outcome) -> Unit) {
        if (index == plans.size) {
            finish(Outcome.DONE)
            return
        }
        runSegments(plans[index], 0, null, mine, label) { outcome ->
            if (outcome == Outcome.DONE) runPlans(plans, index + 1, mine, label, finish) else finish(outcome)
        }
    }

    private fun runSegments(
        plan: StrokePlan,
        index: Int,
        previous: StrokeDescription?,
        mine: Int,
        label: String,
        finish: (Outcome) -> Unit,
    ) {
        if (mine != job) {
            finish(Outcome.REPLACED)
            return
        }
        val segment: Segment = plan.segments[index]
        val last = index == plan.segments.lastIndex
        val stroke = build(label, mine, finish) {
            val path = segment.points.toPath()
            previous?.continueStroke(path, 0, segment.durationMs, !last)
                ?: StrokeDescription(path, 0, segment.durationMs, !last)
        } ?: return
        send(GestureDescription.Builder().addStroke(stroke).build(), mine, label, quiet = false) { outcome ->
            if (outcome != Outcome.DONE || last) finish(outcome) else runSegments(plan, index + 1, stroke, mine, label, finish)
        }
    }

    /** A cancellation is ours when a newer job has started since. */
    private fun cancelled(mine: Int) = if (mine != job) Outcome.REPLACED else Outcome.CANCELLED

    private fun send(gesture: GestureDescription, mine: Int, label: String, quiet: Boolean, onResult: (Outcome) -> Unit) {
        val sentAt = SystemClock.uptimeMillis()
        if (!quiet) DebugLog.v("$label dispatched")
        val accepted = try {
            dispatcher.dispatch(gesture, object : AccessibilityService.GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription) {
                    if (!quiet) DebugLog.v("$label completed after ${SystemClock.uptimeMillis() - sentAt} ms")
                    onResult(Outcome.DONE)
                }

                override fun onCancelled(gestureDescription: GestureDescription) {
                    val outcome = cancelled(mine)
                    when {
                        outcome == Outcome.REPLACED -> DebugLog.v("$label replaced")
                        quiet -> DebugLog.v("$label cancelled")
                        else -> DebugLog.i("$label cancelled by the system")
                    }
                    onResult(outcome)
                }
            })
        } catch (e: IllegalArgumentException) {
            DebugLog.w("$label rejected: $e")
            false
        }
        if (!accepted) {
            DebugLog.w("$label: dispatchGesture refused (service not ready?)")
            // Asynchronously, like a cancellation, so callers never see the result before the call returns.
            handler.post { onResult(cancelled(mine)) }
        }
    }

    /**
     * Runs [block], which builds strokes. The platform rejects bad strokes (off-screen points, a
     * continuation that does not line up) by throwing, which must not take the service down: the
     * gesture fails like a cancelled one instead.
     */
    private inline fun <T> build(label: String, mine: Int, noinline onResult: (Outcome) -> Unit, block: () -> T): T? = try {
        block()
    } catch (e: RuntimeException) {
        DebugLog.w("$label could not be built: $e")
        handler.post { onResult(cancelled(mine)) }
        null
    }

    /**
     * Runs [action] once none of our touchable windows sit under [points]. The window flag change
     * takes a frame or two to reach the input system, hence the delay.
     */
    private fun withPassthrough(
        points: List<IPoint>,
        mine: Int,
        onDone: ((Outcome) -> Unit)?,
        action: (release: () -> Unit) -> Unit,
    ) {
        val blocking = points.flatMap { overlays.touchableWindowsAt(it.x, it.y) }.distinct()
        if (blocking.isEmpty()) {
            action {}
            return
        }
        blocking.forEach(overlays::acquirePassthrough)
        DebugLog.v("passthrough on for $blocking")
        var released = false
        val release = {
            if (!released) {
                released = true
                blocking.forEach(overlays::releasePassthrough)
            }
        }
        handler.postDelayed({
            if (mine != job) {
                release()
                onDone?.invoke(Outcome.REPLACED)
            } else {
                action(release)
            }
        }, PASSTHROUGH_SETTLE_MS)
    }

    private fun List<StrokeDescription>.toGesture() =
        GestureDescription.Builder().apply { forEach(::addStroke) }.build()

    private fun List<IPoint>.toPath() = Path().apply {
        moveTo(this@toPath[0].x.toFloat(), this@toPath[0].y.toFloat())
        for (i in 1 until size) lineTo(this@toPath[i].x.toFloat(), this@toPath[i].y.toFloat())
    }

    private companion object {
        const val PASSTHROUGH_SETTLE_MS = 80L
    }
}
