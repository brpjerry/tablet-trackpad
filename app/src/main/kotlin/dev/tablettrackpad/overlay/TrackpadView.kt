package dev.tablettrackpad.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import androidx.core.graphics.withClip
import dev.tablettrackpad.core.EdgeSwipe
import dev.tablettrackpad.core.TouchAction
import dev.tablettrackpad.core.TouchPoint
import dev.tablettrackpad.debug.DebugLog
import dev.tablettrackpad.settings.OverlayPart
import dev.tablettrackpad.settings.ScrollZoneSide
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/** ARGB colors of the trackpad's parts. */
data class TrackpadColors(
    val pad: Int,
    val bar: Int,
    val outline: Int,
    val icons: Int,
    val fingers: Int,
    val scrollZone: Int,
)

/**
 * The translucent pad. The strip along the top is a handle that moves the window and holds the
 * settings and hide buttons; the corner grip resizes it; everything else is trackpad surface,
 * optionally with a scroll zone along one side.
 */
class TrackpadView(context: Context) : View(context) {
    interface Callbacks {
        fun onPadTouch(action: TouchAction, changedId: Int, touches: List<TouchPoint>, timeMs: Long)

        /** Any touch on the trackpad, bar and grips included: [touching] until the last finger lifts. */
        fun onTouchActivity(touching: Boolean)

        /** One of our own injected touches landed here instead of on the app under the trackpad. */
        fun onInjectedTouch()

        /**
         * Which edge swipe zone covers screen point ([rawX], [rawY]): true for the left, false for
         * the right, null for none. The trackpad can lie over a zone, and swipes there still count.
         */
        fun edgeZoneAt(rawX: Float, rawY: Float): Boolean?

        fun onEdgeSwipe(isLeft: Boolean)
        fun onMoveBy(dx: Float, dy: Float)
        fun onMoveEnd()
        fun onResizeBy(dx: Float, dy: Float)
        fun onResizeEnd()
        fun onHideClicked()
        fun onSettingsClicked()
    }

    var callbacks: Callbacks? = null

    var colors = TrackpadColors(
        pad = OverlayPart.PAD.defaultArgb(),
        bar = OverlayPart.BAR.defaultArgb(),
        outline = OverlayPart.OUTLINE.defaultArgb(),
        icons = OverlayPart.ICONS.defaultArgb(),
        fingers = OverlayPart.FINGERS.defaultArgb(),
        scrollZone = OverlayPart.SCROLL_ZONE.defaultArgb(),
    )
        set(value) {
            field = value
            invalidate()
        }

    private val fader = Fader(this, fadeOutMs = 600, fadeInMs = 150)

    /** Faded out by [conceal]: invisible, but still in place and still taking touches. */
    val concealed get() = fader.hidden

    /** Fades the trackpad out while leaving it working, e.g. once it has been idle for a while. */
    fun conceal() = fader.fadeOut()

    /** Fades the trackpad back in after a [conceal]. */
    fun reveal() = fader.fadeIn()

    /** Fades the trackpad in from nothing, when it is shown. */
    fun appear() = fader.appear()

    /** Side of the one-finger scroll zone, or null for none. */
    var scrollZone: ScrollZoneSide? = null
        set(value) {
            field = value
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val handleHeight = 34 * dp
    private val buttonWidth = 48 * dp
    private val gripSize = 32 * dp
    private val corner = 18 * dp
    private val scrollZoneWidth = 56 * dp

    private enum class Zone { NONE, PAD, HANDLE, HIDE, SETTINGS, RESIZE, EDGE }

    private var zone = Zone.NONE
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var downX = 0f
    private var downY = 0f

    // A touch that started over an edge swipe zone, held back until it is a swipe or not.
    private val edgeTrigger = EdgeSwipe.TRIGGER_DP * dp
    private val edgeSlop = EdgeSwipe.SLOP_DP * dp
    private var edgeIsLeft = false
    private var edgeDownX = 0f
    private var edgeDownY = 0f
    private var edgeSwiped = false
    private val held = ArrayList<MotionEvent>()
    private var scrollZoneTouched = false

    private val touches = ArrayList<TouchPoint>(10)

    private val padPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val handlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1 * dp
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2 * dp
        strokeCap = Paint.Cap.ROUND
    }
    private val glyphFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val touchPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val zonePaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect = RectF()
    private val clip = Path()

