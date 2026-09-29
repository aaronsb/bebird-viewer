// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import kotlin.math.cos
import kotlin.math.sin

/** How the mm scale is drawn over the image (§8); [NONE] leaves only the CLOSE indicator. */
enum class ScaleStyle { RING, BOWTIE, BAR, NONE }

/** Where a [OverlayShape.Label]'s (x, y) sits on its text, as in Pillow's anchors. */
enum class TextAnchor { LEFT_TOP, LEFT_MIDDLE, LEFT_BOTTOM, LEFT_BASELINE, MIDDLE_BOTTOM, RIGHT_TOP }

/**
 * Drawing primitives in raw-frame pixels (centre (240, 240) by default), for a renderer that
 * draws one-pixel rings by a distance test at integer scale. Angles are degrees clockwise from
 * the +x axis (y points down).
 */
sealed interface OverlayShape {
    /**
     * An arc of radius [r] from [startDeg] to [endDeg]: solid when [dashDeg] is 0, otherwise
     * [dashDeg] on and [dashDeg] off starting at [startDeg].
     */
    data class Arc(
        val cx: Double, val cy: Double, val r: Double,
        val startDeg: Double, val endDeg: Double, val dashDeg: Double,
        val color: Int, val width: Int,
    ) : OverlayShape

    /** A straight line; [outlined] means a black line [width] + 2 wide under it, for contrast. */
    data class Line(
        val x0: Double, val y0: Double, val x1: Double, val y1: Double,
        val color: Int, val width: Int, val outlined: Boolean,
    ) : OverlayShape

    /** Bitmap-font text with a 1-px black outline ("⌀" is U+2300, in Unifont). */
    data class Label(val x: Double, val y: Double, val text: String, val anchor: TextAnchor, val color: Int) : OverlayShape

    /** The CLOSE glyph: a yellow triangle with a black outline and a black "!" (U+26A0). */
    data class Warning(val x: Double, val y: Double, val size: Double, val color: Int) : OverlayShape
}

/**
 * The mm scale and CLOSE indicator of §8, as primitives. The scale is 40 px/mm ±10 % at the tip
 * end (ruler captures: 38.8-43.3), uniformly spaced: distortion is below the noise out to ~4 mm.
 * Grey, thin and dashed until the estimator claims it ([FocusResult.locked]); then lock colour,
 * 2 px, solid. The indicator's wording stays neutral: it never claims to prevent contact.
 */
object ScaleOverlay {
    const val PX_PER_MM = 40.0
    const val TOLERANCE_LABEL = "mm ±10%"
    const val CLOSE_LABEL = "CLOSE"
    const val DIAMETER = '⌀'

    const val GREY = 0xFFAAAAAA.toInt()
    const val LOCK = 0xFF00E6C8.toInt()
    const val WARNING = 0xFFFFD700.toInt()
    const val DASH_DEG = 6.0

    /** Rings (and bowtie ticks) at k mm radius for k in 1..RINGS, labelled by diameter 2k. */
    const val RINGS = 5

    /**
     * The overlay for one frame's [result], in the user's [style] and with CLOSE if [showClose]
     * (both from [ProximitySettings], read once, not per frame). With estimation off there
     * is no result and nothing is drawn: the scale is only meaningful when the estimator can say
     * whether it holds, so it is not shown permanently "unverified" either.
     */
    fun forFrame(result: FocusResult?, style: ScaleStyle, showClose: Boolean): List<OverlayShape> =
        if (result == null) emptyList()
        else shapes(style, result.locked, showClose && result.close)

    // the default geometry has only 4 × 2 × 2 variants: built once each, not per frame
    private val cache = arrayOfNulls<List<OverlayShape>>(ScaleStyle.entries.size * 4)

    fun shapes(style: ScaleStyle, locked: Boolean, close: Boolean): List<OverlayShape> {
        val k = style.ordinal * 4 + (if (locked) 2 else 0) + (if (close) 1 else 0)
        return cache[k] ?: build(style, locked, close, FrameGeometry.CENTER.toDouble(), PX_PER_MM).also { cache[k] = it }
    }

