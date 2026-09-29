// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.band

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A few short paragraphs in the band's font at scale 1, word-wrapped: centred in the image
 * circle (the empty circle's message, #32), or right-aligned in a column (the scale's
 * disclaimer, #35). Pure; the screen scales the image up by a whole factor, without smoothing.
 */
class PixelText(private val font: GlyphSource) {
    /** One line, its cell's top left at ([x], [y]) in the image. */
    data class Line(val text: String, val x: Int, val y: Int)

    /**
     * Where [paragraphs]' lines go in a [side] × [side] image with the circle inscribed: the
     * fewest rows that hold them, centred vertically, each line centred in its row and fitting
     * the circle's chord at its height, less a one-cell margin each side. A blank row separates
     * paragraphs; a word too long for its row is broken. Null if the circle is too small.
     */
    fun layout(paragraphs: List<String>, side: Int): List<Line>? {
        val words = words(paragraphs)
        if (words.isEmpty()) return emptyList()
        for (n in 1..side / PITCH) {
            val top = (side - (n * PITCH - GAP)) / 2
            val rows = fill(words, n, blankBetween = true) { i -> room(top + i * PITCH, side) } ?: continue
            return rows.mapIndexedNotNull { i, text ->
                text.takeIf { it.isNotEmpty() }?.let { Line(it, (side - width(it)) / 2, top + i * PITCH) }
            }
        }
        return null
    }

    /** Lines in a [width] × [height] box whose top left is at ([x], [y]) in the image around it. */
    data class Block(val x: Int, val y: Int, val width: Int, val height: Int, val lines: List<Line>)

    /**
     * [layout]'s lines cropped to their bounding box, so only that much is drawn; null if they
     * don't fit the circle, or there are none.
     */
    fun circleBlock(paragraphs: List<String>, side: Int): Block? {
        val lines = layout(paragraphs, side)?.takeIf { it.isNotEmpty() } ?: return null
        val left = lines.minOf { it.x }
        val top = lines.minOf { it.y }
        val right = lines.maxOf { it.x + width(it.text) }
        val bottom = lines.maxOf { it.y + PixelFont.HEIGHT }
        return Block(left, top, right - left, bottom - top, lines.map { it.copy(x = it.x - left, y = it.y - top) })
    }

    /**
     * [paragraphs] right-aligned in a column at most [maxWidth] px wide, each on a new row, at most
     * [maxRows] rows; x is from the left of the widest line, which starts at 0. A word too long
     * for the column is broken. Null if they need more rows.
     */
    fun rightAligned(paragraphs: List<String>, maxWidth: Int, maxRows: Int): List<Line>? {
        val rows = fill(words(paragraphs), maxRows, blankBetween = false) { maxWidth } ?: return null
        val used = rows.maxOfOrNull { width(it) } ?: 0
        return rows.mapIndexed { i, t -> Line(t, used - width(t), i * PITCH) }
    }

    /**
     * [lines] in [color] on a transparent [imageW] × [imageH] image. With [outline], each glyph's
     * pixels are first drawn one pixel out in all eight directions in black, as for CLOSE, so
     * the text reads over the picture; the caller leaves a pixel of room round the lines.
     */
    fun draw(lines: List<Line>, imageW: Int, imageH: Int, color: Int, outline: Boolean = false): PixelImage {
        val px = IntArray(imageW * imageH)
        for (line in lines) {
            var x = line.x
            for (cp in line.text.codePoints()) {
                val g = font.glyph(cp) ?: continue
                if (outline) for (dy in -1..1) for (dx in -1..1) {
                    if (dx != 0 || dy != 0) drawGlyph(px, imageW, imageH, g, x + dx, line.y + dy, 1, BandRenderer.BACKGROUND)
                }
                drawGlyph(px, imageW, imageH, g, x, line.y, 1, color)
                x += g.cells * PixelFont.CELL
            }
        }
        return PixelImage(imageW, imageH, px)
    }

    /** Height in pixels of [rows] rows of text. */
    fun height(rows: Int): Int = if (rows <= 0) 0 else rows * PITCH - GAP

    private fun words(paragraphs: List<String>) =
        paragraphs.map { p -> displayable(p).split(' ').filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }

    /** Width in pixels of [text] at scale 1. */
    fun width(text: String): Int = text.codePoints().toArray().sumOf { font.cells(it) } * PixelFont.CELL

    /** [text] with "…" and "’" spelled "..." and "'" when no font has them. */
    fun displayable(text: String): String {
        var s = text
        if (!font.has(ELLIPSIS.code)) s = s.replace(ELLIPSIS.toString(), "...")
        if (!font.has(APOSTROPHE.code)) s = s.replace(APOSTROPHE, '\'')
        return s
    }

    /**
     * The room across a row whose cells start at [y] in a [side]-px circle: its chord where the
     * row is narrowest (the edge farther from the centre), less the margins. 0 outside the circle.
     */
    fun room(y: Int, side: Int): Int {
        val r = side / 2.0
        val d = max(abs(y - r), abs(y + PixelFont.HEIGHT - r))
        if (d >= r) return 0
        return max(0, floor(2 * sqrt(r * r - d * d)).toInt() - 2 * MARGIN)
    }

    /**
     * [words] greedily into [n] rows, row i at most [roomOf] (i) px wide, each paragraph on a new
     * row ([blankBetween]: after a blank one); null if they don't fit. A word wider than its row
     * is broken to fill it.
     */
    private fun fill(words: List<List<String>>, n: Int, blankBetween: Boolean, roomOf: (Int) -> Int): List<String>? {
        val rows = ArrayList<String>()
        for ((p, paragraph) in words.withIndex()) {
            if (p > 0 && blankBetween) rows += ""
            var line = ""
            val queue = ArrayDeque(paragraph)
            while (queue.isNotEmpty()) {
                if (rows.size >= n) return null
                val space = roomOf(rows.size)
                val word = queue.removeFirst()
                val joined = if (line.isEmpty()) word else "$line $word"
                when {
                    width(joined) <= space -> line = joined
                    line.isNotEmpty() -> { rows += line; line = ""; queue.addFirst(word) }
                    else -> {
                        // alone and still too wide: as much as fits here, the rest on the next row
                        val head = prefixWithin(word, space)
                        if (head.isEmpty()) return null
                        rows += head
                        queue.addFirst(word.substring(head.length))
                    }
                }
            }
            rows += line
        }
        return rows.takeIf { it.size <= n }
    }

    /** The longest start of [word] (whole code points) at most [room] px wide. */
    private fun prefixWithin(word: String, room: Int): String {
        var end = 0
        var used = 0
        while (end < word.length) {
            val cp = word.codePointAt(end)
            used += font.cells(cp) * PixelFont.CELL
            if (used > room) break
            end += Character.charCount(cp)
        }
        return word.substring(0, end)
    }

    companion object {
        /** Space between lines, in pixels at scale 1. */
        const val GAP = 4
        /** From one line's top to the next's. */
        const val PITCH = PixelFont.HEIGHT + GAP
        /** Kept clear inside the circle at each end of a line: one cell. */
        const val MARGIN = PixelFont.CELL
        private const val ELLIPSIS = '…'
        private const val APOSTROPHE = '’'
    }
}
