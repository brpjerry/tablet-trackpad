package dev.tablettrackpad.overlay

import android.content.Context
import android.graphics.PixelFormat
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.view.WindowManager
import android.view.WindowManager.LayoutParams
import dev.tablettrackpad.core.Bounds
import dev.tablettrackpad.debug.DebugLog
import dev.tablettrackpad.settings.OverlayPart
import dev.tablettrackpad.settings.TrackpadSettings
import java.util.EnumMap
import kotlin.math.roundToInt

/**
 * Owns the accessibility overlay windows: the cursor layer, the trackpad and the two edge strips.
 * All coordinates are display pixels; every window is placed with absolute x/y from the top-left.
 */
class OverlayManager(context: Context, private val settings: TrackpadSettings) : PassthroughWindows {
    enum class Window { TRACKPAD, LEFT_EDGE, RIGHT_EDGE }

    private val wm = context.getSystemService(WindowManager::class.java)
    private val density = context.resources.displayMetrics.density

    val cursorView = CursorView(context)
    val trackpadView = TrackpadView(context)
    val leftEdge = EdgeStripView(context, isLeft = true)
    val rightEdge = EdgeStripView(context, isLeft = false)

    private val cursorParams = overlayParams("Cursor", touchable = false)
    private val trackpadParams = overlayParams("Trackpad", touchable = true)
    private val leftParams = overlayParams("LeftEdge", touchable = true)
    private val rightParams = overlayParams("RightEdge", touchable = true)

    private val attached = HashSet<View>()
    private val passthrough = EnumMap<Window, Int>(Window::class.java)

    var trackpadShown = false
        private set

    /** Called when an app enters or leaves fullscreen content. */
    var onFullscreenChanged: (Boolean) -> Unit = {}

    /** Watches for fullscreen content while started. */
    val fullscreenWatcher = FullscreenWatcher(context) { onFullscreenChanged(it) }

    /** Whether an app is showing fullscreen content, while [fullscreenWatcher] runs. */
    val fullscreen get() = fullscreenWatcher.fullscreen

    init {
        applyAppearance()
    }

    /** Fades the edge zone markers out or back in; the zones keep working either way. */
    fun setEdgeMarkersHidden(hidden: Boolean) {
        for (edge in listOf(leftEdge, rightEdge)) if (hidden) edge.conceal() else edge.reveal()
    }

    /** Applies the colors and opacities from the settings to every overlay. */
    fun applyAppearance() {
        trackpadView.colors = TrackpadColors(
            pad = settings.argb(OverlayPart.PAD),
            bar = settings.argb(OverlayPart.BAR),
            outline = settings.argb(OverlayPart.OUTLINE),
            icons = settings.argb(OverlayPart.ICONS),
            fingers = settings.argb(OverlayPart.FINGERS),
            scrollZone = settings.argb(OverlayPart.SCROLL_ZONE),
        )
        cursorView.opacity = settings.opacity(OverlayPart.CURSOR)
        val marker = settings.argb(OverlayPart.EDGE_MARKERS)
        leftEdge.markerColor = marker
        rightEdge.markerColor = marker
    }

    /**
     * The display and the system bars and cutouts showing on it now. Our overlays get no bar
     * insets, but the current window metrics report those of the app underneath, which only
     * include bars that are visible.
     */
    fun displayBounds(): Bounds {
        val b = wm.maximumWindowMetrics.bounds
        val bars = wm.currentWindowMetrics.windowInsets
            .getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
        return Bounds(b.width(), b.height(), bars.left, bars.top, bars.right, bars.bottom)
    }

    fun showTrackpad() {
        trackpadShown = true
        layoutTrackpad()
        // Added after the trackpad so the cursor draws on top of it.
        layoutCursor()
        trackpadView.appear()
    }

    fun hideTrackpad() {
        trackpadShown = false
        passthrough.remove(Window.TRACKPAD)
        detach(trackpadView)
        detach(cursorView)
    }

    /** Re-applies every window's size and position, e.g. after rotation or a settings change. */
    fun relayout() {
        layoutEdges()
        if (trackpadShown) {
            layoutTrackpad()
            layoutCursor()
        }
    }

