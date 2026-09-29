// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.annotate

import com.bockelie.bebird.band.GlyphSource
import com.bockelie.bebird.band.PixelFont
import com.bockelie.bebird.band.PixelImage
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Draws [Mark]s into ARGB pixels, the band's way: pixels chosen by a distance test (no
 * anti-aliasing), text from the band's bitmap font at a whole scale. Every stroke has a thin
 * black edge so it shows on light and dark tissue alike. Sizes are fractions of the drawing
 * area's width, so the on-screen layer and the saved file look the same at any size. Pure.
 */
class AnnotationRenderer(private val font: GlyphSource) {
    /** [marks] alone on a transparent [width] × [height] image: the on-screen layer. */
    fun render(marks: List<Mark>, width: Int, height: Int): PixelImage {
        val px = IntArray(width * height)
        draw(px, width, width, height, marks)
        return PixelImage(width, height, px)
    }

    /**
     * A copy of [image] with [marks] drawn over its top-left [areaWidth] × [areaHeight] (the
     * frame, above the band in a still with the overlay) and nothing outside that area.
     */
    fun paint(image: PixelImage, marks: List<Mark>, areaWidth: Int = image.width, areaHeight: Int = image.height): PixelImage {
        require(areaWidth <= image.width && areaHeight <= image.height) { "area outside the image" }
        val px = image.pixels.copyOf()
        draw(px, image.width, areaWidth, areaHeight, marks)
        return PixelImage(image.width, image.height, px)
    }

    private fun draw(px: IntArray, stride: Int, w: Int, h: Int, marks: List<Mark>) {
        val half = half(w)
        val edge = edge(w)
        for (m in marks) {
            if (m is Mark.Text) {
                text(px, stride, w, h, m)
                continue
            }
            // the whole mark's edge first, then its colour, so joints get no black notches
            val segs = segments(m, w, h)
            for (s in segs) segment(px, stride, w, h, s, half + edge, BLACK)
            for (s in segs) segment(px, stride, w, h, s, half, m.color)
        }
    }

    /** Pixels whose centres are within [half] of [s]. */
    private fun segment(px: IntArray, stride: Int, w: Int, h: Int, s: Seg, half: Double, color: Int) {
        val dx = s.x1 - s.x0
        val dy = s.y1 - s.y0
        val len2 = dx * dx + dy * dy
        val r2 = half * half
        for (y in max(0, floor(min(s.y0, s.y1) - half).toInt())..min(h - 1, ceil(max(s.y0, s.y1) + half).toInt())) {
            val cy = y + 0.5
            for (x in max(0, floor(min(s.x0, s.x1) - half).toInt())..min(w - 1, ceil(max(s.x0, s.x1) + half).toInt())) {
                val cx = x + 0.5
                val t = if (len2 == 0.0) 0.0 else (((cx - s.x0) * dx + (cy - s.y0) * dy) / len2).coerceIn(0.0, 1.0)
                val ex = cx - (s.x0 + t * dx)
                val ey = cy - (s.y0 + t * dy)
                if (ex * ex + ey * ey < r2) px[y * stride + x] = color
            }
        }
    }

    /** The text at a whole scale, with a black outline (its pixels shifted by the scale in 8 directions). */
    private fun text(px: IntArray, stride: Int, w: Int, h: Int, t: Mark.Text) {
        val k = textScale(w)
        val cps = t.text.codePoints().toArray()
        val left = floor(t.at.x * w.toDouble()).toInt()
        val top = floor(t.at.y * h.toDouble()).toInt() - PixelFont.HEIGHT * k / 2
        for ((ddx, ddy) in NEIGHBOURS) glyphs(px, stride, w, h, cps, left + ddx * k, top + ddy * k, k, BLACK)
        glyphs(px, stride, w, h, cps, left, top, k, t.color)
    }

