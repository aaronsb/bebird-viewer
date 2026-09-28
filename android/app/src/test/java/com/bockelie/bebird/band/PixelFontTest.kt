// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.band

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedReader
import java.io.StringReader

class PixelFontTest {
    private fun count(g: PixelFont.Glyph) = (0 until PixelFont.HEIGHT).sumOf { y -> (0 until g.cells * 8).count { x -> g.pixel(x, y) } }

    @Test fun terminusLoadsAndCoversTheReadout() {
        val t = Fonts.terminus
        assertEquals(1356, t.size)
        for (c in "BATLIGHTROLLTRIMFPS0123456789%+-:°… ") assertNotNull("missing '$c'", t.glyph(c.code))
        assertEquals(0, count(t.glyph(' '.code)!!))
        assertTrue(count(t.glyph('A'.code)!!) > 10)
        assertEquals(1, t.glyph('A'.code)!!.cells)
    }

    @Test fun unifontLoadsWideGlyphs() {
        val u = Fonts.unifont
        assertTrue(u.size > 50_000)
        assertEquals(2, u.glyph('山'.code)!!.cells)
        assertEquals(1, u.glyph('A'.code)!!.cells)
    }

    @Test fun fallbackFillsWhatTerminusLacks() {
        val s = Fonts.source
        assertNull(Fonts.terminus.glyph('山'.code))
        assertTrue(s.has('山'.code))
        assertEquals(2, s.cells('山'.code))
        assertTrue(s.has('Ж'.code))  // Cyrillic: Terminus has it
        // nothing has a private-use code point: the replacement glyph is drawn instead
        assertFalse(s.has(0xF8FF))
        assertSame(null, Fonts.terminus.glyph(0xF8FF))
        assertNotNull(s.glyph(0xF8FF))
    }

    @Test fun bdfPlacementUsesTheBoundingBox() {
        // one 3x2 glyph whose box starts 1 px right and sits on the baseline (descent 4)
        val bdf = """
            STARTFONT 2.1
            FONTBOUNDINGBOX 8 16 0 -4
            STARTCHAR x
            ENCODING 120
            DWIDTH 8 0
            BBX 3 2 1 0
            BITMAP
            E0
            A0
            ENDCHAR
            ENDFONT
        """.trimIndent()
        val g = PixelFont.parseBdf(BufferedReader(StringReader(bdf))).glyph('x'.code)!!
        // baseline is row 12 (16 - 4); a 2-high glyph at y offset 0 occupies rows 10 and 11
        assertTrue(g.pixel(1, 10) && g.pixel(2, 10) && g.pixel(3, 10))
        assertTrue(g.pixel(1, 11) && !g.pixel(2, 11) && g.pixel(3, 11))
        assertFalse(g.pixel(0, 10) || g.pixel(1, 9) || g.pixel(1, 12))
    }

    @Test fun malformedGlyphsAreSkippedNotFatal() {
        val hex = listOf(
            "0041:" + "00".repeat(16),      // good
            "0042:" + "ZZ".repeat(16),      // not hex, right length
            "0043:" + "00".repeat(15),      // too short
            "XYZ:" + "00".repeat(16),       // bad code point
            ":" + "00".repeat(16),          // no code point
            "0044:" + "00".repeat(16),      // good
        ).joinToString("\n")
        val h = PixelFont.parseHex(BufferedReader(StringReader(hex)))
        assertEquals(2, h.size)
        assertNotNull(h.glyph(0x41)); assertNotNull(h.glyph(0x44))

        fun char(enc: String, bbx: String, vararg rows: String) =
            "STARTCHAR c\nENCODING $enc\nDWIDTH 8 0\nBBX $bbx\nBITMAP\n" + rows.joinToString("\n") + "\nENDCHAR\n"
        val bdf = "STARTFONT 2.1\nFONTBOUNDINGBOX 8 16 0 -4\n" +
            char("65", "8 1 0 0", "FF") +              // good
            char("66", "8 1 0 0", "") +                // empty bitmap row
            char("67", "8 1 0 0", "GG") +              // not hex
            char("68", "16 1 0 0", "FF") +             // box wider than the row's bits
            char("69", "8 2 0 0", "FF") +              // fewer rows than the box
            char("70", "x 1 0 0", "FF") +              // bad BBX
            char("nope", "8 1 0 0", "FF") +            // bad encoding
            char("71", "8 1 0 0", "80") +              // good
            "ENDFONT\n"
        val b = PixelFont.parseBdf(BufferedReader(StringReader(bdf)))
        assertEquals(2, b.size)
        assertTrue(b.glyph(65)!!.pixel(7, 11))
        assertTrue(b.glyph(71)!!.pixel(0, 11))
    }

    @Test fun aRepeatedCodePointKeepsTheFirstGlyph() {
        val hex = "0041:" + "FF".repeat(16) + "\n0041:" + "00".repeat(16)
        assertTrue(PixelFont.parseHex(BufferedReader(StringReader(hex))).glyph(0x41)!!.pixel(0, 0))
    }

    @Test fun hexParsesNarrowAndWide() {
        val hex = "0041:" + "00".repeat(15) + "FF\n4E00:" + "8000".repeat(16) + "\nnonsense\n"
        val f = PixelFont.parseHex(BufferedReader(StringReader(hex)))
        assertEquals(2, f.size)
        assertTrue(f.glyph(0x41)!!.pixel(7, 15))
        assertFalse(f.glyph(0x41)!!.pixel(7, 14))
        assertEquals(2, f.glyph(0x4E00)!!.cells)
        assertTrue(f.glyph(0x4E00)!!.pixel(0, 5))
        assertFalse(f.glyph(0x4E00)!!.pixel(8, 5))
    }
}
