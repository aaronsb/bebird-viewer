// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.annotate

import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Picking and moving marks on a [w] × [h] frame, for the Move tool. A mark is hit where it is
 * drawn: near a stroke (the outline of a box or ellipse, not its inside), or inside a text
 * label's box; distances are in frame pixels, measured on the same pieces the renderer draws.
 * Pure.
 */
class Picker(private val renderer: AnnotationRenderer, private val w: Int, private val h: Int) {
    /** A rectangle in image coordinates (0..1). */
    data class Bounds(val left: Float, val top: Float, val right: Float, val bottom: Float)

    /**
     * The index of the mark at [p] within [tolerance] frame pixels of what is drawn, the topmost
     * (most recent) one where marks overlap; null on empty space.
     */
    fun pick(marks: List<Mark>, p: Pt, tolerance: Double): Int? {
        val x = p.x * w.toDouble()
        val y = p.y * h.toDouble()
        return marks.indices.reversed().firstOrNull { hits(marks[it], x, y, tolerance) }
    }

    private fun hits(m: Mark, x: Double, y: Double, tolerance: Double): Boolean {
        if (m is Mark.Text) {
            val r = renderer.textBounds(m, w, h)
            return x >= r.left - tolerance && x < r.right + tolerance && y >= r.top - tolerance && y < r.bottom + tolerance
        }
        val reach = tolerance + AnnotationRenderer.half(w)
        return AnnotationRenderer.segments(m, w, h).any { distance(x, y, it) <= reach }
    }

    /**
     * What [m] covers as drawn, in image coordinates: every stroke piece (arrowheads included)
     * widened by half a stroke and the black edge, or a text label's drawn box. The highlight
     * frames this, and moves keep it within the frame.
     */
    fun bounds(m: Mark): Bounds {
        if (m is Mark.Text) {
            val r = renderer.textBounds(m, w, h)
            return Bounds(r.left.toFloat() / w, r.top.toFloat() / h, r.right.toFloat() / w, r.bottom.toFloat() / h)
        }
        val segs = AnnotationRenderer.segments(m, w, h)
        val pad = AnnotationRenderer.half(w) + AnnotationRenderer.edge(w)
        val left = segs.minOf { min(it.x0, it.x1) } - pad
        val top = segs.minOf { min(it.y0, it.y1) } - pad
        val right = segs.maxOf { max(it.x0, it.x1) } + pad
        val bottom = segs.maxOf { max(it.y0, it.y1) } + pad
        return Bounds((left / w).toFloat(), (top / h).toFloat(), (right / w).toFloat(), (bottom / h).toFloat())
    }

    /**
     * How far [m] may move for a drag of ([dx], [dy]) image units, in whole frame pixels (so the
     * screen can slide the mark's own pixels and the file gets exactly those): held within the
     * frame by [clamp], rounded towards zero so it never passes the limit. [b] is [m]'s bounds,
     * if already known (a drag asks on every movement).
     */
    fun offsetPx(m: Mark, dx: Float, dy: Float, b: Bounds = bounds(m)): Pair<Int, Int> {
        return (clamp(dx, b.left, b.right) * w).toInt() to (clamp(dy, b.top, b.bottom) * h).toInt()
    }

    /**
     * [m] moved by ([px], [py]) frame pixels. Text is drawn from whole pixels, so its anchor
     * goes to the middle of the pixel it now starts in: float error can't then land it a pixel
     * short of where the screen showed it.
     */
    fun shifted(m: Mark, px: Int, py: Int): Mark {
        if (m is Mark.Text) {
            val x = (floor(m.at.x * w.toDouble()) + px + 0.5) / w
            val y = (floor(m.at.y * h.toDouble()) + py + 0.5) / h
            return m.copy(at = Pt(x.toFloat(), y.toFloat()))
        }
        val dx = px.toFloat() / w
        val dy = py.toFloat() / h
        fun Pt.by() = Pt(x + dx, y + dy)
        return when (m) {
            is Mark.Ellipse -> m.copy(a = m.a.by(), b = m.b.by())
            is Mark.Box -> m.copy(a = m.a.by(), b = m.b.by())
            is Mark.Arrow -> m.copy(from = m.from.by(), to = m.to.by())
            is Mark.Pen -> m.copy(points = m.points.map { it.by() })
            is Mark.Text -> error("text is handled above")
        }
    }

    /** [m] moved by a drag of ([dx], [dy]) image units, held within the frame ([offsetPx], then [shifted]). */
    fun moved(m: Mark, dx: Float, dy: Float): Mark = offsetPx(m, dx, dy).let { (px, py) -> shifted(m, px, py) }

    companion object {
        /** The touch tolerance: this many dp around what is drawn still picks it. */
        const val TOUCH_DP = 24f

        /**
         * [d] limited so the span [lo, hi] stays within 0..1. A span over an edge may stay
         * there but gets no further out. One wider than the frame (a long label) slides until
         * one of its ends reaches the frame's edge, rather than being stuck.
         */
        fun clamp(d: Float, lo: Float, hi: Float): Float =
            if (hi - lo <= 1f) d.coerceIn(min(-lo, 0f), max(1f - hi, 0f))
            else d.coerceIn(min(1f - hi, 0f), max(-lo, 0f))

        /** Distance from ([x], [y]) to the segment [s]. */
        fun distance(x: Double, y: Double, s: AnnotationRenderer.Seg): Double {
            val dx = s.x1 - s.x0
            val dy = s.y1 - s.y0
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((x - s.x0) * dx + (y - s.y0) * dy) / len2).coerceIn(0.0, 1.0)
            return hypot(x - (s.x0 + t * dx), y - (s.y0 + t * dy))
        }

        /** [TOUCH_DP] as frame pixels, for a frame [frameWidth] px wide shown [viewWidth] screen px wide at [density] px per dp. */
        fun tolerance(density: Float, viewWidth: Float, frameWidth: Int): Double =
            TOUCH_DP * density * frameWidth / viewWidth.toDouble()
    }
}
