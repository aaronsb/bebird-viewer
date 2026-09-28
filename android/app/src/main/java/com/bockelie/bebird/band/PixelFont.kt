// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.band

import java.io.BufferedReader

/**
 * A 16-pixel-high bitmap font: each glyph is one cell (8 px wide) or two (16 px, for wide
 * characters such as CJK). Glyphs are kept compactly (sorted code points, one byte array) so
 * even GNU Unifont's ~57 000 glyphs stay small. Pure Kotlin, so tests can load the real fonts.
 */
class PixelFont private constructor(
    private val codepoints: IntArray,  // sorted
    private val cells: ByteArray,      // 1 or 2 per glyph
    private val offsets: IntArray,     // into data; each glyph is HEIGHT rows of cells bytes
    private val data: ByteArray,
) {
    /** One glyph: [cells] 8-px columns wide, [HEIGHT] rows; bit 7 of each byte is leftmost. */
    class Glyph(val cells: Int, private val rows: ByteArray, private val at: Int) {
        /** Whether the pixel at ([x], [y]) is set; x in 0 until cells * 8, y in 0 until HEIGHT. */
        fun pixel(x: Int, y: Int): Boolean {
            val b = rows[at + y * cells + x / 8].toInt()
            return b and (0x80 ushr (x % 8)) != 0
        }
    }

    val size: Int get() = codepoints.size

    fun glyph(codepoint: Int): Glyph? {
        val i = codepoints.binarySearch(codepoint)
        return if (i < 0) null else Glyph(cells[i].toInt(), data, offsets[i])
    }

    /** Collects glyphs without boxing: Unifont has ~57 000 of them. */
    private class Builder {
        private var cps = IntArray(1024)
        private var widths = ByteArray(1024)
        private var starts = IntArray(1024)
        private var bytes = ByteArray(32 * 1024)
        private var n = 0
        private var used = 0

        /** Add [cellCount] cells' worth of rows from [rows]. */
        fun add(cp: Int, cellCount: Int, rows: ByteArray) {
            if (n == cps.size) {
                cps = cps.copyOf(n * 2); widths = widths.copyOf(n * 2); starts = starts.copyOf(n * 2)
            }
            while (used + rows.size > bytes.size) bytes = bytes.copyOf(bytes.size * 2)
            cps[n] = cp; widths[n] = cellCount.toByte(); starts[n] = used
            rows.copyInto(bytes, used)
            used += rows.size
            n++
        }

        /** Sorted by code point; for a repeated code point the first glyph wins. */
        fun build(): PixelFont {
            // Sort (code point, index) pairs packed into longs: no boxing.
            val keys = LongArray(n) { (cps[it].toLong() shl 32) or it.toLong() }
            keys.sort()
            val order = IntArray(n)
            var m = 0
            for (k in keys) {
                val i = (k and 0xFFFFFFFFL).toInt()
                if (m > 0 && cps[order[m - 1]] == cps[i]) continue
                order[m++] = i
            }
            return PixelFont(
                IntArray(m) { cps[order[it]] },
                ByteArray(m) { widths[order[it]] },
                IntArray(m) { starts[order[it]] },
                bytes.copyOf(used),
            )
        }
    }

    companion object {
        const val HEIGHT = 16
        const val CELL = 8

        /**
         * GNU Unifont's .hex format: "XXXX:" then 32 hex digits (8x16) or 64 (16x16) per line.
         * A malformed line is skipped, as in [parseBdf].
         */
        fun parseHex(reader: BufferedReader): PixelFont {
            val b = Builder()
            val narrow = ByteArray(HEIGHT)
            val wide = ByteArray(HEIGHT * 2)
            reader.forEachLine { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) return@forEachLine
                val cp = hex(line, 0, colon) ?: return@forEachLine
                val digits = line.length - colon - 1
                val rows = when (digits) {
                    32 -> narrow
                    64 -> wide
                    else -> return@forEachLine
                }
                for (i in rows.indices) {
                    rows[i] = (hex(line, colon + 1 + 2 * i, colon + 3 + 2 * i) ?: return@forEachLine).toByte()
                }
                b.add(cp, rows.size / HEIGHT, rows)
            }
            return b.build()
        }

        /** The hex number in [s] from [from] until [to], or null if any character isn't a hex digit. */
        private fun hex(s: String, from: Int, to: Int): Int? {
            if (to <= from || to - from > 7) return null
            var v = 0
            for (i in from until to) {
                val d = Character.digit(s[i], 16)
                if (d < 0) return null
                v = v * 16 + d
            }
            return v
        }

        /**
         * A BDF font whose cells are 8 or 16 px wide and 16 px high (Terminus ter-u16n). Each
         * glyph's bitmap is placed in the cell by its BBX offsets relative to the font's box.
         * A malformed glyph (bad numbers or bitmap rows) is skipped, as in [parseHex].
         */
        fun parseBdf(reader: BufferedReader): PixelFont {
            val b = Builder()
            var descent = 4  // from FONTBOUNDINGBOX's y offset (negative)
            var cp = -1
            var bbx: IntArray? = null
            var dwidth = CELL
            var bitmap: MutableList<String>? = null
            reader.forEachLine { raw ->
                val line = raw.trim()
                val word = line.substringBefore(' ')
                val args = line.substringAfter(' ', "").split(' ').filter { it.isNotEmpty() }
                when {
                    bitmap != null && word != "ENDCHAR" -> bitmap!! += line
                    word == "FONTBOUNDINGBOX" -> descent = -(args.getOrNull(3)?.toIntOrNull() ?: -4)
                    word == "STARTCHAR" -> { cp = -1; bbx = intArrayOf(CELL, HEIGHT, 0, -descent); dwidth = CELL }
                    word == "ENCODING" -> cp = args.firstOrNull()?.toIntOrNull() ?: -1
                    word == "DWIDTH" -> dwidth = args.firstOrNull()?.toIntOrNull() ?: -1
                    word == "BBX" -> bbx = args.take(4).map { it.toIntOrNull() }.takeIf { it.size == 4 && null !in it }
                        ?.map { it!! }?.toIntArray()
                    word == "BITMAP" -> bitmap = mutableListOf()
                    word == "ENDCHAR" -> {
                        val rows = bitmap ?: emptyList()
                        bitmap = null
                        val box = bbx
                        if (cp >= 0 && box != null && dwidth > 0) {
                            val cellCount = if (dwidth > CELL) 2 else 1
                            place(rows, box, cellCount, descent)?.let { b.add(cp, cellCount, it) }
                        }
                    }
                }
            }
            return b.build()
        }

        /** Put a BDF bitmap (rows of hex) into a cellCount-wide, 16-high cell; null if malformed. */
        private fun place(rows: List<String>, bbx: IntArray, cellCount: Int, descent: Int): ByteArray? {
            val (w, h, xOff, yOff) = bbx.toList()
            if (w < 0 || h < 0 || rows.size < h) return null
            val out = ByteArray(HEIGHT * cellCount)
            val top = HEIGHT - descent - (h + yOff)  // rows from the cell's top to the bitmap's
            for (r in 0 until h) {
                val row = rows[r]
                if (row.length * 4 < w) return null  // fewer bits than the box is wide
                for (x in 0 until w) {
                    val digit = Character.digit(row[x / 4], 16)
                    if (digit < 0) return null
                    if (digit and (8 ushr (x % 4)) == 0) continue
                    val y = top + r
                    val px = x + xOff
                    if (y !in 0 until HEIGHT || px !in 0 until cellCount * CELL) continue
                    val i = y * cellCount + px / 8
                    out[i] = (out[i].toInt() or (0x80 ushr (px % 8))).toByte()
                }
            }
            return out
        }
    }
}

/** A primary font with a fallback for what it lacks, and a replacement glyph for the rest. */
class GlyphSource(private val primary: PixelFont, private val fallback: PixelFont? = null) {
    private val replacement = primary.glyph(0xFFFD) ?: primary.glyph('?'.code)

    fun glyph(codepoint: Int): PixelFont.Glyph? =
        primary.glyph(codepoint) ?: fallback?.glyph(codepoint) ?: replacement

    /** Whether any font has a real glyph for [codepoint]. */
    fun has(codepoint: Int) = primary.glyph(codepoint) != null || fallback?.glyph(codepoint) != null

    /** How many cells [codepoint] takes (1, or 2 for wide glyphs). */
    fun cells(codepoint: Int): Int = glyph(codepoint)?.cells ?: 1
}
