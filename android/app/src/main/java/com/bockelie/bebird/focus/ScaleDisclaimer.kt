// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import com.bockelie.bebird.band.PixelText

/**
 * The note at the viewport's upper right while the proximity scale is on screen (#35): the scale
 * is an estimate, not a measurement. Upright and fixed to the viewport, like CLOSE at the upper
 * left, whose width it keeps clear of. On screen only: saved stills carry their own shorter
 * note with the scale (capture/ScaleStamp). Pure.
 */
object ScaleDisclaimer {
    /** [lines] in a [width] × [height] image (a pixel of outline round them), shown [scale] times larger. */
    data class Placement(val scale: Int, val width: Int, val height: Int, val lines: List<PixelText.Line>)

    const val MAX_ROWS = 5
    /** Kept clear round the note (from the viewport's edges and from CLOSE), in font pixels. */
    const val INSET = 8

    /** Whether the note shows: a frame is on screen with a scale on it (CLOSE alone doesn't count). */
    fun shown(hasFrame: Boolean, proximity: List<OverlayShape>): Boolean =
        hasFrame && proximity.any { !ScaleOverlay.isClose(it) }

    /**
     * [paragraphs] for a [viewportW] × [viewportH] px viewport whose upper-left [closeW] px are
     * CLOSE's: at the largest whole scale up to CLOSE's [closeScale] at which they fit in at most
     * [MAX_ROWS] rows, no wider than half the viewport and no taller than a third of it. Null if
     * they fit at no scale.
     */
    fun place(text: PixelText, paragraphs: List<String>, viewportW: Int, viewportH: Int, closeScale: Int, closeW: Int): Placement? {
        for (s in maxOf(1, closeScale) downTo 1) {
            val room = minOf(viewportW / 2, viewportW - closeW) - 2 * INSET * s
            val column = room / s - 2  // less the outline
            if (column <= 0) continue
            val lines = text.rightAligned(paragraphs, column, MAX_ROWS) ?: continue
            val width = (lines.maxOfOrNull { it.x + text.width(it.text) } ?: 0) + 2
            val height = text.height(lines.size) + 2
            if (height * s > viewportH / 3) continue
            return Placement(s, width, height, lines.map { it.copy(x = it.x + 1, y = it.y + 1) })
        }
        return null
    }
}