    fun layoutTrackpad() {
        val display = displayBounds()
        val (w, h) = trackpadSize(display)
        trackpadParams.width = w
        trackpadParams.height = h
        trackpadParams.x = (settings.positionX.coerceIn(0f, 1f) * (display.width - w)).roundToInt()
        trackpadParams.y = (settings.positionY.coerceIn(0f, 1f) * (display.height - h)).roundToInt()
        applyTouchable(Window.TRACKPAD)
        attachOrUpdate(trackpadView, trackpadParams)
    }

    fun moveTrackpadBy(dx: Float, dy: Float) {
        val display = displayBounds()
        trackpadParams.x = (trackpadParams.x + dx.roundToInt()).coerceIn(0, (display.width - trackpadParams.width).coerceAtLeast(0))
        trackpadParams.y = (trackpadParams.y + dy.roundToInt()).coerceIn(0, (display.height - trackpadParams.height).coerceAtLeast(0))
        attachOrUpdate(trackpadView, trackpadParams)
    }

    fun commitTrackpadPosition() {
        val display = displayBounds()
        settings.setPosition(
            fraction(trackpadParams.x, display.width - trackpadParams.width),
            fraction(trackpadParams.y, display.height - trackpadParams.height),
        )
    }

    fun resizeTrackpadBy(dx: Float, dy: Float) {
        val display = displayBounds()
        trackpadParams.width = (trackpadParams.width + dx.roundToInt())
            .coerceIn(minWidthPx(), (display.width - trackpadParams.x).coerceAtLeast(minWidthPx()))
        trackpadParams.height = (trackpadParams.height + dy.roundToInt())
            .coerceIn(minHeightPx(), (display.height - trackpadParams.y).coerceAtLeast(minHeightPx()))
        attachOrUpdate(trackpadView, trackpadParams)
    }

    fun commitTrackpadSize() {
        // The stored position is a fraction of the free space, which the new size changes.
        val display = displayBounds()
        settings.setLayout(
            trackpadParams.width / density,
            trackpadParams.height / density,
            fraction(trackpadParams.x, display.width - trackpadParams.width),
            fraction(trackpadParams.y, display.height - trackpadParams.height),
        )
    }

    fun layoutEdges() {
        val display = displayBounds()
        val stripWidth = (settings.edgeWidthDp * density).roundToInt().coerceAtLeast(1)
        val stripHeight = (settings.edgeZoneHeightDp * density).roundToInt().coerceIn(1, display.height)
        val y = (settings.edgeZonePosition.coerceIn(0f, 1f) * (display.height - stripHeight)).roundToInt()
        for ((view, params, enabled) in listOf(
            Triple(leftEdge, leftParams, settings.edgeLeft),
            Triple(rightEdge, rightParams, settings.edgeRight),
        )) {
            if (!enabled) {
                detach(view)
                continue
            }
            params.width = stripWidth
            params.height = stripHeight
            params.x = if (view.isLeft) 0 else display.width - stripWidth
            params.y = y
            view.showHint = settings.edgeHint
            applyTouchable(if (view.isLeft) Window.LEFT_EDGE else Window.RIGHT_EDGE)
            attachOrUpdate(view, params)
        }
    }

    private fun layoutCursor() {
        val display = displayBounds()
        cursorParams.width = display.width
        cursorParams.height = display.height
        cursorParams.x = 0
        cursorParams.y = 0
        attachOrUpdate(cursorView, cursorParams)
    }

    override fun touchableWindowsAt(x: Int, y: Int): List<Window> {
        val hits = ArrayList<Window>(1)
        if (trackpadShown && trackpadView in attached && trackpadParams.contains(x, y)) hits += Window.TRACKPAD
        if (leftEdge in attached && leftParams.contains(x, y)) hits += Window.LEFT_EDGE
        if (rightEdge in attached && rightParams.contains(x, y)) hits += Window.RIGHT_EDGE
        return hits
    }

