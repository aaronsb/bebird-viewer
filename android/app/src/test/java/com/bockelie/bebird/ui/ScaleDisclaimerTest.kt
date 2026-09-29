// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import com.bockelie.bebird.R
import com.bockelie.bebird.band.Fonts
import com.bockelie.bebird.band.PixelText
import com.bockelie.bebird.focus.ScaleDisclaimer
import com.bockelie.bebird.focus.ScaleOverlay
import com.bockelie.bebird.focus.ScaleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleDisclaimerTest {
    private val text = PixelText(Fonts.source)
    private val paragraphs = listOf(R.string.scale_note_title, R.string.scale_note_focus, R.string.scale_note_sensor).map(AppStrings::text)

    /** CLOSE's scale and width for a viewport, as ZoomableCircle has them. */
    private fun place(w: Int, h: Int): ScaleDisclaimer.Placement? {
        val k = maxOf(1, minOf(w, h) / 480)
        return ScaleDisclaimer.place(text, paragraphs, w, h, k, 112 * k)
    }

    @Test fun shownOnlyWithAFrameAndAScale() {
        val ring = ScaleOverlay.shapes(ScaleStyle.RING, locked = false, close = false)
        assertTrue(ScaleDisclaimer.shown(hasFrame = true, ring))
        assertTrue(ScaleDisclaimer.shown(hasFrame = true, ScaleOverlay.shapes(ScaleStyle.BAR, locked = true, close = true)))
        assertFalse("no frame", ScaleDisclaimer.shown(hasFrame = false, ring))
        assertFalse("estimation off: no shapes", ScaleDisclaimer.shown(hasFrame = true, ScaleOverlay.forFrame(null, ScaleStyle.RING, true)))
        assertFalse("style Off, CLOSE only", ScaleDisclaimer.shown(hasFrame = true, ScaleOverlay.shapes(ScaleStyle.NONE, locked = false, close = true)))
        assertFalse("style Off", ScaleDisclaimer.shown(hasFrame = true, ScaleOverlay.shapes(ScaleStyle.NONE, locked = false, close = false)))
    }

    @Test fun theWording() {
        assertEquals(listOf("APPROXIMATE SCALE", "Valid only when in focus", "No distance sensor"), paragraphs)
    }

    @Test fun terminusHasEveryGlyph() {
        for (p in paragraphs) for (cp in p.codePoints()) {
            assertNotNull("no Terminus glyph for U+%04X".format(cp), Fonts.terminus.glyph(cp))
        }
    }

    @Test fun threeRightAlignedLinesOnAPhone() {
        // 1080 px square: CLOSE's scale 2; the widest line (24 cells, 192 px) sets the image width
        assertEquals(
            ScaleDisclaimer.Placement(
                2, 194, 58,
                listOf(
                    PixelText.Line("APPROXIMATE SCALE", 57, 1),
                    PixelText.Line("Valid only when in focus", 1, 21),
                    PixelText.Line("No distance sensor", 49, 41),
                ),
            ),
            place(1080, 1080),
        )
    }

    @Test fun aNarrowViewportWrapsIntoMoreRows() {
        assertEquals(
            listOf("APPROXIMATE SCALE", "Valid only when", "in focus", "No distance", "sensor"),
            place(320, 300)!!.lines.map { it.text },
        )
        assertNull("too small for the note at all", place(200, 200))
    }

    @Test fun fitsItsCornerClearOfClose() {
        for ((w, h) in listOf(1080 to 1080, 1080 to 1500, 1440 to 2000, 2400 to 900, 2000 to 700, 720 to 720, 480 to 480, 320 to 300)) {
            val p = place(w, h)
            assertNotNull("${w}x$h", p)
            p!!
            val k = maxOf(1, minOf(w, h) / 480)
            val s = p.scale
            val left = w - ScaleDisclaimer.INSET * s - p.width * s
            assertTrue("${w}x$h: overlaps CLOSE", left >= 112 * k + ScaleDisclaimer.INSET * s)
            assertTrue("${w}x$h: wider than half", p.width * s <= w / 2)
            assertTrue("${w}x$h: taller than a third", ScaleDisclaimer.INSET * s + p.height * s <= h / 3 + ScaleDisclaimer.INSET * s)
            assertTrue("${w}x$h: larger than CLOSE's scale", s in 1..k)
            for (l in p.lines) {
                assertTrue("${w}x$h: '${l.text}' outside the image", l.x >= 1 && l.x + text.width(l.text) <= p.width - 1 && l.y >= 1 && l.y + 16 <= p.height - 1)
            }
            // right-aligned: every line ends at the same column
            assertEquals("${w}x$h", 1, p.lines.map { it.x + text.width(it.text) }.distinct().size)
            assertEquals("${w}x$h", paragraphs.joinToString(" "), p.lines.joinToString(" ") { it.text })
        }
    }
}
