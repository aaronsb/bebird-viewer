// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.band

import java.time.LocalDateTime

/** What the status band shows. Null values show as "--". */
data class BandData(
    val batteryPercent: Int? = null,
    val charging: Boolean = false,
    val lightPercent: Int? = null,
    val roll: Int? = null,
    val trim: Int = 0,
    val fps: Int? = null,
    /** Frames dropped in the last second; shown after the fps when non-zero. */
    val droppedPerSecond: Int = 0,
    val device: String? = null,
    val time: LocalDateTime? = null,
    val label: String? = null,
)

/**
 * The band's fixed grid: [COLUMNS] cells by [ROWS] rows (58 of the 60 cells across 480 px, a
 * one-cell margin each side), each field at a fixed column with a
 * fixed width, values right-aligned in it, so nothing moves when values change. Like an
 * ultrasound readout: dim tags, bright values.
 */
object BandLayout {
    const val COLUMNS = 58
    /** Cells across the 480-px width the band is designed for, margins included. */
    const val WIDTH_CELLS = 60
    const val ROWS = 2

    /** One field: [tag] (dim) then [value] (bright), in [width] cells from ([row], [col]). */
    data class Field(val row: Int, val col: Int, val width: Int, val tag: String, val value: String, val align: Align)

    enum class Align { RIGHT, LEFT }

    /** A code point placed at a cell; [cells] is 2 for a wide glyph. */
    data class Placed(val row: Int, val col: Int, val codepoint: Int, val cells: Int, val bright: Boolean)

    fun fields(d: BandData): List<Field> = listOf(
        // row 0: 0 BAT 100%+ | 10 LED 100% | 19 ROLL 359° | 29 TRIM +180° | 40 FPS 11 −3 | 50 12:34:56
        Field(0, 0, 9, "BAT", d.batteryPercent?.let { "$it%" + if (d.charging) "+" else " " } ?: "-- ", Align.RIGHT),
        Field(0, 10, 8, "LED", d.lightPercent?.let { if (it == 0) "OFF" else "$it%" } ?: "--", Align.RIGHT),
        Field(0, 19, 9, "ROLL", d.roll?.let { "$it°" } ?: "--", Align.RIGHT),
        Field(0, 29, 10, "TRIM", (if (d.trim > 0) "+" else "") + "${d.trim}°", Align.RIGHT),
        // fps, then frames dropped in the last second if any: "11 −3"
        Field(0, 40, 9, "FPS", (d.fps?.toString() ?: "--") + if (d.droppedPerSecond > 0) " \u2212${d.droppedPerSecond}" else "", Align.RIGHT),
        Field(0, 50, 8, "", d.time?.let { "%02d:%02d:%02d".format(it.hour, it.minute, it.second) } ?: "--:--:--", Align.RIGHT),
        // row 1: 0 device (18) | 19 date | 30 LABEL text (22 cells after the tag)
        Field(1, 0, 18, "", d.device ?: "--", Align.LEFT),
        Field(1, 19, 10, "", d.time?.let { "%04d-%02d-%02d".format(it.year, it.monthValue, it.dayOfMonth) } ?: "----------", Align.LEFT),
        Field(1, 30, 28, "LABEL", d.label?.ifEmpty { null } ?: "--", Align.LEFT),
    )

    /**
     * Every glyph of [d] at its cell. A value longer than its field is cut to fit, ending in
     * "…"; wide glyphs count two cells and are never split.
     */
    fun place(d: BandData, font: GlyphSource): List<Placed> = fields(d).flatMap { f ->
        val tagCells = if (f.tag.isEmpty()) 0 else f.tag.length + 1
        val room = f.width - tagCells
        val value = fit(f.value, room, font)
        val used = value.sumOf { font.cells(it) }
        val start = f.col + tagCells + if (f.align == Align.RIGHT) room - used else 0
        val out = ArrayList<Placed>()
        f.tag.forEachIndexed { i, c -> out += Placed(f.row, f.col + i, c.code, 1, bright = false) }
        var col = start
        for (cp in value) {
            val w = font.cells(cp)
            out += Placed(f.row, col, cp, w, bright = true)
            col += w
        }
        out
    }

    /** [text]'s code points, cut to [cells] cells with a trailing "…" if it doesn't fit. */
    fun fit(text: String, cells: Int, font: GlyphSource): List<Int> {
        val cps = text.codePoints().toArray().toList()
        if (cps.sumOf { font.cells(it) } <= cells) return cps
        val out = ArrayList<Int>()
        var used = 0
        for (cp in cps) {
            val w = font.cells(cp)
            if (used + w > cells - 1) break  // keep one cell for the ellipsis
            out += cp
            used += w
        }
        while (out.isNotEmpty() && Character.isWhitespace(out.last())) out.removeAt(out.size - 1)  // "Rosalind…", not "Rosalind …"
        return out + ELLIPSIS
    }

    private const val ELLIPSIS = 0x2026
}

/** A plain ARGB image: [pixels] row by row, [width] × [height]. */
class PixelImage(val width: Int, val height: Int, val pixels: IntArray) {
    init { require(pixels.size == width * height) }
}

/**
 * [g]'s set pixels with the top left of its cell at ([x0], [y0]) in a [width] × [height] ARGB
 * buffer, each as an [s] × [s] block of [color]; what falls outside the buffer is dropped.
 */
