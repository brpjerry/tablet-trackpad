package dev.tablettrackpad.core

import kotlin.math.abs

/**
 * Locks a scroll to the axis it started on, like laptop trackpads do. Injected swipes then stay
 * straight, so a vertical list never sees a stray horizontal drag that a parent pager could steal.
 */
class ScrollAxisLock {
    enum class Axis { UNDECIDED, VERTICAL, HORIZONTAL, FREE }

    var axis = Axis.UNDECIDED
        private set

    fun reset() {
        axis = Axis.UNDECIDED
    }

    fun filter(dx: Float, dy: Float, out: FloatArray) {
        if (axis == Axis.UNDECIDED && (dx != 0f || dy != 0f)) {
            axis = when {
                abs(dy) >= abs(dx) * LOCK_RATIO -> Axis.VERTICAL
                abs(dx) >= abs(dy) * LOCK_RATIO -> Axis.HORIZONTAL
                else -> Axis.FREE
            }
        }
        out[0] = if (axis == Axis.VERTICAL) 0f else dx
        out[1] = if (axis == Axis.HORIZONTAL) 0f else dy
    }

    private companion object {
        const val LOCK_RATIO = 1.5f
    }
}