    private fun glyphs(px: IntArray, stride: Int, w: Int, h: Int, cps: IntArray, left: Int, top: Int, k: Int, color: Int) {
        var x0 = left
        for (cp in cps) {
            val g = font.glyph(cp)
            if (g != null) for (gy in 0 until PixelFont.HEIGHT) for (gx in 0 until g.cells * PixelFont.CELL) {
                if (!g.pixel(gx, gy)) continue
                for (sy in 0 until k) for (sx in 0 until k) {
                    val x = x0 + gx * k + sx
                    val y = top + gy * k + sy
                    if (x in 0 until w && y in 0 until h) px[y * stride + x] = color
                }
            }
            x0 += (g?.cells ?: 1) * PixelFont.CELL * k
        }
    }

    /** A straight piece of a stroke, in pixels of the drawing area. */
    data class Seg(val x0: Double, val y0: Double, val x1: Double, val y1: Double)

    companion object {
        const val BLACK = 0xFF000000.toInt()
        /** Stroke width as a fraction of the area's width: 3 px on a 480-px frame. */
        const val STROKE = 1 / 160.0
        /** Arrowhead length as a fraction of the area's width, and its half-angle. */
        const val HEAD = 0.045
        const val HEAD_DEG = 28.0
        /** Pieces an ellipse is drawn with. */
        const val ELLIPSE_STEPS = 72
        private val NEIGHBOURS = listOf(-1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1)

        /** Half a stroke's width, in pixels, for an area [width] px wide. */
        fun half(width: Int) = max(1.0, width * STROKE / 2)

        /** The black edge's width around a stroke, in pixels. */
        fun edge(width: Int) = max(1.0, width / 480.0)

        /** The text's whole scale: 2 (32 px tall) on a 480-px frame. */
        fun textScale(width: Int) = max(1, (width / 240.0).roundToInt())

        /**
         * The straight pieces [m] is drawn with in a [w] × [h] area (text has none). The screen
         * draws its in-progress mark from these too, so it matches what is saved.
         */
        fun segments(m: Mark, w: Int, h: Int): List<Seg> {
            fun x(p: Pt) = p.x * w.toDouble()
            fun y(p: Pt) = p.y * h.toDouble()
            return when (m) {
                is Mark.Box -> {
                    val (l, r) = min(x(m.a), x(m.b)) to max(x(m.a), x(m.b))
                    val (t, b) = min(y(m.a), y(m.b)) to max(y(m.a), y(m.b))
                    listOf(Seg(l, t, r, t), Seg(r, t, r, b), Seg(r, b, l, b), Seg(l, b, l, t))
                }
                is Mark.Ellipse -> {
                    val cx = (x(m.a) + x(m.b)) / 2
                    val cy = (y(m.a) + y(m.b)) / 2
                    val rx = abs(x(m.a) - x(m.b)) / 2
                    val ry = abs(y(m.a) - y(m.b)) / 2
                    val pts = (0..ELLIPSE_STEPS).map { i ->
                        val a = 2 * Math.PI * i / ELLIPSE_STEPS
                        cx + rx * cos(a) to cy + ry * sin(a)
                    }
                    pts.zipWithNext { (x0, y0), (x1, y1) -> Seg(x0, y0, x1, y1) }
                }
                is Mark.Arrow -> {
                    val x0 = x(m.from); val y0 = y(m.from); val x1 = x(m.to); val y1 = y(m.to)
                    val back = atan2(y0 - y1, x0 - x1)  // from the tip towards the tail
                    val len = min(w * HEAD, hypot(x1 - x0, y1 - y0) * 0.5)
                    val spread = Math.toRadians(HEAD_DEG)
                    listOf(
                        Seg(x0, y0, x1, y1),
                        Seg(x1, y1, x1 + len * cos(back + spread), y1 + len * sin(back + spread)),
                        Seg(x1, y1, x1 + len * cos(back - spread), y1 + len * sin(back - spread)),
                    )
                }
                is Mark.Pen ->
                    if (m.points.size == 1) listOf(Seg(x(m.points[0]), y(m.points[0]), x(m.points[0]), y(m.points[0])))
                    else m.points.zipWithNext { a, b -> Seg(x(a), y(a), x(b), y(b)) }
                is Mark.Text -> emptyList()
            }
        }
    }
}
