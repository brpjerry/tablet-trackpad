package dev.tablettrackpad.inject

import dev.tablettrackpad.core.Cancellable
import dev.tablettrackpad.core.IPoint
import dev.tablettrackpad.core.Scheduler
import dev.tablettrackpad.core.StrokePlan
import dev.tablettrackpad.core.TimedStroke
import kotlin.math.hypot

/** Runs scheduled actions in time order as the test moves the clock forward. */
class ManualScheduler(start: Long = 1000) : Scheduler {
    var now = start
        private set

    private class Task(val at: Long, val seq: Int, val action: () -> Unit)

    private val tasks = mutableListOf<Task>()
    private var seq = 0

    override fun schedule(delayMs: Long, action: () -> Unit): Cancellable {
        val task = Task(now + delayMs, seq++, action)
        tasks += task
        return Cancellable { tasks.remove(task) }
    }

    fun advanceTo(time: Long) {
        while (true) {
            val next = tasks.filter { it.at <= time }.minWithOrNull(compareBy({ it.at }, { it.seq })) ?: break
            tasks.remove(next)
            now = next.at
            next.action()
        }
        now = time
    }

    fun advanceBy(ms: Long) = advanceTo(now + ms)
}

/**
 * Records what would be injected, with the job rules of [GestureInjector]: every new gesture
 * starts a job, and continuing a chain of an older job is refused as [Outcome.REPLACED]. Results
 * arrive only when the test calls [Call.complete].
 */
class FakeInjector(private val clock: () -> Long) : Injector {
    enum class Kind { DISPATCH, DISPATCH_ALL, PLAY, OPEN, EXTEND, FINISH, REFUSED }

    class Call(
        val kind: Kind,
        val label: String,
        val job: Int,
        val at: Long,
        val paths: List<List<IPoint>> = emptyList(),
        val willContinue: Boolean = true,
        val plans: List<StrokePlan> = emptyList(),
        val gestures: List<List<TimedStroke>> = emptyList(),
        private val onDone: ((Outcome) -> Unit)? = null,
    ) {
        fun complete(outcome: Outcome) = onDone?.invoke(outcome)
    }

    override var job = 0
        private set
    val calls = mutableListOf<Call>()

    fun of(kind: Kind, label: String? = null) = calls.filter { it.kind == kind && (label == null || it.label == label) }

    override fun dispatch(strokes: List<TimedStroke>, label: String, onDone: ((Outcome) -> Unit)?) {
        calls += Call(Kind.DISPATCH, label, ++job, clock(), gestures = listOf(strokes), onDone = onDone)
    }

    override fun dispatchAll(gestures: List<List<TimedStroke>>, label: String) {
        calls += Call(Kind.DISPATCH_ALL, label, ++job, clock(), gestures = gestures)
    }

    override fun play(plans: List<StrokePlan>, label: String, onDone: ((Outcome) -> Unit)?) {
        calls += Call(Kind.PLAY, label, ++job, clock(), plans = plans, onDone = onDone)
    }

    override fun openStrokes(paths: List<List<IPoint>>, durationMs: Long, label: String, onDone: (Outcome) -> Unit): Chain {
        calls += Call(Kind.OPEN, label, ++job, clock(), paths, onDone = onDone)
        return Chain(job, emptyList())
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
            calls += Call(Kind.REFUSED, label, chain.job, clock(), paths, willContinue, onDone = onDone)
            onDone(Outcome.REPLACED)
            return null
        }
        calls += Call(Kind.EXTEND, label, chain.job, clock(), paths, willContinue, onDone = onDone)
        return Chain(chain.job, emptyList())
    }

    override fun finishStroke(chain: Chain, plan: StrokePlan, label: String, onDone: ((Outcome) -> Unit)?) {
        if (chain.job != job) {
            calls += Call(Kind.REFUSED, label, chain.job, clock(), plans = listOf(plan), onDone = onDone)
            onDone?.invoke(Outcome.REPLACED)
            return
        }
        calls += Call(Kind.FINISH, label, chain.job, clock(), plans = listOf(plan), onDone = onDone)
    }
}

fun distance(a: IPoint, b: IPoint) = hypot((b.x - a.x).toFloat(), (b.y - a.y).toFloat())
