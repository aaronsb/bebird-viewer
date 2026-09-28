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

    private class Builder {
        val cps = ArrayList<Int>()
        val widths = ArrayList<Int>()
        val bytes = java.io.ByteArrayOutputStream()
        val starts = ArrayList<Int>()

        fun add(cp: Int, cellCount: Int, rows: ByteArray) {
            cps += cp; widths += cellCount; starts += bytes.size(); bytes.write(rows)
        }

        fun build(): PixelFont {
            val order = cps.indices.sortedBy { cps[it] }.distinctBy { cps[it] }
            return PixelFont(
                IntArray(order.size) { cps[order[it]] },
                ByteArray(order.size) { widths[order[it]].toByte() },
                IntArray(order.size) { starts[order[it]] },
                bytes.toByteArray(),
            )
        }
    }

    companion object {
        const val HEIGHT = 16
        const val CELL = 8

        /**
         * GNU Unifont's .hex format: "XXXX:" then 32 hex digits (8x16) or 64 (16x16) per line.
         */
        fun parseHex(reader: BufferedReader): PixelFont {
            val b = Builder()
            reader.forEachLine { line ->
                val colon = line.indexOf(':')
                if (colon <= 0) return@forEachLine
                val cp = line.substring(0, colon).toIntOrNull(16) ?: return@forEachLine
                val hex = line.substring(colon + 1).trim()
                val cellCount = when (hex.length) {
                    32 -> 1
                    64 -> 2
                    else -> return@forEachLine
                }
                b.add(cp, cellCount, ByteArray(hex.length / 2) { hex.substring(2 * it, 2 * it + 2).toInt(16).toByte() })
            }
            return b.build()
        }

        /**
         * A BDF font whose cells are 8 or 16 px wide and 16 px high (Terminus ter-u16n). Each
         * glyph's bitmap is placed in the cell by its BBX offsets relative to the font's box.
         */
        fun parseBdf(reader: BufferedReader): PixelFont {
            val b = Builder()
            var descent = 4  // from FONTBOUNDINGBOX's y offset (negative) or FONT_DESCENT
            var cp = -1
            var bbx = intArrayOf(CELL, HEIGHT, 0, -descent)
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
                    word == "DWIDTH" -> dwidth = args.firstOrNull()?.toIntOrNull() ?: CELL
                    word == "BBX" -> bbx = IntArray(4) { args.getOrNull(it)?.toIntOrNull() ?: 0 }
                    word == "BITMAP" -> bitmap = mutableListOf()
                    word == "ENDCHAR" -> {
                        val rows = bitmap ?: emptyList()
                        bitmap = null
                        val cellCount = if (dwidth > CELL) 2 else 1
                        if (cp >= 0) b.add(cp, cellCount, place(rows, bbx, cellCount, descent))
                    }
                }
            }
            return b.build()
        }

        /** Put a BDF bitmap (rows of hex) into a cellCount-wide, 16-high cell. */
        private fun place(rows: List<String>, bbx: IntArray, cellCount: Int, descent: Int): ByteArray {
            val (w, h, xOff, yOff) = bbx.toList()
            val out = ByteArray(HEIGHT * cellCount)
            val top = HEIGHT - descent - (h + yOff)  // rows from the cell's top to the bitmap's
            for (r in 0 until minOf(h, rows.size)) {
                val y = top + r
                if (y !in 0 until HEIGHT) continue
                val bits = rows[r].toBigInteger(16)
                val rowBits = rows[r].length * 4
                for (x in 0 until w) {
                    if (!bits.testBit(rowBits - 1 - x)) continue
                    val px = x + xOff
                    if (px !in 0 until cellCount * CELL) continue
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
