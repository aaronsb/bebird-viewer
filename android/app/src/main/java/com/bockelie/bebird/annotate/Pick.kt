// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.annotate

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

    /** What [m] covers, in image coordinates: its defining points, or a text label's drawn box. */
    fun bounds(m: Mark): Bounds {
        val pts = when (m) {
            is Mark.Ellipse -> listOf(m.a, m.b)
            is Mark.Box -> listOf(m.a, m.b)
            is Mark.Arrow -> listOf(m.from, m.to)
            is Mark.Pen -> m.points
            is Mark.Text -> {
                val r = renderer.textBounds(m, w, h)
                return Bounds(r.left.toFloat() / w, r.top.toFloat() / h, r.right.toFloat() / w, r.bottom.toFloat() / h)
            }
        }
        return Bounds(pts.minOf { it.x }, pts.minOf { it.y }, pts.maxOf { it.x }, pts.maxOf { it.y })
    }

    /**
     * [m] moved by ([dx], [dy]) image units, held within the frame. A mark already over an edge
     * (a long label) may stay there, but never goes further out.
     */
    fun moved(m: Mark, dx: Float, dy: Float): Mark {
        val b = bounds(m)
        val cx = clamp(dx, b.left, b.right)
        val cy = clamp(dy, b.top, b.bottom)
        fun Pt.by() = Pt(x + cx, y + cy)
        return when (m) {
            is Mark.Ellipse -> m.copy(a = m.a.by(), b = m.b.by())
            is Mark.Box -> m.copy(a = m.a.by(), b = m.b.by())
            is Mark.Arrow -> m.copy(from = m.from.by(), to = m.to.by())
            is Mark.Pen -> m.copy(points = m.points.map { it.by() })
            is Mark.Text -> m.copy(at = m.at.by())
        }
    }

    companion object {
        /** The touch tolerance: this many dp around what is drawn still picks it. */
        const val TOUCH_DP = 24f

        /** [d] limited so the span [lo, hi] stays within 0..1, or at least gets no further out. */
        fun clamp(d: Float, lo: Float, hi: Float): Float = d.coerceIn(min(-lo, 0f), max(1f - hi, 0f))

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