    init {
        contentDescription = "Virtual trackpad"
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Our own injected gestures can land here when the cursor sits over the pad.
        if (event.flags and FLAG_IS_ACCESSIBILITY_EVENT != 0) {
            val action = event.actionMasked
            if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
                DebugLog.w("An injected touch hit the trackpad instead of the app under it")
                callbacks?.onInjectedTouch()
            }
            return true
        }
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            val edge = callbacks?.edgeZoneAt(event.rawX, event.rawY)
            if (edge != null) {
                zone = Zone.EDGE
                edgeIsLeft = edge
            } else {
                enterZone(zoneAt(event.x, event.y), event)
            }
        }
        if (zone == Zone.EDGE) {
            edgeSwipeOrHandOver(event)
        } else {
            callbacks?.onTouchActivity(action != MotionEvent.ACTION_UP && action != MotionEvent.ACTION_CANCEL)
            dispatchToZone(event)
        }
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
            zone = Zone.NONE
            scrollZoneTouched = false
            touches.clear()
            clearHeld()
            invalidate()
        }
        return true
    }

    private fun enterZone(target: Zone, down: MotionEvent) {
        zone = target
        scrollZoneTouched = target == Zone.PAD && inScrollZone(down.x, down.y)
    }

    private fun dispatchToZone(event: MotionEvent) {
        when (zone) {
            Zone.PAD -> forwardToPad(event)
            Zone.HANDLE, Zone.RESIZE -> dragFrame(event)
            Zone.HIDE, Zone.SETTINGS -> pressButton(event)
            Zone.NONE, Zone.EDGE -> Unit
        }
    }

    /**
     * A touch that started where the trackpad covers an edge swipe zone. An inward swipe toggles
     * the trackpad, as it would on the zone itself; anything else (a tap, movement along the edge
     * or outward, a second finger) was meant for the trackpad, which then gets the held events.
     */
    private fun edgeSwipeOrHandOver(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                edgeDownX = event.rawX
                edgeDownY = event.rawY
                edgeSwiped = false
                held += MotionEvent.obtain(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (edgeSwiped) return
                val dx = event.rawX - edgeDownX
                val inward = if (edgeIsLeft) dx else -dx
                val dy = abs(event.rawY - edgeDownY)
                when (EdgeSwipe.judge(inward, dy, edgeTrigger, edgeSlop)) {
                    EdgeSwipe.Verdict.SWIPE -> {
                        edgeSwiped = true
                        clearHeld()
                        callbacks?.onEdgeSwipe(edgeIsLeft)
                    }
                    EdgeSwipe.Verdict.NOT_SWIPE -> handOver(event)
                    EdgeSwipe.Verdict.UNDECIDED -> held += MotionEvent.obtain(event)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_DOWN -> if (!edgeSwiped) handOver(event)
        }
    }

    /** Plays the held events, then [event], to the part of the trackpad the touch began on. */
    private fun handOver(event: MotionEvent) {
        val down = held.firstOrNull() ?: return
        enterZone(zoneAt(down.x, down.y), down)
        callbacks?.onTouchActivity(true)
        for (e in held) dispatchToZone(e)
        clearHeld()
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_UP) callbacks?.onTouchActivity(false)
        dispatchToZone(event)
    }

    private fun clearHeld() {
        for (e in held) e.recycle()
        held.clear()
    }

    /** Whether pad point ([x], [y]) is in the scroll zone. */
    fun inScrollZone(x: Float, y: Float): Boolean {
        val side = scrollZone ?: return false
        if (y < handleHeight) return false
        val zoneWidth = scrollZoneWidth()
        return if (side == ScrollZoneSide.LEFT) x < zoneWidth else x > width - zoneWidth
    }

    /** At most a quarter of the pad, so a small pad keeps room for the pointer. */
    private fun scrollZoneWidth() = min(scrollZoneWidth, width / 4f)

    private fun zoneAt(x: Float, y: Float): Zone = when {
        y < handleHeight && x < buttonWidth -> Zone.SETTINGS
        y < handleHeight && x > width - buttonWidth -> Zone.HIDE
        y < handleHeight -> Zone.HANDLE
        x > width - gripSize && y > height - gripSize -> Zone.RESIZE
        else -> Zone.PAD
    }

    private fun forwardToPad(event: MotionEvent) {
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> TouchAction.DOWN
            MotionEvent.ACTION_POINTER_DOWN -> TouchAction.POINTER_DOWN
            MotionEvent.ACTION_MOVE -> TouchAction.MOVE
            MotionEvent.ACTION_POINTER_UP -> TouchAction.POINTER_UP
            MotionEvent.ACTION_UP -> TouchAction.UP
            MotionEvent.ACTION_CANCEL -> TouchAction.CANCEL
            else -> return
        }
        touches.clear()
        for (i in 0 until event.pointerCount) {
            touches += TouchPoint(event.getPointerId(i), event.getX(i), event.getY(i))
        }
        callbacks?.onPadTouch(action, event.getPointerId(event.actionIndex), touches, event.eventTime)
        if (action == TouchAction.POINTER_UP) touches.removeAt(event.actionIndex)
        invalidate()
    }

    private fun dragFrame(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                lastRawX = event.rawX
                lastRawY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                if (zone == Zone.HANDLE) callbacks?.onMoveBy(dx, dy) else callbacks?.onResizeBy(dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL ->
                if (zone == Zone.HANDLE) callbacks?.onMoveEnd() else callbacks?.onResizeEnd()
        }
    }

    private fun pressButton(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val still = hypot(event.x - downX, event.y - downY) < 12 * dp
                if (still && zoneAt(event.x, event.y) == zone) {
                    if (zone == Zone.HIDE) callbacks?.onHideClicked() else callbacks?.onSettingsClicked()
                }
            }
        }
    }

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        val c = colors

        rect.set(0f, 0f, w, h)
        clip.reset()
        clip.addRoundRect(rect, corner, corner, Path.Direction.CW)
        canvas.withClip(clip) {
            padPaint.color = c.pad
            drawRect(rect, padPaint)

            handlePaint.color = c.bar
            drawRect(0f, 0f, w, handleHeight, handlePaint)

            scrollZone?.let { side ->
                val zw = scrollZoneWidth()
                val left = if (side == ScrollZoneSide.LEFT) 0f else w - zw
                zonePaint.color = if (scrollZoneTouched) emphasized(c.scrollZone) else c.scrollZone
                drawRect(left, handleHeight, left + zw, h, zonePaint)
            }
        }

        glyph.color = c.icons
        glyphFill.color = c.icons
        border.color = c.outline
        touchPaint.color = c.fingers

        // Handle grip
        val cx = w / 2
        val cy = handleHeight / 2
        rect.set(cx - 22 * dp, cy - 2.5f * dp, cx + 22 * dp, cy + 2.5f * dp)
        canvas.drawRoundRect(rect, 2.5f * dp, 2.5f * dp, glyphFill)

        // Settings: three dots
        val sx = buttonWidth / 2
        for (i in -1..1) canvas.drawCircle(sx + i * 6 * dp, cy, 1.8f * dp, glyphFill)

        // Hide: a cross
        val hx = w - buttonWidth / 2
        val r = 6 * dp
        canvas.drawLine(hx - r, cy - r, hx + r, cy + r, glyph)
        canvas.drawLine(hx - r, cy + r, hx + r, cy - r, glyph)

        // Scroll zone: up and down chevrons in the middle of its strip
        scrollZone?.let { side ->
            val zw = scrollZoneWidth()
            val zx = if (side == ScrollZoneSide.LEFT) zw / 2 else w - zw / 2
            val zy = (handleHeight + h) / 2
            val c = 5 * dp
            for (dir in listOf(-1, 1)) {
                val tip = zy + dir * 14 * dp
                canvas.drawLine(zx - c, tip - dir * c, zx, tip, glyph)
                canvas.drawLine(zx + c, tip - dir * c, zx, tip, glyph)
            }
        }

        // Resize grip: diagonal lines in the corner
        val inset = 9 * dp
        for (i in 1..3) {
            val o = i * 5 * dp
            canvas.drawLine(w - inset - o, h - inset, w - inset, h - inset - o, glyph)
        }

        // Fingers currently on the pad
        for (t in touches) canvas.drawCircle(t.x, t.y, 18 * dp, touchPaint)

        rect.set(border.strokeWidth / 2, border.strokeWidth / 2, w - border.strokeWidth / 2, h - border.strokeWidth / 2)
        canvas.drawRoundRect(rect, corner, corner, border)
    }

    /** The scroll zone while a finger in it is down: more opaque, unless it is hidden entirely. */
    private fun emphasized(color: Int): Int {
        val alpha = Color.alpha(color)
        return if (alpha == 0) color else ColorUtils.setAlphaComponent(color, min(255, alpha * 2 + 30))
    }

    companion object {
        /** MotionEvent.FLAG_IS_ACCESSIBILITY_EVENT, hidden from the public SDK. */
        const val FLAG_IS_ACCESSIBILITY_EVENT = 0x800
    }
}