    /** The shapes for another centre or scale (e.g. drawn at display resolution). */
    fun shapes(style: ScaleStyle, locked: Boolean, close: Boolean, center: Double, pxPerMm: Double): List<OverlayShape> =
        build(style, locked, close, center, pxPerMm)

    private fun build(style: ScaleStyle, locked: Boolean, close: Boolean, center: Double, pxPerMm: Double): List<OverlayShape> {
        val out = ArrayList<OverlayShape>()
        val col = if (locked) LOCK else GREY
        val w = if (locked) 2 else 1
        val dash = if (locked) 0.0 else DASH_DEG
        val c = center
        when (style) {
            ScaleStyle.RING -> {
                for (k in 1..RINGS) {
                    val r = k * pxPerMm
                    out += OverlayShape.Arc(c, c, r, 0.0, 360.0, dash, col, w)
                    // along the horizontal axis, just outside each ring: the scale stays upright on screen
                    out += OverlayShape.Label(c + r + 3, c - 3, "$DIAMETER${2 * k}", TextAnchor.LEFT_BASELINE, col)
                }
                val d = RINGS * pxPerMm * 0.72
                out += OverlayShape.Label(c + d + 6, c - d - 6, TOLERANCE_LABEL, TextAnchor.LEFT_BOTTOM, col)
            }
            ScaleStyle.BOWTIE -> {
                val r0 = 0.5 * pxPerMm
                val r1 = 5.25 * pxPerMm
                val s15 = sin(Math.toRadians(15.0))
                for (base in intArrayOf(0, 180)) {
                    for (e in intArrayOf(-15, 15)) {
                        val a = Math.toRadians((base + e).toDouble())
                        val x0 = c + r0 * cos(a)
                        val y0 = c + r0 * sin(a)
                        val x1 = c + r1 * cos(a)
                        val y1 = c + r1 * sin(a)
                        if (locked) {
                            out += OverlayShape.Line(x0, y0, x1, y1, col, w, false)
                        } else {
                            val n = 12  // six dashes along the wedge edge
                            for (j in 0 until n step 2) {
                                out += OverlayShape.Line(
                                    x0 + (x1 - x0) * j / n, y0 + (y1 - y0) * j / n,
                                    x0 + (x1 - x0) * (j + 1) / n, y0 + (y1 - y0) * (j + 1) / n,
                                    col, w, false,
                                )
                            }
                        }
                    }
                    for (k in 1..RINGS) {
                        val r = k * pxPerMm
                        out += OverlayShape.Arc(c, c, r, base - 15.0, base + 15.0, 0.0, col, w)
                        if (base == 0) {
                            out += OverlayShape.Label(c + r - 10, c - r * s15 - 4, "$DIAMETER${2 * k}", TextAnchor.LEFT_BASELINE, col)
                        }
                    }
                }
                out += OverlayShape.Label(c + 3.2 * pxPerMm, c + r1 * s15 + 6, TOLERANCE_LABEL, TextAnchor.LEFT_TOP, col)
            }
            ScaleStyle.BAR -> {
                // straight scale through the centre, to lay along a ruler: a tick per mm, long every 5
                val half = 5.5 * pxPerMm
                out += OverlayShape.Line(c - half, c, c + half, c, col, w, true)
                for (k in -5..5) {
                    val x = c + k * pxPerMm
                    val len = if (k % 5 == 0) 14.0 else 7.0
                    out += OverlayShape.Line(x, c - len, x, c + len, col, w, true)
                    out += OverlayShape.Label(x, c - len - 2, "${k + 5}", TextAnchor.MIDDLE_BOTTOM, col)
                }
                out += OverlayShape.Label(c + half, c + 16, TOLERANCE_LABEL, TextAnchor.RIGHT_TOP, col)
            }
            ScaleStyle.NONE -> Unit
        }
        if (close) {
            val x = 18.0
            val y = 16.0
            val s = 40.0
            out += OverlayShape.Warning(x, y, s, WARNING)
            out += OverlayShape.Label(x + s + 8, y + s * 0.45, CLOSE_LABEL, TextAnchor.LEFT_MIDDLE, WARNING)
        }
        return java.util.Collections.unmodifiableList(out)
    }
}
