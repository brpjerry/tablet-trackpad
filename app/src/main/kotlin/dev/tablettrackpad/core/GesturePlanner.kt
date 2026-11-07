package dev.tablettrackpad.core

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * A screen point in whole pixels. Injected strokes only use whole pixels: the platform matches a
 * continued stroke to its predecessor by exact float equality, and integer coordinates survive
 * the interpolation along a path unchanged.
 */
data class IPoint(val x: Int, val y: Int)

/**
 * The display, [width] by [height] pixels, and how far the visible system bars and cutouts reach
 * in from each edge. Taps may land anywhere; swipes and pinches start clear of the bars, where a
 * touch would reach system UI instead of the app.
 */
data class Bounds(
    val width: Int,
    val height: Int,
    val insetLeft: Int = 0,
    val insetTop: Int = 0,
    val insetRight: Int = 0,
    val insetBottom: Int = 0,
)

/** Part of one finger's stroke. A single point is a press held in place for [durationMs]. */
data class Segment(val points: List<IPoint>, val durationMs: Long) {
    init {
        require(points.isNotEmpty()) { "Segment needs at least one point" }
        require(durationMs > 0) { "Segment duration must be positive" }
    }

    val start get() = points.first()
    val end get() = points.last()
}

/** One finger's press-move-release, played as consecutive continued strokes. */
data class StrokePlan(val segments: List<Segment>) {
    init {
        require(segments.isNotEmpty())
        for (i in 1 until segments.size) {
            require(segments[i].start == segments[i - 1].end) { "Segment $i does not start where ${i - 1} ended" }
        }
    }

    val start get() = segments.first().start
}

/** A stroke inside a single gesture; strokes may overlap in time (pinch) or follow each other (double tap). */
data class TimedStroke(val points: List<IPoint>, val startMs: Long, val durationMs: Long)

/** A pinch centered on [center] whose fingers spread along ([dirX], [dirY]) up to [reach] apart. */
data class PinchPlacement(val center: IPoint, val dirX: Float, val dirY: Float, val reach: Int)

/**
 * Builds the touch gestures that stand in for mouse actions at the cursor.
 *
 * Android has no way for an accessibility service to send mouse events, so a scroll is played as
 * a finger drag. Scroll containers ignore the first [touchSlopPx] of a drag, so every drag here
 * travels that much further than the scroll it represents.
 */
