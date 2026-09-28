// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.band

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class BandTest {
    private val font = Fonts.source
    private val renderer = BandRenderer(font)
    private val full = BandData(
        batteryPercent = 100, charging = true, lightPercent = 100, roll = 359, trim = -180, fps = 12,
        device = "ES-123456", time = LocalDateTime.of(2026, 9, 28, 13, 5, 9), label = "left ear",
    )
    private val sparse = BandData(batteryPercent = 7, lightPercent = 0, roll = 3, trim = 0, fps = 9, time = LocalDateTime.of(2026, 1, 2, 3, 4, 5))

    private fun frame(w: Int = 480, h: Int = 480) = PixelImage(w, h, IntArray(w * h) { (0xFF shl 24) or (it * 2654435761L).toInt() and 0xFFFFFF })

    // --- layout ---

    @Test fun tagsSitInTheSameCellsWhateverTheValues() {
        fun tags(d: BandData) = BandLayout.place(d, font).filter { !it.bright }.map { Triple(it.row, it.col, it.codepoint) }
        assertEquals(tags(full), tags(sparse))
        assertEquals(tags(full), tags(BandData()))
    }

    @Test fun rightAlignedValuesEndInTheSameCell() {
        fun lastCol(d: BandData, row: Int, from: Int, to: Int) =
            BandLayout.place(d, font).filter { it.bright && it.row == row && it.col in from until to }.maxOf { it.col + it.cells }
        for ((from, to) in listOf(0 to 9, 10 to 20, 21 to 30, 31 to 41, 42 to 48, 50 to 58)) {
            assertEquals("field at $from", lastCol(full, 0, from, to), lastCol(sparse, 0, from, to))
            assertEquals(to, lastCol(full, 0, from, to))
        }
    }

    @Test fun everythingFitsTheGrid() {
        for (d in listOf(full, sparse, BandData(), full.copy(label = "x".repeat(200), device = "y".repeat(50)))) {
            for (p in BandLayout.place(d, font)) {
                assertTrue(p.col >= 0 && p.col + p.cells <= BandLayout.COLUMNS && p.row in 0 until BandLayout.ROWS)
            }
            // no two glyphs share a cell
            val cells = BandLayout.place(d, font).flatMap { p -> (0 until p.cells).map { p.row to p.col + it } }
            assertEquals(cells.size, cells.toSet().size)
        }
    }

    @Test fun longNamesAreCutWithAnEllipsis() {
        val placed = BandLayout.place(full.copy(label = "Maximiliana Rosalind Featherstonehaugh-Smythe"), font)
            .filter { it.row == 1 && it.col >= 30 }
        assertEquals(28, placed.sumOf { it.cells })
        assertEquals(0x2026, placed.last().codepoint)
        assertEquals("Maximiliana Rosalind Feathe", String(placed.dropLast(1).map { it.codepoint }.toIntArray(), 0, placed.size - 1))
    }

    @Test fun wideGlyphsAreNeverSplit() {
        // 16 wide characters = 32 cells: 13 fit before the ellipsis (26 cells + 1, of 28)
        val placed = BandLayout.place(full.copy(label = "山".repeat(16)), font).filter { it.row == 1 && it.col >= 30 }
        assertEquals(13, placed.count { it.codepoint == '山'.code })
        assertTrue(placed.sumOf { it.cells } <= 28)
        assertEquals(0x2026, placed.last().codepoint)
    }

    @Test fun nonLatinNamesUseTheFallbackFont() {
        val names = listOf("Жанна", "Ελένη", "山田花子", "José Müller", "محمد")
        for (name in names) {
            val placed = BandLayout.place(full.copy(label = name), font).filter { it.row == 1 && it.col >= 30 }
            for (p in placed) assertTrue("no glyph for U+%04X in $name".format(p.codepoint), font.has(p.codepoint))
        }
        // and it draws: the label area isn't blank
        val band = renderer.render(full.copy(label = "山田花子"), 480)
        val labelArea = (0 until band.height).flatMap { y -> (renderer.left(480) + 30 * 8 until 480).map { x -> band.pixels[y * 480 + x] } }
        assertTrue(labelArea.any { it == BandRenderer.VALUE })
    }

    // --- rendering and composing ---

    @Test fun writesAPreview() {
        // For eyeballing: build/band-preview.ppm, the band at 1x and 2x under a grey frame.
        val out = renderer.compose(PixelImage(480, 120, IntArray(480 * 120) { 0xFF404040.toInt() }), full.copy(label = "Жанна 山田 left ear"), band = true, circle = false)
        val f = java.io.File("build/band-preview.ppm")
        f.outputStream().buffered().use { o ->
            o.write("P6 ${out.width} ${out.height} 255\n".toByteArray())
            for (p in out.pixels) { o.write(p shr 16 and 0xFF); o.write(p shr 8 and 0xFF); o.write(p and 0xFF) }
        }
        assertTrue(f.length() > 0)
    }


    @Test fun bandSizeAndIntegerScale() {
        assertEquals(1, renderer.scale(480))
        assertEquals(40, renderer.height(480))
        assertEquals(2, renderer.scale(960))
        assertEquals(80, renderer.height(960))
        assertEquals(2, renderer.scale(1344))  // a phone screen: 2x, centred
        assertEquals(1, renderer.scale(320))
        assertEquals(8, renderer.left(480))    // one-cell margin
        assertEquals(16, renderer.left(960))
    }

    @Test fun twiceTheScaleIsTheSamePicturePixelDoubled() {
        val one = renderer.render(full, 480)
        val two = renderer.render(full, 960)
        for (y in 0 until two.height) for (x in 0 until two.width) {
            assertEquals(one.pixels[(y / 2) * 480 + x / 2], two.pixels[y * 960 + x])
        }
        // only three colours: no smoothing
        assertEquals(setOf(BandRenderer.BACKGROUND, BandRenderer.TAG, BandRenderer.VALUE), two.pixels.toSet())
    }

    @Test fun tagPixelsDontMoveWhenValuesChange() {
        val a = renderer.render(full, 480)
        val b = renderer.render(sparse, 480)
        val tagCells = BandLayout.place(full, font).filter { !it.bright }
        for (c in tagCells) for (gy in 0 until 16) for (gx in 0 until 8) {
            val i = ((BandRenderer.PAD + c.row * 16 + gy) * 480) + renderer.left(480) + c.col * 8 + gx
            assertEquals(a.pixels[i], b.pixels[i])
        }
        assertNotEquals(a.pixels.toList(), b.pixels.toList())
    }

    @Test fun composingAddsExactlyTheBandBelowUntouchedImagePixels() {
        val f = frame()
        val out = renderer.compose(f, full, band = true, circle = false)
        assertEquals(480, out.width)
        assertEquals(480 + renderer.height(480), out.height)
        assertArrayEquals(f.pixels, out.pixels.copyOfRange(0, f.pixels.size))
        assertArrayEquals(renderer.render(full, 480).pixels, out.pixels.copyOfRange(f.pixels.size, out.pixels.size))
    }

    @Test fun bothOffIsTheFrameItself() {
        val f = frame()
        assertSame(f, renderer.compose(f, full, band = false, circle = false))
    }

    @Test fun theCircleTouchesOnlyARingAtTheEdge() {
        val f = frame()
        val out = renderer.compose(f, full, band = false, circle = true)
        assertEquals(f.height, out.height)
        val changed = f.pixels.indices.filter { f.pixels[it] != out.pixels[it] }
        assertTrue(changed.isNotEmpty())
        for (i in changed) {
            val d = Math.hypot(i % 480 - 239.5, i / 480 - 239.5)
            assertTrue("pixel ${i % 480},${i / 480} at $d", kotlin.math.abs(d - 239.5) < 0.5)
            assertEquals(BandRenderer.CIRCLE, out.pixels[i])
        }
        // closed ring: every row that crosses the circle has a ring pixel on each side
        for (y in 1 until 479) assertEquals("row $y", 2, changed.count { it / 480 == y }.coerceAtMost(2))
    }
}