internal fun drawGlyph(px: IntArray, width: Int, height: Int, g: PixelFont.Glyph, x0: Int, y0: Int, s: Int, color: Int) {
    for (gy in 0 until PixelFont.HEIGHT) for (gx in 0 until g.cells * PixelFont.CELL) {
        if (!g.pixel(gx, gy)) continue
        for (dy in 0 until s) for (dx in 0 until s) {
            val x = x0 + gx * s + dx
            val y = y0 + gy * s + dy
            if (x in 0 until width && y in 0 until height) px[y * width + x] = color
        }
    }
}

/**
 * Draws the band and composes saved output. Pure: works on ARGB int arrays, so the screen
 * (via a Bitmap of the same pixels) and saved files look identical.
 *
 * The layout is designed for 480 px (the scope's frame width); any width of at least
 * [MIN_WIDTH] fits the whole grid. Narrower bands clip the right-hand fields.
 */
class BandRenderer(private val font: GlyphSource) {
    fun scale(width: Int) = Companion.scale(width)
    fun height(width: Int) = Companion.height(width)
    fun left(width: Int) = Companion.left(width)

    /** The band alone, [width] wide: black, glyphs drawn with integer scaling and no smoothing. */
    fun render(d: BandData, width: Int): PixelImage = renderInto(d, width, IntArray(width * height(width)))

    /** Like [render], drawing into [px] (width × height ints, reused between redraws). */
    fun renderInto(d: BandData, width: Int, px: IntArray): PixelImage {
        val s = scale(width)
        val h = height(width)
        require(px.size == width * h) { "buffer is ${px.size}, not $width x $h" }
        px.fill(BACKGROUND)
        val left = left(width)
        for (p in BandLayout.place(d, font)) {
            val g = font.glyph(p.codepoint) ?: continue
            val color = if (p.bright) VALUE else TAG
            drawGlyph(px, width, h, g, left + p.col * PixelFont.CELL * s, (PAD + p.row * PixelFont.HEIGHT) * s, s, color)
        }
        return PixelImage(width, h, px)
    }

    /**
     * Saved output with the overlay: the (already rotated) [frame] with a hair-thin circle at
     * the image circle's edge, the pixels outside that circle filled with the band's background
     * (so image and band read as one panel), and the band below, making the result
     * frame.width wide and frame.height + band height tall. Pixels inside the circle are copied
     * unchanged. With the overlay off, the frame itself is returned.
     *
     * [circle] is the image circle in frame pixels: by default the inscribed one; a zoomed crop
     * passes where the scope's circle falls in it (possibly partly outside the crop).
     */
    fun compose(frame: PixelImage, d: BandData, overlay: Boolean, circle: Circle = Circle.inscribed(frame.width, frame.height)): PixelImage {
        if (!overlay) return frame
        val w = frame.width
        val band = render(d, w)
        val out = IntArray(w * (frame.height + band.height))
        frame.pixels.copyInto(out)
        frameCircle(out, w, frame.height, circle)
        band.pixels.copyInto(out, w * frame.height)
        return PixelImage(w, frame.height + band.height, out)
    }

    /** A circle in pixel coordinates (pixel centres at integers). */
    data class Circle(val cx: Double, val cy: Double, val r: Double) {
        companion object {
            /** The circle inscribed in a w × h image. */
            fun inscribed(w: Int, h: Int) = Circle((w - 1) / 2.0, (h - 1) / 2.0, minOf(w, h) / 2.0 - 0.5)
        }
    }

    /**
     * In the w × h frame area of [px]: a ring about one pixel thin on [c] (pixel centres within
     * half a pixel of its radius), and the band's background outside it.
     */
    private fun frameCircle(px: IntArray, w: Int, h: Int, c: Circle) {
        for (y in 0 until h) for (x in 0 until w) {
            val d = Math.hypot(x - c.cx, y - c.cy) - c.r
            when {
                d >= 0.5 -> px[y * w + x] = BACKGROUND
                d > -0.5 -> px[y * w + x] = CIRCLE
            }
        }
    }

    companion object {
        const val PAD = 4
        /** Narrowest band that fits the whole grid at scale 1. */
        const val MIN_WIDTH = BandLayout.COLUMNS * PixelFont.CELL

        // Geometry depends only on the width, so the screen can reserve the band's space
        // before the fonts are loaded.

        /** The integer scale for a band [width] pixels wide: 1 at 480, 2 at 960 and so on. */
        fun scale(width: Int) = maxOf(1, width / (BandLayout.WIDTH_CELLS * PixelFont.CELL))

        /** Band height for [width]: two text rows plus padding, times the scale. */
        fun height(width: Int) = (BandLayout.ROWS * PixelFont.HEIGHT + 2 * PAD) * scale(width)

        /** Where the grid starts: centred, so a one-cell margin at 480 px (8 × scale px). */
        fun left(width: Int) = maxOf(0, (width - BandLayout.COLUMNS * PixelFont.CELL * scale(width)) / 2)

        const val BACKGROUND = 0xFF000000.toInt()
        const val TAG = 0xFF8C8C8C.toInt()
        const val VALUE = 0xFFE6E6E6.toInt()
        const val CIRCLE = 0xFFB4B4B4.toInt()
    }
}
