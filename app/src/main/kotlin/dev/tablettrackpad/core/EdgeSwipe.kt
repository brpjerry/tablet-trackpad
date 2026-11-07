package dev.tablettrackpad.core

/**
 * Whether a touch that started on an edge swipe zone is an inward swipe: shared by the zones
 * themselves and by the trackpad where it lies over one, so both judge a swipe the same way.
 */
object EdgeSwipe {
    /** How far inward the finger must travel, in dp. */
    const val TRIGGER_DP = 36f

    /** Movement that already rules a swipe out, in dp. */
    const val SLOP_DP = 8f

    /** An inward swipe moves at least this many times further inward than sideways. */
    const val DIRECTION_RATIO = 1.5f

    enum class Verdict { SWIPE, NOT_SWIPE, UNDECIDED }

    /** Judges a touch from how far it moved [inward] (negative: outward) and [sideways] (absolute). */
    fun judge(inward: Float, sideways: Float, triggerPx: Float, slopPx: Float): Verdict = when {
        inward > triggerPx && inward > sideways * DIRECTION_RATIO -> Verdict.SWIPE
        inward < -slopPx || (sideways > slopPx && sideways * DIRECTION_RATIO >= inward) -> Verdict.NOT_SWIPE
        else -> Verdict.UNDECIDED
    }
}
