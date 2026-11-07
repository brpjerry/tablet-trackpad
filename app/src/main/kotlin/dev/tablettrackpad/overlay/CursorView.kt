package dev.tablettrackpad.overlay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.animation.DecelerateInterpolator
import androidx.core.animation.doOnEnd
import androidx.core.graphics.withTranslation
import dev.tablettrackpad.settings.OverlayPart
import dev.tablettrackpad.settings.argb
import kotlin.math.hypot
import kotlin.math.min

/**
 * Full-screen, untouchable layer that draws the virtual cursor plus feedback for clicks, drags,
 * deferred scrolls and pinches.
 */
class CursorView(context: Context) : View(context) {
    private val dp = resources.displayMetrics.density

    var scale = 1f
        set(value) {
            field = value
            buildArrow()
            invalidate()
        }

    private var cursorX = 0f
    private var cursorY = 0f
    private val origin = IntArray(2)

    private val arrow = Path()
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val outline = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 1.3f * dp
        strokeJoin = Paint.Join.ROUND
    }
    private val accent = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 3 * dp
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val accentFill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }

    /** Alpha of the feedback color, which animations fade from. */
    private var accentAlpha = 255

    private val fader = Fader(this, fadeOutMs = 400, fadeInMs = 100)

    /** Opacity of the arrow, its outline and all feedback, from 0 to 1. */
    var opacity = OverlayPart.CURSOR.defaultOpacity
        set(value) {
            field = value
            applyOpacity()
        }

    private val trail = Path()
    private var dragging = false
    private var dragStartX = 0f
    private var dragStartY = 0f

    private var previewDx = 0f
    private var previewDy = 0f
    private var pinchScale = 0f

    private var pulseProgress = -1f
    private var pulseSecondary = false
    private val pulse = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 260
        interpolator = DecelerateInterpolator()
        addUpdateListener {
            pulseProgress = it.animatedValue as Float
            invalidate()
        }
        doOnEnd { pulseProgress = -1f }
    }

    init {
        buildArrow()
        applyOpacity()
    }

    private fun applyOpacity() {
        fill.color = argb(Color.WHITE, opacity)
        // The drop shadow fades with the arrow, so a see-through arrow leaves no dark smudge.
        fill.setShadowLayer(3 * dp, 0f, 1 * dp, argb(Color.BLACK, 0.4f * opacity))
        outline.color = argb(Color.BLACK, opacity)
        accent.color = argb(ACCENT, opacity)
        accentFill.color = accent.color
        accentAlpha = Color.alpha(accent.color)
        invalidate()
    }

    /** Fades the cursor out, e.g. once the trackpad has been idle for a while. */
    fun conceal() = fader.fadeOut()

    /** Brings a [conceal]ed cursor back, quickly, so it is there as soon as the pad is touched. */
    fun reveal() = fader.fadeIn()

    fun moveTo(x: Float, y: Float) {
        cursorX = x
        cursorY = y
        invalidate()
    }

    fun pulse(secondary: Boolean) {
        pulseSecondary = secondary
        pulse.cancel()
        pulse.start()
    }

    fun beginDrag(x: Float, y: Float) {
        trail.reset()
        trail.moveTo(x, y)
        dragStartX = x
        dragStartY = y
        dragging = true
        invalidate()
    }

    fun extendDrag(x: Float, y: Float) {
        if (!dragging) return
        trail.lineTo(x, y)
        invalidate()
    }

    fun endDrag() {
        dragging = false
        trail.reset()
        invalidate()
    }

    /** Shows the distance a deferred scroll will travel when the fingers lift. */
    fun setScrollPreview(dx: Float, dy: Float) {
        previewDx = dx
        previewDy = dy
        invalidate()
    }

    /** 0 hides the pinch preview. */
    fun setPinchPreview(scale: Float) {
        pinchScale = scale
        invalidate()
    }

    fun clearFeedback() {
        endDrag()
        setScrollPreview(0f, 0f)
        setPinchPreview(0f)
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        // The window covers the display, but measure where it really landed so the drawn tip
        // matches the screen coordinates gestures are injected at.
        getLocationOnScreen(origin)
    }

    override fun onDraw(canvas: Canvas) = canvas.withTranslation(-origin[0].toFloat(), -origin[1].toFloat()) {
        if (dragging) {
            drawPath(trail, accent)
            drawCircle(dragStartX, dragStartY, 5 * dp, accentFill)
        }
        if (previewDx != 0f || previewDy != 0f) drawScrollPreview(this)
        if (pinchScale > 0f) {
            val r = (24 * dp * pinchScale).coerceIn(6 * dp, 160 * dp)
            drawCircle(cursorX - r, cursorY, 6 * dp, accentFill)
            drawCircle(cursorX + r, cursorY, 6 * dp, accentFill)
        }
        if (pulseProgress >= 0f) {
            val p = pulseProgress
            accent.alpha = ((1f - p) * accentAlpha).toInt()
            val r = (6 + 22 * p) * dp
            drawCircle(cursorX, cursorY, r, accent)
            if (pulseSecondary) drawCircle(cursorX, cursorY, r * 0.6f, accent)
            accent.alpha = accentAlpha
        }

        translate(cursorX, cursorY)
        drawPath(arrow, fill)
        drawPath(arrow, outline)
    }

    private fun drawScrollPreview(canvas: Canvas) {
        val length = hypot(previewDx, previewDy)
        val shown = min(length, 120 * dp) / length
        val ex = cursorX + previewDx * shown
        val ey = cursorY + previewDy * shown
        accent.alpha = accentAlpha * 2 / 3
        canvas.drawLine(cursorX, cursorY, ex, ey, accent)
        accent.alpha = accentAlpha
        canvas.drawCircle(ex, ey, 5 * dp, accentFill)
    }

    private fun buildArrow() {
        val s = dp * scale * 1.15f
        arrow.reset()
        arrow.moveTo(0f, 0f)
        arrow.lineTo(0f, 16.5f * s)
        arrow.lineTo(4f * s, 12.8f * s)
        arrow.lineTo(6.8f * s, 19.2f * s)
        arrow.lineTo(9.4f * s, 18.1f * s)
        arrow.lineTo(6.7f * s, 11.9f * s)
        arrow.lineTo(12f * s, 11.9f * s)
        arrow.close()
    }

    private companion object {
        const val ACCENT = 0x3D8BFF
    }
}
