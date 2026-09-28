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

    private fun frame(w: Int = 480, h: Int = 480) = PixelImage(w, h, IntArray(w * h) { (0xFF shl 24) or ((it * 2654435761L).toInt() and 0xFFFFFF) })

    // --- layout ---

    @Test fun tagsSitInTheSameCellsWhateverTheValues() {
        fun tags(d: BandData) = BandLayout.place(d, font).filter { !it.bright }.map { Triple(it.row, it.col, it.codepoint) }
        assertEquals(tags(full), tags(sparse))
        assertEquals(tags(full), tags(BandData()))
    }

    @Test fun rightAlignedValuesEndInTheSameCell() {
        fun lastCol(d: BandData, row: Int, from: Int, to: Int) =
            BandLayout.place(d, font).filter { it.bright && it.row == row && it.col in from until to }.maxOf { it.col + it.cells }
        for ((from, to) in listOf(0 to 9, 10 to 18, 19 to 28, 29 to 39, 40 to 49, 50 to 58)) {
            assertEquals("field at $from", lastCol(full, 0, from, to), lastCol(sparse, 0, from, to))
            assertEquals(to, lastCol(full, 0, from, to))
        }
    }

    @Test fun theLabelIsATaggedFieldOfItsOwn() {
        fun row1(d: BandData) = BandLayout.place(d, font).filter { it.row == 1 && it.col >= 30 }
        fun text(p: List<BandLayout.Placed>) = String(p.map { it.codepoint }.toIntArray(), 0, p.size)
        val set = row1(full.copy(label = "left ear"))
        assertEquals("LABEL", text(set.filter { !it.bright }))
        assertEquals((30 until 35).toList(), set.filter { !it.bright }.map { it.col })
        assertEquals("left ear", text(set.filter { it.bright }))
        assertEquals(36, set.first { it.bright }.col)  // left-aligned right after the tag
        // empty or missing: the tag stays, with "--" like the other fields
        for (empty in listOf(full.copy(label = null), full.copy(label = ""))) {
            val p = row1(empty)
            assertEquals("LABEL", text(p.filter { !it.bright }))
            assertEquals("--", text(p.filter { it.bright }))
            assertEquals(36, p.first { it.bright }.col)
        }
    }

    @Test fun droppedFramesShowAfterTheFpsInTheSameField() {
        fun fps(d: BandData) = BandLayout.place(d, font).filter { it.row == 0 && it.col in 40 until 49 && it.bright }
        val with = fps(full.copy(fps = 11, droppedPerSecond = 3))
        assertEquals("11 \u22123", String(with.map { it.codepoint }.toIntArray(), 0, with.size))
        assertEquals(49, with.maxOf { it.col + it.cells })
        assertEquals(49, fps(full.copy(fps = 11)).maxOf { it.col + it.cells })  // same right edge without
        // the tag doesn't move
        assertEquals(
            BandLayout.place(full, font).filter { !it.bright }.map { it.col },
            BandLayout.place(full.copy(droppedPerSecond = 12), font).filter { !it.bright }.map { it.col },
        )
    }

    @Test fun renderIntoReusesTheBuffer() {
        val px = IntArray(480 * renderer.height(480))
        val a = renderer.renderInto(full, 480, px)
        assertSame(px, a.pixels)
        assertArrayEquals(renderer.render(full, 480).pixels, px)
        renderer.renderInto(sparse, 480, px)  // a redraw clears what was there
        assertArrayEquals(renderer.render(sparse, 480).pixels, px)
    }

    @Test fun geometryNeedsNoFont() {
        assertEquals(renderer.height(1344), BandRenderer.height(1344))
        assertEquals(464, BandRenderer.MIN_WIDTH)
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
            .filter { it.row == 1 && it.col >= 30 && it.bright }
        assertEquals(36, placed.first().col)  // after "LABEL "
        assertTrue(placed.last().col + placed.last().cells <= 58)
        assertEquals(0x2026, placed.last().codepoint)
        // cut to the 22-cell field, trailing space dropped before the ellipsis
        assertEquals("Maximiliana Rosalind", String(placed.dropLast(1).map { it.codepoint }.toIntArray(), 0, placed.size - 1))
    }

    @Test fun wideGlyphsAreNeverSplit() {
        // 16 wide characters = 32 cells: 10 fit before the ellipsis (20 cells + 1, of 22)
        val placed = BandLayout.place(full.copy(label = "山".repeat(16)), font).filter { it.row == 1 && it.col >= 30 && it.bright }
        assertEquals(10, placed.count { it.codepoint == '山'.code })
        assertTrue(placed.sumOf { it.cells } <= 22)
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
        val out = renderer.compose(PixelImage(480, 480, IntArray(480 * 480) { 0xFF404040.toInt() }), full.copy(label = "Жанна 山田 left ear", fps = 11, droppedPerSecond = 3), overlay = true)
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

    /** Where a frame pixel falls relative to the inscribed circle's ring. */
    private fun ringDistance(i: Int, w: Int = 480) = Math.hypot(i % w - (w - 1) / 2.0, i / w - (w - 1) / 2.0) - (w / 2.0 - 0.5)

    @Test fun theOverlayAddsExactlyTheBandBelow() {
        val f = frame()
        val out = renderer.compose(f, full, overlay = true)
        assertEquals(480, out.width)
        assertEquals(480 + renderer.height(480), out.height)
        assertArrayEquals(renderer.render(full, 480).pixels, out.pixels.copyOfRange(f.pixels.size, out.pixels.size))
    }

    @Test fun theOverlayLeavesThePictureInsideTheCircleUntouched() {
        val f = frame()
        val out = renderer.compose(f, full, overlay = true)
        var inside = 0
        for (i in f.pixels.indices) if (ringDistance(i) <= -0.5) {
            assertEquals("pixel ${i % 480},${i / 480}", f.pixels[i], out.pixels[i])
            inside++
        }
        assertTrue(inside > 170_000)  // about pi * 239^2
    }

    @Test fun theOverlayDrawsAThinRingAndBandColourOutside() {
        val out = renderer.compose(frame(), full, overlay = true)
        for (i in 0 until 480 * 480) {
            val d = ringDistance(i)
            when {
                d >= 0.5 -> assertEquals(BandRenderer.BACKGROUND, out.pixels[i])
                d > -0.5 -> assertEquals(BandRenderer.CIRCLE, out.pixels[i])
            }
        }
        // the corners, and every row's ends, are band colour; the ring is closed in every row
        assertEquals(BandRenderer.BACKGROUND, out.pixels[0])
        assertEquals(BandRenderer.BACKGROUND, out.pixels[480 * 480 - 1])
        for (y in 0 until 480) assertTrue("row $y", (0 until 480).count { out.pixels[y * 480 + it] == BandRenderer.CIRCLE } >= 2)
    }

    @Test fun overlayOffIsTheFrameItself() {
        val f = frame()
        assertSame(f, renderer.compose(f, full, overlay = false))
    }
}
