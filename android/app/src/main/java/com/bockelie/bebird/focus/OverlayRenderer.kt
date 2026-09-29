// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import com.bockelie.bebird.band.GlyphSource
import com.bockelie.bebird.band.PixelFont
import com.bockelie.bebird.band.PixelImage
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Draws [OverlayShape]s into a transparent ARGB buffer, the band's way: pixels chosen by a
 * distance test (no anti-aliasing), text from the band's bitmap font at a whole scale. Shapes
 * are in raw-frame pixels; the output maps raw (x, y) to ((x − originX) · [f], (y − originY) · [f])
 * so rings stay one device pixel (times [k]) thin at any display size. Pure.
 */
class OverlayRenderer(private val font: GlyphSource) {
    /**
     * Render [shapes] into a [width] × [height] image at [f] output pixels per raw pixel, with
     * the raw point ([originX], [originY]) at the output's top left. Strokes and text are [k]
     * times their size: by default f's whole part, so they keep their look at any display size.
     */
    fun render(
        shapes: List<OverlayShape>, width: Int, height: Int, f: Double,
        originX: Double = 0.0, originY: Double = 0.0, k: Int = max(1, floor(f).toInt()),
    ): PixelImage {
        val c = Canvas(IntArray(width * height), width, height, f, originX, originY, k)
        for (s in shapes) when (s) {
            is OverlayShape.Arc -> c.arc(s)
            is OverlayShape.Line -> c.line(s)
            is OverlayShape.Label -> c.label(s)
            is OverlayShape.Warning -> c.warning(s)
        }
        return PixelImage(width, height, c.px)
    }

