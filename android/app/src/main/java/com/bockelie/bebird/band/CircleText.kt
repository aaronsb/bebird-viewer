// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.band

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/**
 * A few short paragraphs centred in the image circle, in the band's font at scale 1: the message
 * in the empty circle while there is no picture (#32). Each line is word-wrapped to fit the
 * circle's chord at its height, less a one-cell margin each side; a blank line separates
 * paragraphs. Pure; the screen scales the image up by a whole factor, without smoothing.
 */
class CircleText(private val font: GlyphSource) {
    /** One line, its cell's top left at ([x], [y]) in the image. */
    data class Line(val text: String, val x: Int, val y: Int)

    /**
     * Where [paragraphs]' lines go in a [side] × [side] image with the circle inscribed: the
     * fewest rows that hold them, centred vertically, each line centred in its row. A word too
     * long for its row is broken. Null if the circle is too small to hold them at all.
     */
    fun layout(paragraphs: List<String>, side: Int): List<Line>? {
        val words = paragraphs.map { p -> displayable(p).split(' ').filter { it.isNotEmpty() } }.filter { it.isNotEmpty() }
        if (words.isEmpty()) return emptyList()
        for (n in 1..side / PITCH) {
            val top = (side - (n * PITCH - GAP)) / 2
            val rows = fill(words, n) { i -> room(top + i * PITCH, side) } ?: continue
            return rows.mapIndexedNotNull { i, text ->
                text.takeIf { it.isNotEmpty() }?.let { Line(it, (side - width(it)) / 2, top + i * PITCH) }
            }
        }
        return null
    }

    /** [paragraphs] drawn in [color] on a transparent [side] × [side] image; empty if they don't fit. */
    fun render(paragraphs: List<String>, side: Int, color: Int = BandRenderer.VALUE): PixelImage {
        val px = IntArray(side * side)
        for (line in layout(paragraphs, side).orEmpty()) {
            var x = line.x
            for (cp in line.text.codePoints()) {
                val g = font.glyph(cp) ?: continue
                drawGlyph(px, side, side, g, x, line.y, 1, color)
                x += g.cells * PixelFont.CELL
            }
        }
        return PixelImage(side, side, px)
    }

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
     * [words] greedily into [n] rows, row i at most [roomOf] (i) px wide, a blank row between
     * paragraphs; null if they don't fit. A word wider than its row is broken to fill it.
     */
    private fun fill(words: List<List<String>>, n: Int, roomOf: (Int) -> Int): List<String>? {
        val rows = ArrayList<String>()
        for ((p, paragraph) in words.withIndex()) {
            if (p > 0) rows += ""
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
