package dev.tablettrackpad.core

enum class TouchAction { DOWN, POINTER_DOWN, MOVE, POINTER_UP, UP, CANCEL }

/** One finger on the pad, in pad-local pixels. */
data class TouchPoint(val id: Int, val x: Float, val y: Float)

enum class SwipeDirection { UP, DOWN, LEFT, RIGHT }

fun interface Cancellable {
    fun cancel()
}

/** Delayed callbacks, so the recognizer's timers can be driven by a fake clock in tests. */
fun interface Scheduler {
    fun schedule(delayMs: Long, action: () -> Unit): Cancellable
}
