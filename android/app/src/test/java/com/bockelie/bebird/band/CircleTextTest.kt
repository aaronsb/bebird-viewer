// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class CircleTextTest {
    private val text = CircleText(Fonts.source)

    @Test fun oneShortParagraphSitsInTheMiddle() {
        // 64 px: one row, its 16-px cell from y 24; "I" is one 8-px cell from x 28
        assertEquals(listOf(CircleText.Line("I", 28, 24)), text.layout(listOf("I"), 64))
    }

    @Test fun linesWrapToTheChordAtTheirHeight() {
        // 100 px: four rows from y 12, 20 apart; the middle rows are wider than the outer ones,
        // so "CCCC DDDD" shares row 2 and "EEEE" goes to the narrower row 3. Row 1 is the blank
        // between paragraphs.
        assertEquals(
            listOf(CircleText.Line("AB", 42, 12), CircleText.Line("CCCC DDDD", 14, 52), CircleText.Line("EEEE", 34, 72)),
            text.layout(listOf("AB", "CCCC DDDD EEEE"), 100),
        )
    }

    @Test fun aWordTooLongForItsRowIsBroken() {
        assertEquals(
            listOf(CircleText.Line("ABCDEFGHI", 14, 32), CircleText.Line("JKLMNOP", 22, 52)),
            text.layout(listOf("ABCDEFGHIJKLMNOP"), 100),
        )
    }

    @Test fun everyLineStaysInsideTheCircle() {
        val paragraphs = listOf("WAITING FOR PICTURE…", "Is it on and nearby? Tap Connect to try again, or pick another scope")
        for (side in listOf(96, 120, 160, 240, 480, 959)) {
            val lines = text.layout(paragraphs, side) ?: continue
            val r = side / 2.0
            for (l in lines) {
                val w = text.width(l.text)
                for ((x, y) in listOf(l.x to l.y, l.x + w to l.y, l.x to l.y + PixelFont.HEIGHT, l.x + w to l.y + PixelFont.HEIGHT)) {
                    assertTrue("side $side: '${l.text}' corner ($x, $y) outside", Math.hypot(x - r, y - r) <= r)
                }
            }
            // nothing lost or reordered, whatever the wrapping
            assertEquals("side $side", paragraphs.joinToString(" "), lines.joinToString(" ") { it.text })
        }
        assertEquals(2, text.layout(paragraphs, 959)!!.size)  // roomy: one line each
    }

    @Test fun nothingWhenTheCircleIsTooSmall() {
        assertNull(text.layout(listOf("NOT CONNECTED"), 16))
        assertTrue(text.render(listOf("NOT CONNECTED"), 16).pixels.all { it == 0 })
    }

    @Test fun renderDrawsTheGlyphsPixelForPixel() {
        val img = text.render(listOf("I"), 64)
        val g = Fonts.source.glyph('I'.code)!!
        var set = 0
        for (y in 0 until 64) for (x in 0 until 64) {
            val inGlyph = x in 28 until 36 && y in 24 until 40 && g.pixel(x - 28, y - 24)
            assertEquals("($x, $y)", if (inGlyph) BandRenderer.VALUE else 0, img.pixels[y * 64 + x])
            if (inGlyph) set++
        }
        assertTrue(set > 0)
        assertTrue(text.render(listOf("I"), 64, BandRenderer.TAG).pixels.filter { it != 0 }.all { it == BandRenderer.TAG })
    }

    @Test fun ellipsisAndApostropheFallBackWhenNoFontHasThem() {
        assertEquals("A… B’s", text.displayable("A… B’s"))
        val bare = PixelFont.parseBdf(BufferedReader(StringReader("""
            STARTFONT 2.1
            FONTBOUNDINGBOX 8 16 0 -4
            STARTCHAR question
            ENCODING 63
            DWIDTH 8 0
            BBX 8 16 0 -4
            BITMAP
            ${"FF\n".repeat(16).trim()}
            ENDCHAR
            ENDFONT
        """.trimIndent())))
        assertEquals("A... B's", CircleText(GlyphSource(bare)).displayable("A… B’s"))
    }
}
