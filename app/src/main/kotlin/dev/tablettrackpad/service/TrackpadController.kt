package dev.tablettrackpad.service

import android.accessibilityservice.AccessibilityService
import android.os.Handler
import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.view.ViewConfiguration
import dev.tablettrackpad.core.GesturePlanner
import dev.tablettrackpad.core.GestureRecognizer
import dev.tablettrackpad.core.IPoint
import dev.tablettrackpad.core.PointerAcceleration
import dev.tablettrackpad.core.ScrollAxisLock
import dev.tablettrackpad.core.SwipeDirection
import dev.tablettrackpad.debug.DebugLog
import dev.tablettrackpad.inject.GestureInjector
import dev.tablettrackpad.inject.LiveDragger
import dev.tablettrackpad.inject.LivePincher
import dev.tablettrackpad.inject.LiveScroller
import dev.tablettrackpad.overlay.OverlayManager
import dev.tablettrackpad.settings.GestureAction
import dev.tablettrackpad.settings.TrackpadSettings
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Maps recognized trackpad gestures onto the virtual cursor and the gestures injected under it.
 */
class TrackpadController(
    private val service: AccessibilityService,
    private val overlays: OverlayManager,
    private val injector: GestureInjector,
    private val settings: TrackpadSettings,
    private val handler: Handler,
) : GestureRecognizer.Listener {
    private val density = service.resources.displayMetrics.density
    private val viewConfig = ViewConfiguration.get(service)
    private val longPressMs = ViewConfiguration.getLongPressTimeout().toLong()
    private val minFlingPx = MIN_FLING_DP_PER_S * density
    private val maxFlingPx = viewConfig.scaledMaximumFlingVelocity.toFloat()

    private val planner = GesturePlanner(viewConfig.scaledTouchSlop, (EDGE_MARGIN_DP * density).roundToInt())
    private val acceleration = PointerAcceleration(density)
    private val axisLock = ScrollAxisLock()
    private val scheduler = HandlerScheduler(handler)
    private val clock = SystemClock::uptimeMillis
    private val liveScroller = LiveScroller(injector, planner, clock, ::clearOfOverlays)
    private val liveDragger = LiveDragger(injector, planner, scheduler, clock)
    private val livePincher = LivePincher(injector, planner, viewConfig.scaledMinimumScalingSpan, scheduler, clock)
    private val tmp = FloatArray(2)

    private var cursorX = -1f
    private var cursorY = -1f

    private var scrolling = false
    private var scrollLive = false

    /** Scroll zone speed for a one-finger scroll in the zone, otherwise 1. */
    private var scrollMultiplier = 1f
    private var scrollAnchor = IPoint(0, 0)
    private var owedX = 0f
    private var owedY = 0f

    private var pinching = false
    private var pinchLive = false

    private var dragging = false
    private var dragLive = false
    private var dragPath = ArrayList<IPoint>()
    private var dragHoldMs = 0L

    /** When one of our own injected touches last landed on the trackpad instead of an app. */
    private var ownTouchOnPadAt = Long.MIN_VALUE / 2

    /** Whether a finger is on the trackpad window, which keeps the cursor and pad from hiding. */
    private var padTouched = false
    private val hideCursor = Runnable { overlays.cursorView.conceal() }
    private val hidePad = Runnable { overlays.trackpadView.conceal() }

    /** Fullscreen content is showing and the settings hide everything for it, as last applied. */
    private var hidingForFullscreen = false
    private val fullscreenHidesAll get() = settings.hideInFullscreen && overlays.fullscreen

    // During fullscreen content both hide when idle, whatever their own settings say.
    private val cursorAutoHides get() = settings.cursorAutoHide || hidingForFullscreen
    private val padAutoHides get() = settings.padAutoHide || hidingForFullscreen

    fun applySettings() {
        acceleration.speed = settings.pointerSpeed
        acceleration.acceleration = settings.acceleration
        overlays.cursorView.scale = settings.cursorScale
        overlays.trackpadView.scrollZone = if (settings.scrollZone) settings.scrollZoneSide else null
        if (settings.hideInFullscreen) overlays.fullscreenWatcher.start() else overlays.fullscreenWatcher.stop()
        if (fullscreenHidesAll != hidingForFullscreen) {
            applyFullscreen()
            return
        }
        if (!cursorAutoHides) overlays.cursorView.reveal()
        if (!padAutoHides) overlays.trackpadView.reveal()
        scheduleIdleHides()
    }

    /** An app entered or left fullscreen content. */
    fun onFullscreenChanged() {
        if (settings.hideInFullscreen) {
            DebugLog.i(if (overlays.fullscreen) "Fullscreen content: hiding everything" else "Fullscreen content ended")
        }
        applyFullscreen()
    }

    /**
     * Hides the trackpad, cursor and edge markers at once while fullscreen content shows (they
     * keep working, and touching the pad brings it back for a while), and brings them back after.
     */
    private fun applyFullscreen() {
        val hide = fullscreenHidesAll
        if (hide == hidingForFullscreen) return
        hidingForFullscreen = hide
        overlays.setEdgeMarkersHidden(hide)
        if (hide) {
            handler.removeCallbacks(hideCursor)
            handler.removeCallbacks(hidePad)
            if (!padTouched) {
                overlays.cursorView.conceal()
                overlays.trackpadView.conceal()
            }
        } else {
            wakeOverlays()
        }
    }

    /**
     * Any touch on the trackpad window, [touching] until the last finger lifts. The cursor and pad
     * show while it lasts and, with auto-hide on, fade out once the pad has been idle a while.
     */
    fun onTrackpadActivity(touching: Boolean) {
        padTouched = touching
        if (touching) {
            handler.removeCallbacks(hideCursor)
            handler.removeCallbacks(hidePad)
            overlays.cursorView.reveal()
            overlays.trackpadView.reveal()
        } else {
            scheduleIdleHides()
        }
    }

    /** Shows the cursor and pad, e.g. when the trackpad appears, and starts the idle countdowns afresh. */
    fun wakeOverlays() {
        overlays.cursorView.reveal()
        overlays.trackpadView.reveal()
        scheduleIdleHides()
    }

    private fun scheduleIdleHides() {
        handler.removeCallbacks(hideCursor)
        handler.removeCallbacks(hidePad)
        if (padTouched) return
        if (cursorAutoHides) handler.postDelayed(hideCursor, millis(settings.cursorHideDelaySeconds))
        if (padAutoHides) handler.postDelayed(hidePad, millis(settings.padHideDelaySeconds))
    }

    private fun millis(seconds: Float) = (seconds * 1000).roundToInt().toLong()

    fun onDisplayChanged() {
        val bounds = refreshBounds()
        if (cursorX < 0f) {
            cursorX = bounds.width / 2f
            cursorY = bounds.height / 2f
        }
        moveCursorBy(0f, 0f)
    }

    /**
     * Reads the display size and which system bars show. Bars come and go with the app in front,
     * so swipes and pinches read them again as they start.
     */
    private fun refreshBounds() = overlays.displayBounds().also {
        if (it != planner.bounds) DebugLog.v("Display $it")
        planner.bounds = it
    }

    /** Drops any gesture in progress, e.g. when the trackpad is hidden. */
    fun reset() {
        scrolling = false
        liveScroller.stop()
        dragging = false
        liveDragger.cancel()
        pinching = false
        livePincher.cancel()
        overlays.cursorView.clearFeedback()
        padTouched = false
        handler.removeCallbacks(hideCursor)
        handler.removeCallbacks(hidePad)
    }

    /** Hands a tap on an edge strip back to the app underneath. */
    fun forwardTap(x: Float, y: Float) {
        injector.dispatch(planner.tap(IPoint(x.roundToInt(), y.roundToInt())), "edge tap")
    }

    override fun onPointerMove(dx: Float, dy: Float, timeMs: Long) {
        acceleration.apply(dx, dy, timeMs, tmp)
        moveCursorBy(tmp[0], tmp[1])
    }

    override fun onClick() {
        click("click") { injector.dispatch(planner.tap(it), "click") }
    }

    override fun onDoubleClick() {
        click("double click") { injector.dispatch(planner.doubleTap(it), "double click") }
    }

    override fun onTwoFingerTap() {
        overlays.cursorView.pulse(secondary = true)
        haptic()
        injector.dispatch(planner.press(cursorPoint(), longPressMs + LONG_PRESS_MARGIN_MS), "right click")
    }

    override fun onThreeFingerTap() = perform(settings.threeFingerTap, "3-finger tap")

    override fun onThreeFingerSwipe(direction: SwipeDirection) {
        val action = when (direction) {
            SwipeDirection.UP -> settings.threeFingerUp
            SwipeDirection.DOWN -> settings.threeFingerDown
            SwipeDirection.LEFT -> settings.threeFingerLeft
            SwipeDirection.RIGHT -> settings.threeFingerRight
        }
        perform(action, "3-finger swipe $direction")
    }

    override fun onScrollBegin(inZone: Boolean) {
        axisLock.reset()
        scrolling = true
        scrollMultiplier = if (inZone) settings.scrollZoneSpeed else 1f
        owedX = 0f
        owedY = 0f
        refreshBounds()
        scrollAnchor = planner.swipeAnchor(cursorPoint())
        scrollLive = canGoLive(scrollAnchor)
        if (scrollLive) liveScroller.begin(scrollAnchor)
    }

    override fun onScroll(dx: Float, dy: Float, timeMs: Long) {
        if (!scrolling) return
        val factor = scrollFactor()
        axisLock.filter(dx * factor, dy * factor, tmp)
        if (scrollLive) {
            liveScroller.add(tmp[0], tmp[1])
        } else {
            owedX += tmp[0]
            owedY += tmp[1]
            overlays.cursorView.setScrollPreview(owedX, owedY)
        }
    }

    override fun onScrollEnd(velocityX: Float, velocityY: Float) {
        if (!scrolling) return
        scrolling = false
        overlays.cursorView.setScrollPreview(0f, 0f)

        var vx = 0f
        var vy = 0f
        if (settings.momentum) {
            val factor = scrollFactor()
            axisLock.filter(velocityX * factor, velocityY * factor, tmp)
            val speed = hypot(tmp[0], tmp[1])
            if (speed >= minFlingPx) {
                val capped = speed.coerceAtMost(maxFlingPx) / speed
                vx = tmp[0] * capped
                vy = tmp[1] * capped
            }
        }
        DebugLog.v("Scroll end (${if (scrollLive) "live" else "deferred"}), fling (%.0f, %.0f) px/s".format(vx, vy))
        if (scrollLive) {
            liveScroller.finish(vx, vy)
        } else {
            injector.play(planner.scrollRelease(scrollAnchor, owedX.roundToInt(), owedY.roundToInt(), vx, vy), "scroll")
        }
    }

    override fun onPinchBegin() {
        pinching = true
        refreshBounds()
        val p = cursorPoint()
        // The injected fingers land either side of the cursor, so all of them must miss our windows.
        pinchLive = settings.liveScroll && livePincher.startPoints(p).all(::clearOfOverlays)
        if (pinchLive) livePincher.begin(p) else overlays.cursorView.setPinchPreview(1f)
    }

    override fun onPinch(scale: Float) {
        if (!pinching) return
        if (pinchLive) livePincher.update(scale) else overlays.cursorView.setPinchPreview(scale)
    }

    override fun onPinchEnd(scale: Float) {
        if (!pinching) return
        pinching = false
        if (pinchLive) {
            livePincher.finish(scale)
        } else {
            overlays.cursorView.setPinchPreview(0f)
            injector.dispatchAll(planner.pinch(cursorPoint(), scale, viewConfig.scaledMinimumScalingSpan), "pinch x%.2f".format(scale))
        }
    }

    override fun onDragBegin(longPress: Boolean) {
        val p = cursorPoint()
        dragging = true
        // A long press means "pick this up"; the drag may also end right there, as a plain long press.
        dragHoldMs = if (longPress) longPressMs + LONG_PRESS_MARGIN_MS else GesturePlanner.MIN_PRESS_MS
        if (longPress) {
            overlays.cursorView.pulse(secondary = true)
            haptic()
        }
        dragLive = canGoLive(p)
        if (dragLive) {
            liveDragger.begin(p, dragHoldMs)
        } else {
            dragPath = arrayListOf(p)
            overlays.cursorView.beginDrag(cursorX, cursorY)
        }
    }

    override fun onDragMove(dx: Float, dy: Float, timeMs: Long) {
        if (!dragging) return
        acceleration.apply(dx, dy, timeMs, tmp)
        moveCursorBy(tmp[0], tmp[1])
        if (dragLive) {
            liveDragger.moveTo(cursorPoint())
        } else {
            dragPath += cursorPoint()
            overlays.cursorView.extendDrag(cursorX, cursorY)
        }
    }

    override fun onDragEnd() {
        if (!dragging) return
        dragging = false
        if (dragLive) {
            liveDragger.finish()
        } else {
            overlays.cursorView.endDrag()
            val plan = planner.drag(dragPath, dragHoldMs)
            if (plan != null) {
                injector.play(listOf(plan), "drag")
            } else {
                injector.dispatch(planner.press(dragPath.first(), dragHoldMs), "long press")
            }
        }
    }

    override fun onGestureCancelled() {
        val lastLive = when {
            scrolling && scrollLive -> liveScroller.lastDispatchMs
            dragging && dragLive -> liveDragger.lastActivityMs
            pinching && pinchLive -> livePincher.lastDispatchMs
            else -> 0L
        }
        val now = SystemClock.uptimeMillis()
        if (lastLive > 0 && now - lastLive < LIVE_CANCEL_WINDOW_MS) {
            // Android cancelled our own pad touch right after a live step. Either this device
            // cannot mix injected and real touches, or one of our injected touches landed on the
            // trackpad, which Android reports just after this. Wait to see which before falling
            // back to playing gestures on lift.
            handler.postDelayed({
                if (ownTouchOnPadAt >= now - OWN_TOUCH_SLACK_MS) {
                    DebugLog.w("Trackpad touch cancelled by our own injected touch landing on it; live gestures stay on")
                } else {
                    settings.liveScroll = false
                    DebugLog.w("Live gestures turned off: Android cancelled the trackpad touch after an injected step")
                }
            }, OWN_TOUCH_SLACK_MS)
        } else {
            DebugLog.i("Trackpad touch cancelled by the system")
        }
        reset()
    }

    /** One of our injected touches landed on the trackpad rather than the app under it. */
    fun onInjectedTouchOnPad() {
        ownTouchOnPadAt = SystemClock.uptimeMillis()
    }

    /**
     * Whether a scroll or drag starting at [p] can follow the fingers live. Live strokes cannot
     * make our own window untouchable while fingers rest on it, so gestures that start over an
     * overlay are played when the fingers lift instead.
     */
    private fun canGoLive(p: IPoint) = settings.liveScroll && clearOfOverlays(p)

    private fun clearOfOverlays(p: IPoint) = overlays.touchableWindowsAt(p.x, p.y).isEmpty()

    private inline fun click(label: String, inject: (IPoint) -> Unit) {
        overlays.cursorView.pulse(secondary = false)
        haptic()
        DebugLog.v("$label at ${cursorPoint()}")
        inject(cursorPoint())
    }

    private fun perform(action: GestureAction, label: String) {
        DebugLog.i("$label -> ${action.label}")
        haptic()
        when (action) {
            GestureAction.NONE -> Unit
            GestureAction.HIDE_TRACKPAD -> settings.visible = false
            else -> action.globalAction?.let { service.performGlobalAction(it) }
        }
    }

    private fun scrollFactor() = settings.scrollSpeed * scrollMultiplier * if (settings.naturalScroll) 1f else -1f

    private fun moveCursorBy(dx: Float, dy: Float) {
        val bounds = planner.bounds
        cursorX = (cursorX + dx).coerceIn(0f, (bounds.width - 1).toFloat())
        cursorY = (cursorY + dy).coerceIn(0f, (bounds.height - 1).toFloat())
        overlays.cursorView.moveTo(cursorX, cursorY)
    }

    private fun cursorPoint() = IPoint(cursorX.roundToInt(), cursorY.roundToInt())

    private fun haptic() {
        if (settings.haptics) overlays.trackpadView.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
    }

    private companion object {
        const val EDGE_MARGIN_DP = 24f
        const val MIN_FLING_DP_PER_S = 400f
        const val LONG_PRESS_MARGIN_MS = 150L
        const val LIVE_CANCEL_WINDOW_MS = 250L

        /** How long after a pad touch is cancelled our own touch on the pad can still show up. */
        const val OWN_TOUCH_SLACK_MS = 100L
    }
}