class GesturePlanner(
    private val touchSlopPx: Int,
    /** Swipes stay this far inside the system bars so they never trigger system edge gestures. */
    private val edgeMarginPx: Int,
) {
    var bounds = Bounds(1, 1)

    fun tap(at: IPoint) = listOf(TimedStroke(listOf(clamp(at)), 0, TAP_MS))

    fun doubleTap(at: IPoint): List<TimedStroke> {
        val p = clamp(at)
        return listOf(TimedStroke(listOf(p), 0, TAP_MS), TimedStroke(listOf(p), DOUBLE_TAP_GAP_MS, TAP_MS))
    }

    fun press(at: IPoint, durationMs: Long) = listOf(TimedStroke(listOf(clamp(at)), 0, durationMs))

    /** Moves [p] inside the swipe area. */
    fun swipeAnchor(p: IPoint) = IPoint(fitAxis(p.x, areaLeft(), areaRight()), fitAxis(p.y, areaTop(), areaBottom()))

    data class LiveStep(val from: IPoint, val to: IPoint, val consumedX: Int, val consumedY: Int)

    /**
     * The first, open-ended drag of a live scroll: scrolls content under [anchor] by up to
     * ([dx], [dy]) pixels after getting past the touch slop, and stays down for later steps to
     * continue. Near the edge it is moving toward, the press moves back from [anchor] to leave up
     * to [LIVE_HEADROOM_SLOPS] touch slops of travel beyond this step for the later ones, as far
     * as the swipe area has room; without that, every step would have to press again.
     */
    fun liveScrollStep(anchor: IPoint, dx: Int, dy: Int): LiveStep? {
        if (dx == 0 && dy == 0) return null
        val cx = dx.coerceIn(-maxChunk(areaWidth()), maxChunk(areaWidth()))
        val cy = dy.coerceIn(-maxChunk(areaHeight()), maxChunk(areaHeight()))
        val offset = IPoint(compensate(cx), compensate(cy))
        val headroom = touchSlopPx * LIVE_HEADROOM_SLOPS
        val reach = IPoint(offset.x + offset.x.sign * headroom, offset.y + offset.y.sign * headroom)
        // The headroom is a wish: where the area is too small for it, the stroke itself still fits.
        val from = fit(fit(anchor, listOf(reach)), listOf(offset))
        return LiveStep(from, IPoint(from.x + offset.x, from.y + offset.y), cx, cy)
    }

    /** Where an open live stroke at [from] moves next, or null if that would leave the swipe area. */
    fun continueStep(from: IPoint, dx: Int, dy: Int): IPoint? {
        val to = IPoint(from.x + dx, from.y + dy)
        return if (insideMargins(to)) to else null
    }

    /**
     * Ends an open live stroke at [from]: plays the remaining ([dx], [dy]), then either flings at
     * ([velocityX], [velocityY]) px/s or rests so the content does not fling. Null if that would
     * leave the swipe area.
     */
    fun releaseOpenStroke(from: IPoint, dx: Int, dy: Int, velocityX: Float, velocityY: Float): StrokePlan? {
        val flingX = clampFling(velocityX * FLING_MS / 1000f, areaWidth())
        val flingY = clampFling(velocityY * FLING_MS / 1000f, areaHeight())
        val segments = ArrayList<Segment>(2)
        var at = from
        if (dx != 0 || dy != 0) {
            val to = IPoint(at.x + dx, at.y + dy)
            val moveMs = (hypot(dx.toFloat(), dy.toFloat()) / RELEASE_SPEED_PX_PER_MS).toLong()
                .coerceIn(1, RELEASE_MAX_MS)
            segments += Segment(listOf(at, to), moveMs)
            at = to
        }
        segments += if (flingX != 0 || flingY != 0) {
            Segment(listOf(at, IPoint(at.x + flingX, at.y + flingY)), FLING_MS)
        } else {
            settleSegment(at)
        }
        return if (segments.all { seg -> seg.points.all(::insideMargins) }) StrokePlan(segments) else null
    }

    /** Lifts an open stroke at [at] without a fling. */
    fun settle(at: IPoint) = StrokePlan(listOf(settleSegment(at)))

    /**
     * Rests the finger at [at] before it lifts, so the content does not fling. A held injected
     * finger reports no movement at all, which leaves Android's velocity tracker on the last
     * drag speed, so the finger wiggles by a pixel and back to report that it has stopped.
     */
    fun settleSegment(at: IPoint): Segment {
        // Toward the middle of the swipe area, so it never crosses a margin.
        val dx = if (at.x < (areaLeft() + areaRight()) / 2) 1 else -1
        val dy = if (at.y < (areaTop() + areaBottom()) / 2) 1 else -1
        return Segment(listOf(at, IPoint(at.x + dx, at.y + dy), at), SETTLE_MS)
    }

    /**
     * Scrolls content under [anchor] by ([dx], [dy]) pixels with drags that release cleanly, then
     * flings at ([velocityX], [velocityY]) px/s if that is non-zero. Long scrolls are split into
     * several drags so each one fits on screen.
     */
    fun scrollRelease(anchor: IPoint, dx: Int, dy: Int, velocityX: Float, velocityY: Float): List<StrokePlan> {
        val flingX = clampFling(velocityX * FLING_MS / 1000f, areaWidth())
        val flingY = clampFling(velocityY * FLING_MS / 1000f, areaHeight())
        val hasFling = flingX != 0 || flingY != 0
        if (dx == 0 && dy == 0 && !hasFling) return emptyList()

        val chunks = max(
            ceil(abs(dx).toDouble() / maxChunk(areaWidth())).toInt(),
            ceil(abs(dy).toDouble() / maxChunk(areaHeight())).toInt(),
        ).coerceAtLeast(1)
        val plans = ArrayList<StrokePlan>(chunks)
        var doneX = 0
        var doneY = 0
        for (i in 1..chunks) {
            // Spread the distance evenly; integer division puts the remainder in later chunks.
            val chunkX = dx * i / chunks - doneX
            val chunkY = dy * i / chunks - doneY
            doneX += chunkX
            doneY += chunkY
            val last = i == chunks
            val fling = last && hasFling
            // A fling with nothing left to scroll still has to get past the slop first.
            val moveX = if (chunkX == 0 && chunkY == 0 && fling) flingX.sign else chunkX
            val moveY = if (chunkX == 0 && chunkY == 0 && fling) flingY.sign else chunkY
            val first = IPoint(compensate(moveX), compensate(moveY))
            val second = if (fling) IPoint(first.x + flingX, first.y + flingY) else first
            val a = fit(anchor, listOf(first, second))
            val b = IPoint(a.x + first.x, a.y + first.y)
            val c = IPoint(a.x + second.x, a.y + second.y)
            val travel = hypot(first.x.toFloat(), first.y.toFloat())
            val moveMs = (travel / RELEASE_SPEED_PX_PER_MS).toLong().coerceIn(RELEASE_MIN_MS, RELEASE_MAX_MS)
            val end = if (fling) Segment(listOf(b, c), FLING_MS) else settleSegment(b)
            plans += StrokePlan(listOf(Segment(listOf(a, b), moveMs), end))
        }
        return plans
    }

    /**
     * Replays a drag along [path]. [holdMs] presses in place first, long enough for a long-press
     * when the user rested before moving, so launcher icons and text selections pick up.
     */
    fun drag(path: List<IPoint>, holdMs: Long): StrokePlan? {
        val points = ArrayList<IPoint>(path.size)
        for (raw in path) {
            val p = clamp(raw)
            val prev = points.lastOrNull()
            if (prev == null || hypot((p.x - prev.x).toFloat(), (p.y - prev.y).toFloat()) >= DRAG_POINT_SPACING_PX) {
                points += p
            }
        }
        val end = clamp(path.lastOrNull() ?: return null)
        if (points.last() != end) points += end
        if (points.size < 2) return null
        var length = 0f
        for (i in 1 until points.size) {
            length += hypot((points[i].x - points[i - 1].x).toFloat(), (points[i].y - points[i - 1].y).toFloat())
        }
        val moveMs = (length / DRAG_SPEED_PX_PER_MS).toLong().coerceIn(DRAG_MIN_MS, DRAG_MAX_MS)
        return StrokePlan(
            listOf(
                Segment(listOf(points.first()), holdMs.coerceAtLeast(MIN_PRESS_MS)),
                Segment(points, moveMs),
                settleSegment(points.last()),
            ),
        )
    }

    /**
     * The finger spacings an injected pinch can use: wide enough for Android's ScaleGestureDetector,
     * which ignores pinches narrower than [minSpanPx], and at most [MAX_PINCH_RANGE] times that so
     * the fingers stay close to the cursor. Null if the screen is too small.
     */
    fun pinchSpans(minSpanPx: Int): Pair<Int, Int>? {
        val lo = (minSpanPx * PINCH_SPAN_HEADROOM).roundToInt()
        val hi = min(lo * MAX_PINCH_RANGE, max(areaWidth(), areaHeight()) - 2 * margin() - 2)
        return if (hi > lo) lo to hi else null
    }

    /**
     * Places a pinch with finger spacings from [lo] up to [hi] around [cursor]. The fingers spread
     * horizontally, vertically or diagonally, whichever leaves the most room, so the pinch stays
     * centered on the cursor and inside the app under it. Only near a corner, where no direction
     * has room for [MIN_PINCH_RANGE] times [lo], does the center move inward, along a direction
     * the swipe area is big enough for.
     */
    fun pinchPlacement(cursor: IPoint, lo: Int, hi: Int): PinchPlacement {
        val c = fit(cursor, emptyList())
        val roomX = min(c.x - areaLeft(), areaRight() - c.x).toFloat()
        val roomY = min(c.y - areaTop(), areaBottom() - c.y).toFloat()
        // The widest spacing up to hi that fits along each direction; the first wins a tie.
        val options = listOf(
            PinchPlacement(c, 1f, 0f, min(hi, (2 * roomX).toInt())),
            PinchPlacement(c, 0f, 1f, min(hi, (2 * roomY).toInt())),
            PinchPlacement(c, DIAGONAL, DIAGONAL, min(hi, (2 * min(roomX, roomY) / DIAGONAL).toInt())),
        )
        val best = options.maxBy { it.reach }
        val needed = min(hi, (lo * MIN_PINCH_RANGE).roundToInt())
        if (best.reach >= needed) return best
        // A direction that has room around the cursor may have none anywhere on a small screen.
        // The long side always has: hi is capped by it.
        val roomy = options.filter { fitsArea(it, needed) }.maxByOrNull { it.reach }
            ?: options[if (areaWidth() >= areaHeight()) 0 else 1]
        val (a, b) = pinchFingers(PinchPlacement(IPoint(0, 0), roomy.dirX, roomy.dirY, needed), needed)
        return PinchPlacement(fit(c, listOf(a, b)), roomy.dirX, roomy.dirY, needed)
    }

    /** Whether a pinch [span] apart along [placement]'s direction fits in the swipe area at all. */
    private fun fitsArea(placement: PinchPlacement, span: Int) =
        (span * placement.dirX).roundToInt() <= areaRight() - areaLeft() &&
            (span * placement.dirY).roundToInt() <= areaBottom() - areaTop()

    /** The two fingers of a pinch [span] apart, at [placement]. */
    fun pinchFingers(placement: PinchPlacement, span: Int): Pair<IPoint, IPoint> {
        val c = placement.center
        // Round the whole offset, then split it: horizontal and vertical spacings stay exact, and
        // neither finger reaches further than half of it, which the placement made room for.
        val dx = (span * placement.dirX).roundToInt()
        val dy = (span * placement.dirY).roundToInt()
        val ax = dx / 2
        val ay = dy / 2
        val bx = dx - ax
        val by = dy - ay
        return IPoint(c.x - ax, c.y - ay) to IPoint(c.x + bx, c.y + by)
    }

    /**
     * Two-finger pinches around [center] that together change the finger spacing by [scale]. Each
     * one changes it by at most [MAX_PINCH_STEP], which keeps the fingers near the cursor and
     * inside the app being zoomed; large zooms take several pinches in a row.
     */
    fun pinch(center: IPoint, scale: Float, minSpanPx: Int): List<List<TimedStroke>> {
        if (abs(scale - 1f) < MIN_PINCH_CHANGE) return emptyList()
        val (lo, hi) = pinchSpans(minSpanPx) ?: return emptyList()
        val placement = pinchPlacement(center, lo, hi)
        val maxStep = min(MAX_PINCH_STEP, placement.reach.toFloat() / lo)
        val count = ceil(abs(ln(scale)) / ln(maxStep)).toInt().coerceIn(1, MAX_PINCHES)
        val step = scale.pow(1f / count).coerceIn(1f / maxStep, maxStep)
        val from = if (step > 1f) lo else (lo / step).roundToInt()
        val to = if (step > 1f) (lo * step).roundToInt() else lo
        val (a0, b0) = pinchFingers(placement, from)
        val (a1, b1) = pinchFingers(placement, to)
        val strokes = listOf(
            TimedStroke(listOf(a0, a1), 0, PINCH_MS),
            TimedStroke(listOf(b0, b1), 0, PINCH_MS),
        )
        return List(count) { strokes }
    }

    /**
     * Shifts [anchor] as little as possible so that it and anchor + every offset lie inside the
     * swipe area.
     */
    fun fit(anchor: IPoint, offsets: List<IPoint>): IPoint {
        var minX = 0
        var maxX = 0
        var minY = 0
        var maxY = 0
        for (o in offsets) {
            minX = min(minX, o.x)
            maxX = max(maxX, o.x)
            minY = min(minY, o.y)
            maxY = max(maxY, o.y)
        }
        return IPoint(
            fitAxis(anchor.x, areaLeft() - minX, areaRight() - maxX),
            fitAxis(anchor.y, areaTop() - minY, areaBottom() - maxY),
        )
    }

    private fun insideMargins(p: IPoint) = p.x in areaLeft()..areaRight() && p.y in areaTop()..areaBottom()

    private fun fitAxis(value: Int, lo: Int, hi: Int) = if (lo > hi) lo else value.coerceIn(lo, hi)

    private fun compensate(d: Int) = if (d == 0) 0 else d + d.sign * touchSlopPx

    // The swipe area: the display inside the visible system bars, less the edge margin.
    private fun areaWidth() = (bounds.width - bounds.insetLeft - bounds.insetRight).coerceAtLeast(1)
    private fun areaHeight() = (bounds.height - bounds.insetTop - bounds.insetBottom).coerceAtLeast(1)
    private fun areaLeft() = bounds.insetLeft + margin()
    private fun areaTop() = bounds.insetTop + margin()
    private fun areaRight() = bounds.insetLeft + areaWidth() - 1 - margin()
    private fun areaBottom() = bounds.insetTop + areaHeight() - 1 - margin()

    private fun margin() = min(edgeMarginPx, min(areaWidth(), areaHeight()) / 4)

    /** Largest scroll one drag can carry along an axis of [size] pixels. */
    private fun maxChunk(size: Int) =
        (((size - 2 * margin()) * CHUNK_FRACTION).toInt() - touchSlopPx).coerceAtLeast(1)

    private fun clampFling(distance: Float, size: Int): Int {
        val cap = (size - 2 * margin()) * FLING_FRACTION
        return distance.coerceIn(-cap, cap).roundToInt()
    }

    private fun clamp(p: IPoint) = IPoint(
        p.x.coerceIn(0, (bounds.width - 1).coerceAtLeast(0)),
        p.y.coerceIn(0, (bounds.height - 1).coerceAtLeast(0)),
    )

    companion object {
        const val TAP_MS = 16L
        const val DOUBLE_TAP_GAP_MS = 90L

        /** Longer than the 100 ms window Android's velocity tracker looks back over. */
        const val SETTLE_MS = 150L
        const val MIN_PRESS_MS = 40L
        const val FLING_MS = 64L
        const val PINCH_MS = 300L
        const val RELEASE_SPEED_PX_PER_MS = 1.5f
        const val RELEASE_MIN_MS = 40L
        const val RELEASE_MAX_MS = 600L
        const val DRAG_SPEED_PX_PER_MS = 1.2f
        const val DRAG_MIN_MS = 150L
        const val DRAG_MAX_MS = 3000L
        const val DRAG_POINT_SPACING_PX = 3f
        const val CHUNK_FRACTION = 0.6f
        const val LIVE_HEADROOM_SLOPS = 16
        const val FLING_FRACTION = 0.35f
        const val PINCH_SPAN_HEADROOM = 1.15f
        const val MAX_PINCH_RANGE = 3
        const val MIN_PINCH_RANGE = 2f
        const val DIAGONAL = 0.70710677f
        const val MAX_PINCH_STEP = 2f
        const val MAX_PINCHES = 4
        const val MIN_PINCH_CHANGE = 0.03f
    }
}