    /** Which edge strip covers screen point ([x], [y]): true for the left, false for the right, null for none. */
    fun edgeZoneAt(x: Float, y: Float): Boolean? = when {
        leftEdge in attached && leftParams.contains(x.toInt(), y.toInt()) -> true
        rightEdge in attached && rightParams.contains(x.toInt(), y.toInt()) -> false
        else -> null
    }

    override fun acquirePassthrough(window: Window) {
        val count = passthrough[window] ?: 0
        passthrough[window] = count + 1
        if (count == 0) refresh(window)
    }

    override fun releasePassthrough(window: Window) {
        val count = passthrough[window] ?: return
        if (count <= 1) passthrough.remove(window) else passthrough[window] = count - 1
        if (count <= 1) refresh(window)
    }

    fun destroy() {
        fullscreenWatcher.stop()
        for (view in attached.toList()) detach(view)
        passthrough.clear()
        trackpadShown = false
    }

    private fun refresh(window: Window) {
        applyTouchable(window)
        when (window) {
            Window.TRACKPAD -> if (trackpadView in attached) attachOrUpdate(trackpadView, trackpadParams)
            Window.LEFT_EDGE -> if (leftEdge in attached) attachOrUpdate(leftEdge, leftParams)
            Window.RIGHT_EDGE -> if (rightEdge in attached) attachOrUpdate(rightEdge, rightParams)
        }
    }

    private fun applyTouchable(window: Window) {
        val params = when (window) {
            Window.TRACKPAD -> trackpadParams
            Window.LEFT_EDGE -> leftParams
            Window.RIGHT_EDGE -> rightParams
        }
        params.flags = if (passthrough.containsKey(window)) {
            params.flags or LayoutParams.FLAG_NOT_TOUCHABLE
        } else {
            params.flags and LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        }
    }

    private fun trackpadSize(display: Bounds): Pair<Int, Int> {
        val w = (settings.widthDp * density).roundToInt().coerceIn(minWidthPx().coerceAtMost(display.width), display.width)
        val h = (settings.heightDp * density).roundToInt().coerceIn(minHeightPx().coerceAtMost(display.height), display.height)
        return w to h
    }

    private fun fraction(offset: Int, free: Int) = if (free > 0) offset.toFloat() / free else 0.5f

    private fun minWidthPx() = (MIN_WIDTH_DP * density).roundToInt()

    private fun minHeightPx() = (MIN_HEIGHT_DP * density).roundToInt()

    private fun attachOrUpdate(view: View, params: LayoutParams) {
        try {
            if (view in attached) {
                wm.updateViewLayout(view, params)
            } else {
                val start = SystemClock.uptimeMillis()
                wm.addView(view, params)
                attached += view
                DebugLog.v("Added ${params.title} in ${SystemClock.uptimeMillis() - start} ms")
            }
        } catch (e: RuntimeException) {
            // BadTokenException when the service is being torn down, IllegalArgumentException
            // when the view was detached behind our back.
            DebugLog.w("Overlay ${params.title} failed: $e")
            attached -= view
        }
    }

    private fun detach(view: View) {
        if (!attached.remove(view)) return
        try {
            wm.removeViewImmediate(view)
        } catch (e: RuntimeException) {
            DebugLog.w("Removing overlay failed: $e")
        }
    }

    private fun overlayParams(name: String, touchable: Boolean) = LayoutParams(
        LayoutParams.WRAP_CONTENT,
        LayoutParams.WRAP_CONTENT,
        LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        LayoutParams.FLAG_NOT_FOCUSABLE or
            LayoutParams.FLAG_NOT_TOUCH_MODAL or
            LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            (if (touchable) 0 else LayoutParams.FLAG_NOT_TOUCHABLE),
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        layoutInDisplayCutoutMode = LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        fitInsetsTypes = 0
        title = "TabletTrackpad:$name"
    }

    private fun LayoutParams.contains(x: Int, y: Int) =
        x >= this.x && x < this.x + width && y >= this.y && y < this.y + height

    private companion object {
        const val MIN_WIDTH_DP = 200f
        const val MIN_HEIGHT_DP = 140f
    }
}
