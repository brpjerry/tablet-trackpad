package dev.tablettrackpad.overlay

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.ColorUtils
import dev.tablettrackpad.core.EdgeSwipe
import dev.tablettrackpad.settings.OverlayPart
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.min

/**
 * A thin touch strip along one screen edge. Swiping inward from it toggles the trackpad; a plain
 * tap is handed back to the app underneath.
 */
@SuppressLint("ViewConstructor")
class EdgeStripView(context: Context, val isLeft: Boolean) : View(context) {
    interface Callbacks {
        fun onEdgeSwipe(isLeft: Boolean)
        fun onEdgeTap(rawX: Float, rawY: Float)
    }

    var callbacks: Callbacks? = null

    var showHint = true
        set(value) {
            field = value
            invalidate()
        }

    private val fader = Fader(this, fadeOutMs = 400, fadeInMs = 150)

    /** Fades the marker out; the strip keeps taking swipes. */
    fun conceal() = fader.fadeOut()

    fun reveal() = fader.fadeIn()

    /** ARGB color of the marker bar. Its dark rim fades with it. */
    var markerColor = OverlayPart.EDGE_MARKERS.defaultArgb()
        set(value) {
            field = value
            hint.color = value
            hintOutline.color = ColorUtils.setAlphaComponent(Color.BLACK, min(255, Color.alpha(value) * 0x55 / 0x99))
            invalidate()
        }

    private val dp = resources.displayMetrics.density
    private val triggerDistance = EdgeSwipe.TRIGGER_DP * dp
    private val tapSlop = EdgeSwipe.SLOP_DP * dp

    private var downRawX = 0f
    private var downRawY = 0f
    private var downTime = 0L
    private var fired = false

    private val hint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = markerColor }
    private val hintOutline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0x55000000
        style = Paint.Style.STROKE
        strokeWidth = 1 * dp
    }
    private val bar = RectF()
    private val exclusion = Rect()

    init {
        contentDescription = "Trackpad edge swipe zone"
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // Keep the system back gesture out of the strip. Android honours at most 200 dp of
        // exclusions per edge, which is why the default zone is that tall.
        exclusion.set(0, 0, width, height)
        systemGestureExclusionRects = listOf(exclusion)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.flags and TrackpadView.FLAG_IS_ACCESSIBILITY_EVENT != 0) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downRawX = event.rawX
                downRawY = event.rawY
                downTime = event.eventTime
                fired = false
            }
            MotionEvent.ACTION_MOVE -> if (!fired) {
                val dx = event.rawX - downRawX
                val inward = if (isLeft) dx else -dx
                val dy = abs(event.rawY - downRawY)
                // Only a swipe matters here: anything else on the strip just never fires.
                if (EdgeSwipe.judge(inward, dy, triggerDistance, tapSlop) == EdgeSwipe.Verdict.SWIPE) {
                    fired = true
                    callbacks?.onEdgeSwipe(isLeft)
                }
            }
            MotionEvent.ACTION_UP -> if (!fired) {
                val still = hypot(event.rawX - downRawX, event.rawY - downRawY) < tapSlop
                if (still && event.eventTime - downTime < TAP_TIMEOUT_MS) callbacks?.onEdgeTap(event.rawX, event.rawY)
            }
        }
        return true
    }

    override fun onDraw(canvas: Canvas) {
        if (!showHint) return
        val barWidth = 4 * dp
        val inset = 2 * dp
        val left = if (isLeft) inset else width - inset - barWidth
        bar.set(left, 8 * dp, left + barWidth, height - 8 * dp)
        canvas.drawRoundRect(bar, barWidth / 2, barWidth / 2, hint)
        canvas.drawRoundRect(bar, barWidth / 2, barWidth / 2, hintOutline)
    }

    private companion object {
        const val TAP_TIMEOUT_MS = 400L
    }
}
