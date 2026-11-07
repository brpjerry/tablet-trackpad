package dev.tablettrackpad.core

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max

/**
 * Turns raw multi-touch input on the pad into laptop-style trackpad gestures.
 *
 * Coordinates are pad-local pixels. The recognizer knows nothing about the cursor or how gestures
 * reach the screen; the [Listener] maps them onto it.
 *
 * - One finger moves the pointer. A short tap clicks; tap then touch-and-move drags; two quick
 *   taps double-click. A long press is either a two-finger tap or, with
 *   [Config.holdForLongPress], tap then touch and hold.
 * - One finger that starts in the scroll zone (see [Config.inScrollZone]) and moves up or down
 *   scrolls; moving sideways out of it moves the pointer as usual.
 * - Two fingers scroll or pinch.
 * - Three fingers swipe in a direction or tap.
 *
 * After any multi-finger gesture the remaining fingers are ignored until all of them lift, so a
 * scroll never ends with a stray pointer jump.
 */
class GestureRecognizer(
    config: Config,
    private val scheduler: Scheduler,
    private val listener: Listener,
) {
    data class Config(
        /** Movement that turns a touch from a potential tap into pointer motion. */
        val tapSlopPx: Float,
        /** Centroid travel before two fingers start scrolling. */
        val scrollSlopPx: Float,
        /** Change in finger spacing before two fingers start pinching. */
        val pinchSlopPx: Float,
        /** Centroid travel that completes a three-finger swipe. */
        val swipeDistancePx: Float,
        val tapTimeoutMs: Long = 220,
        val multiTapTimeoutMs: Long = 300,
        /** How long a tap waits for a second touch that would turn it into a drag or double-click. */
        val tapDragTimeoutMs: Long = 220,
        val tapAndDrag: Boolean = true,
        /**
         * Tap then touch and hold is a long press, and two-finger taps do nothing. Otherwise a
         * two-finger tap is the long press.
         */
        val holdForLongPress: Boolean = false,
        /** How long the second touch holds still before it becomes a long press. */
        val holdMs: Long = 300,
        /** A tap-and-drag whose finger rested this long before moving starts with a long press. */
        val longPressMs: Long = 500,
        val pinchEnabled: Boolean = true,
        /** Whether a touch starting at this pad point is in the one-finger scroll zone. */
        val inScrollZone: (x: Float, y: Float) -> Boolean = { _, _ -> false },
    )

    interface Listener {
        fun onPointerMove(dx: Float, dy: Float, timeMs: Long)
        fun onClick()
        fun onDoubleClick()
        fun onTwoFingerTap()
        fun onThreeFingerTap()
        fun onThreeFingerSwipe(direction: SwipeDirection)
        /** [inZone] for a one-finger scroll in the scroll zone, which only reports vertical motion. */
        fun onScrollBegin(inZone: Boolean)
        fun onScroll(dx: Float, dy: Float, timeMs: Long)

        /** Velocity is the fingers' speed at lift-off, in pad pixels per second. */
        fun onScrollEnd(velocityX: Float, velocityY: Float)
        fun onPinchBegin()

        /** [scale] is the finger spacing relative to where the pinch started. */
        fun onPinch(scale: Float)
        fun onPinchEnd(scale: Float)

        /**
         * [longPress] when the drag starts with a long press, which picks things up: the finger
         * rested before moving, or held still with [Config.holdForLongPress]. The drag may then
         * end without having moved, which makes it a plain long press.
         */
        fun onDragBegin(longPress: Boolean)
        fun onDragMove(dx: Float, dy: Float, timeMs: Long)
        fun onDragEnd()

        /** The platform cancelled the touch stream in the middle of a gesture. */
        fun onGestureCancelled()
    }

    private enum class Mode { IDLE, POINTER, ZONE, ZONE_SCROLL, SECOND_TOUCH, DRAG, MULTI, SCROLL, PINCH, SWIPE, DONE }

    var config: Config = config

    private var mode = Mode.IDLE
    private val points = LinkedHashMap<Int, FloatArray>()
    private var downTimeMs = 0L
    private var maxPointers = 0
    private var moved = false

    private var primaryId = -1
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f

    // Multi-finger tracking. Rebased whenever fingers are added or lifted, so a change in the set
    // of fingers never shows up as a jump in position or spacing.
    private var baseCx = 0f
    private var baseCy = 0f
    private var lastCx = 0f
    private var lastCy = 0f
    private var baseSpan = 1f
    private var lastSpan = 1f

    private val velocity = VelocityEstimator()
    private val velocityOut = FloatArray(2)
    private var pendingClick: Cancellable? = null
    private var holdTimer: Cancellable? = null

    fun onTouch(action: TouchAction, changedId: Int, touches: List<TouchPoint>, timeMs: Long) {
        when (action) {
            TouchAction.DOWN -> onDown(changedId, touches, timeMs)
            TouchAction.POINTER_DOWN -> onPointerDown(touches)
            TouchAction.MOVE -> onMove(touches, timeMs)
            TouchAction.POINTER_UP -> onPointerUp(changedId, touches, timeMs)
            TouchAction.UP -> onUp(touches, timeMs)
            TouchAction.CANCEL -> cancel()
        }
    }

    /** Drops the gesture in progress and any click still waiting for a second touch. */
    fun reset() {
        pendingClick?.cancel()
        pendingClick = null
        clearHold()
        points.clear()
        mode = Mode.IDLE
    }

    private fun onDown(id: Int, touches: List<TouchPoint>, timeMs: Long) {
        // A DOWN while a gesture is open means we never saw its end.
        if (mode != Mode.IDLE) cancel()
        update(touches)
        val p = points[id] ?: return
        downTimeMs = timeMs
        maxPointers = points.size
        moved = false
        primaryId = id
        startX = p[0]
        startY = p[1]
        lastX = p[0]
        lastY = p[1]
        val pending = pendingClick
        mode = if (pending != null) {
            pending.cancel()
            pendingClick = null
            if (config.holdForLongPress) holdTimer = scheduler.schedule(config.holdMs, ::holdElapsed)
            Mode.SECOND_TOUCH
        } else if (config.inScrollZone(p[0], p[1])) {
            Mode.ZONE
        } else {
            Mode.POINTER
        }
    }

    private fun onPointerDown(touches: List<TouchPoint>) {
        update(touches)
        maxPointers = max(maxPointers, points.size)
        when (mode) {
            Mode.POINTER, Mode.ZONE -> enterMulti()
            Mode.SECOND_TOUCH -> {
                // The earlier tap was a plain click after all.
                clearHold()
                listener.onClick()
                enterMulti()
            }
            Mode.MULTI -> if (points.size >= 3) enterMulti() else rebase()
            Mode.SWIPE, Mode.SCROLL, Mode.PINCH -> rebase()
            Mode.IDLE, Mode.DRAG, Mode.ZONE_SCROLL, Mode.DONE -> Unit
        }
    }

    private fun onMove(touches: List<TouchPoint>, timeMs: Long) {
        update(touches)
        processMove(timeMs)
    }

    /** Applies the latest finger positions to the gesture in progress. */
    private fun processMove(timeMs: Long) {
        when (mode) {
            Mode.POINTER -> movePrimary(timeMs)
            Mode.ZONE -> decideZone(timeMs)
            Mode.ZONE_SCROLL -> points[primaryId]?.let { p ->
                val dy = p[1] - lastY
                lastY = p[1]
                velocity.add(timeMs, 0f, p[1] - startY)
                if (dy != 0f) listener.onScroll(0f, dy, timeMs)
            }
            Mode.SECOND_TOUCH -> {
                val p = points[primaryId] ?: return
                if (hypot(p[0] - startX, p[1] - startY) > config.tapSlopPx) {
                    clearHold()
                    moved = true
                    if (config.tapAndDrag) {
                        mode = Mode.DRAG
                        listener.onDragBegin(longPress = timeMs - downTimeMs >= config.longPressMs)
                        emitDrag(p, timeMs)
                    } else {
                        // Without tap-and-drag the first tap was a click, and this touch moves the pointer.
                        listener.onClick()
                        mode = Mode.POINTER
                        movePrimary(timeMs)
                    }
                }
            }
            Mode.DRAG -> points[primaryId]?.let { emitDrag(it, timeMs) }
            Mode.MULTI -> decideMulti(timeMs)
            Mode.SCROLL -> {
                val (cx, cy) = centroid()
                val dx = cx - lastCx
                val dy = cy - lastCy
                lastCx = cx
                lastCy = cy
                velocity.add(timeMs, cx - baseCx, cy - baseCy)
                if (dx != 0f || dy != 0f) listener.onScroll(dx, dy, timeMs)
            }
            Mode.PINCH -> {
                lastSpan = span().coerceAtLeast(1f)
                listener.onPinch(lastSpan / baseSpan)
            }
            Mode.SWIPE -> {
                val (cx, cy) = centroid()
                lastCx = cx
                lastCy = cy
                val dx = cx - baseCx
                val dy = cy - baseCy
                val travel = hypot(dx, dy)
                if (travel > config.tapSlopPx) moved = true
                if (points.size >= 3 && travel > config.swipeDistancePx) {
                    listener.onThreeFingerSwipe(direction(dx, dy))
                    mode = Mode.DONE
                }
            }
            Mode.IDLE, Mode.DONE -> Unit
        }
    }

    private fun onPointerUp(id: Int, touches: List<TouchPoint>, timeMs: Long) {
        // A lift can carry the fingers' last movement; take it in before ending anything.
        if (update(touches)) processMove(timeMs)
        val remaining = points.size - 1
        when (mode) {
            Mode.SCROLL -> if (remaining < 2) {
                finishScroll(timeMs)
                mode = Mode.DONE
            }
            Mode.ZONE_SCROLL -> if (id == primaryId) {
                finishScroll(timeMs)
                mode = Mode.DONE
            }
            Mode.PINCH -> if (remaining < 2) {
                listener.onPinchEnd(lastSpan / baseSpan)
                mode = Mode.DONE
            }
            else -> Unit
        }
        points.remove(id)
        when (mode) {
            Mode.DRAG -> if (id == primaryId) adoptPrimary()
            Mode.MULTI, Mode.SWIPE, Mode.SCROLL, Mode.PINCH -> rebase()
            else -> Unit
        }
    }

    private fun onUp(touches: List<TouchPoint>, timeMs: Long) {
        // A lift can carry the finger's last movement; take it in before ending anything.
        if (update(touches)) processMove(timeMs)
        clearHold()
        val duration = timeMs - downTimeMs
        when (mode) {
            Mode.POINTER, Mode.ZONE ->
                if (!moved && duration <= config.tapTimeoutMs) tap()
            Mode.SECOND_TOUCH ->
                if (!moved && duration <= config.tapTimeoutMs) listener.onDoubleClick() else listener.onClick()
            Mode.DRAG -> listener.onDragEnd()
            Mode.MULTI ->
                if (maxPointers == 2 && !moved && duration <= config.multiTapTimeoutMs && !config.holdForLongPress) {
                    listener.onTwoFingerTap()
                }
            Mode.SWIPE ->
                if (maxPointers == 3 && !moved && duration <= config.multiTapTimeoutMs) listener.onThreeFingerTap()
            Mode.SCROLL, Mode.ZONE_SCROLL -> finishScroll(timeMs)
            Mode.PINCH -> listener.onPinchEnd(lastSpan / baseSpan)
            Mode.IDLE, Mode.DONE -> Unit
        }
        points.clear()
        mode = Mode.IDLE
    }

    private fun cancel() {
        val previous = mode
        clearHold()
        points.clear()
        mode = Mode.IDLE
        if (previous == Mode.IDLE) return
        listener.onGestureCancelled()
        // A cancelled second touch still leaves the first tap's click owed.
        if (previous == Mode.SECOND_TOUCH) listener.onClick()
    }

    private fun tap() {
        // Only wait for a second touch if it could make a drag or a long press.
        if (!config.tapAndDrag && !config.holdForLongPress) {
            listener.onClick()
            return
        }
        pendingClick = scheduler.schedule(config.tapDragTimeoutMs) {
            pendingClick = null
            listener.onClick()
        }
    }

    /** The second touch of a double-tap-and-hold stayed put: a long press, which can still drag. */
    private fun holdElapsed() {
        holdTimer = null
        if (mode != Mode.SECOND_TOUCH) return
        mode = Mode.DRAG
        listener.onDragBegin(longPress = true)
    }

    private fun clearHold() {
        holdTimer?.cancel()
        holdTimer = null
    }

    private fun movePrimary(timeMs: Long) {
        val p = points[primaryId] ?: return
        if (!moved) {
            if (hypot(p[0] - startX, p[1] - startY) <= config.tapSlopPx) return
            moved = true
        }
        val dx = p[0] - lastX
        val dy = p[1] - lastY
        lastX = p[0]
        lastY = p[1]
        if (dx != 0f || dy != 0f) listener.onPointerMove(dx, dy, timeMs)
    }

    /** A touch that started in the scroll zone scrolls if it goes up or down, else moves the pointer. */
    private fun decideZone(timeMs: Long) {
        val p = points[primaryId] ?: return
        val dx = p[0] - startX
        val dy = p[1] - startY
        if (abs(dy) >= abs(dx) && abs(dy) > config.scrollSlopPx) {
            moved = true
            mode = Mode.ZONE_SCROLL
            lastY = p[1]
            velocity.reset()
            velocity.add(timeMs, 0f, dy)
            listener.onScrollBegin(inZone = true)
            listener.onScroll(0f, dy, timeMs)
        } else if (abs(dx) > abs(dy) && abs(dx) > config.tapSlopPx) {
            mode = Mode.POINTER
            movePrimary(timeMs)
        }
    }

    private fun emitDrag(p: FloatArray, timeMs: Long) {
        val dx = p[0] - lastX
        val dy = p[1] - lastY
        lastX = p[0]
        lastY = p[1]
        if (dx != 0f || dy != 0f) listener.onDragMove(dx, dy, timeMs)
    }

    private fun decideMulti(timeMs: Long) {
        val (cx, cy) = centroid()
        val s = span().coerceAtLeast(1f)
        val travel = hypot(cx - baseCx, cy - baseCy)
        val spread = abs(s - baseSpan)
        if (travel > config.tapSlopPx || spread > config.tapSlopPx) moved = true
        if (points.size < 2) {
            // One finger left over from a two-finger touch neither scrolls nor moves the pointer.
            if (moved) mode = Mode.DONE
            return
        }
        // A pinch with one finger held still moves the centroid half as far as the spacing
        // changes, so spacing has to win outright before the travel check can call it a scroll.
        if (config.pinchEnabled && spread > travel) {
            if (spread > config.pinchSlopPx) {
                mode = Mode.PINCH
                lastSpan = s
                listener.onPinchBegin()
                listener.onPinch(s / baseSpan)
            }
        } else if (travel > config.scrollSlopPx) {
            mode = Mode.SCROLL
            velocity.reset()
            velocity.add(timeMs, cx - baseCx, cy - baseCy)
            listener.onScrollBegin(inZone = false)
            listener.onScroll(cx - lastCx, cy - lastCy, timeMs)
            lastCx = cx
            lastCy = cy
        }
    }

    private fun finishScroll(timeMs: Long) {
        velocity.velocity(timeMs, velocityOut)
        listener.onScrollEnd(velocityOut[0], velocityOut[1])
    }

    private fun enterMulti() {
        mode = if (points.size >= 3) Mode.SWIPE else Mode.MULTI
        val (cx, cy) = centroid()
        baseCx = cx
        baseCy = cy
        lastCx = cx
        lastCy = cy
        baseSpan = span().coerceAtLeast(1f)
        lastSpan = baseSpan
    }

    private fun rebase() {
        if (points.isEmpty()) return
        val (cx, cy) = centroid()
        val travelX = lastCx - baseCx
        val travelY = lastCy - baseCy
        lastCx = cx
        lastCy = cy
        baseCx = cx - travelX
        baseCy = cy - travelY
        val scale = lastSpan / baseSpan
        val s = span().coerceAtLeast(1f)
        lastSpan = s
        baseSpan = s / scale
    }

    private fun adoptPrimary() {
        val (id, p) = points.entries.firstOrNull() ?: return
        primaryId = id
        lastX = p[0]
        lastY = p[1]
    }

    /** Records the positions of [touches]; returns whether a known finger moved. */
    private fun update(touches: List<TouchPoint>): Boolean {
        var moved = false
        for (t in touches) {
            val p = points[t.id]
            if (p == null) {
                points[t.id] = floatArrayOf(t.x, t.y)
                continue
            }
            if (p[0] != t.x || p[1] != t.y) moved = true
            p[0] = t.x
            p[1] = t.y
        }
        return moved
    }

    private fun centroid(): Pair<Float, Float> {
        var x = 0f
        var y = 0f
        for (p in points.values) {
            x += p[0]
            y += p[1]
        }
        val n = points.size.coerceAtLeast(1)
        return x / n to y / n
    }

    /** Twice the mean distance from the centroid: the finger spacing for two fingers. */
    private fun span(): Float {
        if (points.size < 2) return 0f
        val (cx, cy) = centroid()
        var total = 0f
        for (p in points.values) total += hypot(p[0] - cx, p[1] - cy)
        return 2f * total / points.size
    }

    private fun direction(dx: Float, dy: Float): SwipeDirection = if (abs(dx) > abs(dy)) {
        if (dx > 0) SwipeDirection.RIGHT else SwipeDirection.LEFT
    } else {
        if (dy > 0) SwipeDirection.DOWN else SwipeDirection.UP
    }
}
