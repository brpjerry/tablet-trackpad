package dev.tablettrackpad.inject

import android.accessibilityservice.GestureDescription.StrokeDescription
import dev.tablettrackpad.core.IPoint
import dev.tablettrackpad.core.StrokePlan
import dev.tablettrackpad.core.TimedStroke

/** How an injected gesture ended. */
enum class Outcome {
    /** It played to the end. */
    DONE,

    /** Android cancelled or refused it, so its touches did not all arrive. */
    CANCELLED,

    /** We started a newer job, or cancelled queued ones, before it ended: dropping it was ours. */
    REPLACED,
}

/**
 * Injected strokes that are still pressed, for later steps to continue. They belong to job [job];
 * once a newer job starts, continuing them is refused as [Outcome.REPLACED].
 */
class Chain(val job: Int, internal val strokes: List<StrokeDescription>)

/**
 * Plays planned gestures. Every gesture, or chain of continued strokes, is a job; starting a new
 * job cancels the one before, as dispatching a gesture does on Android, and callbacks say whether
 * a gesture ended on its own, was cancelled by the system, or was replaced by a newer job of ours.
 */
interface Injector {
    /** The newest job. Starting a gesture changes it, so a caller can tell whether its own is still the newest. */
    val job: Int

    /** One gesture made of [strokes] that may overlap in time. */
    fun dispatch(strokes: List<TimedStroke>, label: String, onDone: ((Outcome) -> Unit)? = null)

    /** Several gestures one after another, as one job; stops at the first that does not complete. */
    fun dispatchAll(gestures: List<List<TimedStroke>>, label: String)

    /** Plays single-finger [plans] one after another, each as a chain of continued strokes. */
    fun play(plans: List<StrokePlan>, label: String, onDone: ((Outcome) -> Unit)? = null)

    /**
     * Starts a job that presses one finger per path at its first point, moves along the rest over
     * [durationMs] (or holds still for single points), then stays pressed. Returns at once so later
     * steps can continue it without waiting for this one's result. Our own windows are not made
     * untouchable here: callers only open strokes away from them.
     */
    fun openStrokes(paths: List<List<IPoint>>, durationMs: Long, label: String, onDone: (Outcome) -> Unit): Chain?

    /**
     * Moves each stroke of [chain] along the matching path, which starts where that stroke ended.
     * With [willContinue] false the fingers lift at the end.
     */
    fun extendStrokes(
        chain: Chain,
        paths: List<List<IPoint>>,
        durationMs: Long,
        willContinue: Boolean,
        label: String,
        onDone: (Outcome) -> Unit,
    ): Chain?

    /** Plays [plan] as the continuation of the single still-pressed stroke of [chain], ending with a lift. */
    fun finishStroke(chain: Chain, plan: StrokePlan, label: String, onDone: ((Outcome) -> Unit)? = null)
}

/**
 * Whether one operation (a live scroll, drag or pinch) may still inject. Each operation has its
 * own, which its scheduled work and callbacks keep, and claims the job after each gesture it
 * starts. Once another gesture starts, from anywhere, or its [Issuer] revokes it, the operation
 * is over: it must not inject again, not even to make up for a cancellation, or it would cut a
 * newer gesture short or act after the trackpad was reset.
 */
class Ownership private constructor(private val issuer: Issuer, private val generation: Int) {
    private var claimed: Int? = null

    /** Not revoked, and nothing injected yet or nobody else has injected since. */
    val held get() = generation == issuer.generation && claimed.let { it == null || it == issuer.injector.job }

    /** Records the job just started as this operation's. */
    fun claim() {
        claimed = issuer.injector.job
    }

    /** Hands out the ownerships of one kind of operation, and revokes them all at once. */
    class Issuer(internal val injector: Injector) {
        internal var generation = 0
            private set

        fun next() = Ownership(this, generation)

        /**
         * Ends every operation handed out so far: the current one, and earlier ones whose
         * releases were sent but whose results are still to come.
         */
        fun revokeAll() {
            generation++
        }
    }
}

fun Injector.openStroke(points: List<IPoint>, durationMs: Long, label: String, onDone: (Outcome) -> Unit) =
    openStrokes(listOf(points), durationMs, label, onDone)

fun Injector.extendStroke(chain: Chain, points: List<IPoint>, durationMs: Long, onDone: (Outcome) -> Unit) =
    extendStrokes(chain, listOf(points), durationMs, true, "live step", onDone)