    private inner class Canvas(
        val px: IntArray, val w: Int, val h: Int, val f: Double, val ox: Double, val oy: Double,
        /** Whole-pixel scale for stroke widths and text. */
        val k: Int,
    ) {
        fun X(x: Double) = (x - ox) * f
        fun Y(y: Double) = (y - oy) * f

        fun set(x: Int, y: Int, color: Int) {
            if (x in 0 until w && y in 0 until h) px[y * w + x] = color
        }

        /**
         * A ring by distance test: pixel centres with r − half ≤ d < r + half. Each row touches
         * only the ring's one or two spans (squared distances, no sqrt per pixel).
         */
        fun arc(a: OverlayShape.Arc) {
            val cx = X(a.cx)
            val cy = Y(a.cy)
            val r = a.r * f
            val half = max(0.5, a.width * k / 2.0)
            val outer = r + half
            val inner = max(0.0, r - half)
            val outer2 = outer * outer
            val inner2 = inner * inner
            val span = a.endDeg - a.startDeg
            val partial = span < 360.0 || a.dashDeg > 0
            for (y in max(0, floor(cy - outer).toInt())..min(h - 1, ceil(cy + outer).toInt())) {
                val dy = y - cy
                val dy2 = dy * dy
                if (dy2 >= outer2) continue
                val xo = sqrt(outer2 - dy2)
                val xi = if (dy2 < inner2) sqrt(inner2 - dy2) else 0.0
                // left span [cx − xo, cx − xi], right span [cx + xi, cx + xo]; one span if xi = 0
                for ((from, to) in if (xi > 0) listOf(cx - xo to cx - xi, cx + xi to cx + xo) else listOf(cx - xo to cx + xo)) {
                    for (x in max(0, floor(from).toInt())..min(w - 1, ceil(to).toInt())) {
                        val dx = x - cx
                        val d2 = dx * dx + dy2
                        if (d2 >= outer2 || d2 < inner2) continue
                        if (partial) {
                            // degrees clockwise from +x (y down), measured from the arc's start, in [0, 360)
                            val deg = ((Math.toDegrees(atan2(dy, dx)) - a.startDeg) % 360 + 360) % 360
                            if (span < 360.0 && deg > span) continue
                            if (a.dashDeg > 0 && floor(deg / a.dashDeg).toInt() % 2 != 0) continue
                        }
                        set(x, y, a.color)
                    }
                }
            }
        }

        fun line(l: OverlayShape.Line) {
            if (l.outlined) segment(l, (l.width + 2) * k / 2.0, BLACK)
            segment(l, max(0.5, l.width * k / 2.0), l.color)
        }

        /**
         * Along the segment, pixel centres whose signed distance s from the line has
         * −half ≤ s < half: half-open like [arc], so a width-2 line is 2 px wide wherever it
         * falls, as a width-2 ring is. Past the ends, rounded caps (distance < half).
         */
        private fun segment(l: OverlayShape.Line, half: Double, color: Int) {
            val x0 = X(l.x0); val y0 = Y(l.y0); val x1 = X(l.x1); val y1 = Y(l.y1)
            val dx = x1 - x0; val dy = y1 - y0
            val len = hypot(dx, dy)
            for (y in max(0, floor(min(y0, y1) - half).toInt())..min(h - 1, ceil(max(y0, y1) + half).toInt())) {
                for (x in max(0, floor(min(x0, x1) - half).toInt())..min(w - 1, ceil(max(x0, x1) + half).toInt())) {
                    val t = if (len == 0.0) 0.0 else ((x - x0) * dx + (y - y0) * dy) / (len * len)
                    val hit = if (len > 0.0 && t in 0.0..1.0) {
                        val s = ((x - x0) * dy - (y - y0) * dx) / len
                        s >= -half && s < half
                    } else {
                        val e = t.coerceIn(0.0, 1.0)
                        hypot(x - (x0 + e * dx), y - (y0 + e * dy)) < half
                    }
                    if (hit) set(x, y, color)
                }
            }
        }

        /** Bitmap-font text at scale k, with a one-(scaled-)pixel black outline, placed by its anchor. */
        fun label(l: OverlayShape.Label) {
            val cps = l.text.codePoints().toArray()
            val cells = cps.sumOf { if (it == ScaleOverlay.DIAMETER.code) 1 else font.cells(it) }
            val tw = cells * PixelFont.CELL * k
            val th = PixelFont.HEIGHT * k
            val ax = X(l.x).toInt()
            val ay = Y(l.y).toInt()
            val left = when (l.anchor) {
                TextAnchor.MIDDLE_BOTTOM -> ax - tw / 2
                TextAnchor.RIGHT_TOP -> ax - tw
                else -> ax
            }
            val top = when (l.anchor) {
                TextAnchor.LEFT_TOP, TextAnchor.RIGHT_TOP -> ay
                TextAnchor.LEFT_MIDDLE -> ay - th / 2
                TextAnchor.LEFT_BOTTOM, TextAnchor.MIDDLE_BOTTOM -> ay - th
                TextAnchor.LEFT_BASELINE -> ay - BASELINE * k
            }
            // outline first (the text's pixels shifted by k in 8 directions), then the text
            for ((ddx, ddy) in NEIGHBOURS) text(cps, left + ddx * k, top + ddy * k, BLACK)
            text(cps, left, top, l.color)
        }

        private fun text(cps: IntArray, left: Int, top: Int, color: Int) {
            var x0 = left
            for (cp in cps) {
                val cellsWide: Int
                if (cp == ScaleOverlay.DIAMETER.code) {
                    cellsWide = 1
                    for (gy in 0 until PixelFont.HEIGHT) for (gx in 0 until PixelFont.CELL) if (diameter(gx, gy)) block(x0, top, gx, gy, color)
                } else {
                    val g = font.glyph(cp)
                    cellsWide = g?.cells ?: 1
                    if (g != null) for (gy in 0 until PixelFont.HEIGHT) for (gx in 0 until g.cells * PixelFont.CELL) {
                        if (g.pixel(gx, gy)) block(x0, top, gx, gy, color)
                    }
                }
                x0 += cellsWide * PixelFont.CELL * k
            }
        }

        private fun block(x0: Int, y0: Int, gx: Int, gy: Int, color: Int) {
            for (dy in 0 until k) for (dx in 0 until k) set(x0 + gx * k + dx, y0 + gy * k + dy, color)
        }

        /** The CLOSE glyph: a filled triangle, point up, black edge, black "!". */
        fun warning(wn: OverlayShape.Warning) {
            val x = X(wn.x); val y = Y(wn.y); val s = wn.size * f
            val ax = x + s / 2; val ay = y          // apex
            val bx = x; val by = y + s              // bottom left
            val cx = x + s; val cy = y + s          // bottom right
            val edge = 1.5 * k
            for (py in max(0, floor(y).toInt())..min(h - 1, ceil(y + s).toInt())) {
                for (pxl in max(0, floor(x).toInt())..min(w - 1, ceil(x + s).toInt())) {
                    val p = pxl.toDouble(); val q = py.toDouble()
                    if (!inTriangle(p, q, ax, ay, bx, by, cx, cy)) continue
                    val d = minOf(dist(p, q, ax, ay, bx, by), dist(p, q, bx, by, cx, cy), dist(p, q, cx, cy, ax, ay))
                    set(pxl, py, if (d < edge) BLACK else wn.color)
                }
            }
            // "!": a bar and a dot, centred
            val barW = max(1.0, s * 0.10)
            fill(ax - barW / 2, y + s * 0.34, ax + barW / 2, y + s * 0.70, BLACK)
            fill(ax - barW / 2, y + s * 0.77, ax + barW / 2, y + s * 0.87, BLACK)
        }

        private fun fill(x0: Double, y0: Double, x1: Double, y1: Double, color: Int) {
            for (y in floor(y0).toInt() until ceil(y1).toInt()) for (x in floor(x0).toInt() until ceil(x1).toInt()) set(x, y, color)
        }
    }

