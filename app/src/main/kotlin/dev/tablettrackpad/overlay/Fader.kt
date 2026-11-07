package dev.tablettrackpad.overlay

import android.view.View

/**
 * Fades a view out, e.g. after a spell of inactivity, and back in. Only the view's alpha changes,
 * so a faded-out window keeps receiving touches.
 *
 * Repeated calls toward the same end are ignored. Touch handlers call [fadeIn] on every event,
 * and restarting the animation each time would keep it crawling at the start of its curve.
 */
class Fader(private val view: View, private val fadeOutMs: Long, private val fadeInMs: Long) {
    /** The alpha the view is at or animating to. */
    private var target = 1f

    /** Faded out, or fading out. */
    val hidden get() = target == 0f

    fun fadeOut() = animateTo(0f, fadeOutMs)

    fun fadeIn() = animateTo(1f, fadeInMs)

    /** Fades in from fully transparent, e.g. when the view first appears. */
    fun appear() {
        view.animate().cancel()
        view.alpha = 0f
        target = 0f
        fadeIn()
    }

    private fun animateTo(alpha: Float, durationMs: Long) {
        if (target == alpha) return
        target = alpha
        view.animate().cancel()
        view.animate().alpha(alpha).setDuration(durationMs).start()
    }
}