    private fun inTriangle(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Boolean {
        val d1 = (px - bx) * (ay - by) - (ax - bx) * (py - by)
        val d2 = (px - cx) * (by - cy) - (bx - cx) * (py - cy)
        val d3 = (px - ax) * (cy - ay) - (cx - ax) * (py - ay)
        val neg = d1 < 0 || d2 < 0 || d3 < 0
        val pos = d1 > 0 || d2 > 0 || d3 > 0
        return !(neg && pos)
    }

    /** Distance from (px, py) to the segment (x0, y0)-(x1, y1). */
    private fun dist(px: Double, py: Double, x0: Double, y0: Double, x1: Double, y1: Double): Double {
        val dx = x1 - x0; val dy = y1 - y0
        val t = (((px - x0) * dx + (py - y0) * dy) / (dx * dx + dy * dy)).coerceIn(0.0, 1.0)
        return hypot(px - (x0 + t * dx), py - (y0 + t * dy))
    }

    companion object {
        const val BLACK = 0xFF000000.toInt()
        /** The band font's baseline, in cell rows from the top (Terminus: ascent 12 of 16). */
        const val BASELINE = 12
        private val NEIGHBOURS = listOf(-1 to -1, 0 to -1, 1 to -1, -1 to 0, 1 to 0, -1 to 1, 0 to 1, 1 to 1)

        /**
         * The hand-drawn ⌀ (U+2300) in one 8 × 16 cell, like the desktop's: a small circle on the
         * x-height with a slash through it, so it reads the same whatever the fallback font has.
         */
        fun diameter(x: Int, y: Int): Boolean {
            val ring = abs(hypot(x - 3.5, y - 8.5) - 2.8) < 0.7
            // slash from the upper right (7, 4) to the lower left (0, 13)
            val slash = abs((y - 4) * 7.0 + (x - 7) * 9.0) / hypot(7.0, 9.0) < 0.6 && y in 4..13
            return ring || slash
        }
    }
}
