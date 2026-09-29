// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import com.bockelie.bebird.band.Fonts
import com.bockelie.bebird.band.PixelText
import com.bockelie.bebird.focus.OverlayRenderer
import com.bockelie.bebird.focus.ScaleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The saved scale's note, placed once for the file and for the annotate screen (#51). */
class ScaleNoteTest {
    private val text = PixelText(Fonts.source)
    private val stamp = ScaleStamp(OverlayRenderer(Fonts.source), text)

    @Test fun theNotesPlaceOnAFullFrame() {
        // "APPROX. SCALE" (104 px) over "needs focus" (88), right-aligned, a pixel of outline round them
        assertEquals(
            ScaleStamp.Note(1, 373, 1, 106, 38, listOf(PixelText.Line("APPROX. SCALE", 1, 1), PixelText.Line("needs focus", 17, 21))),
            ScaleStamp.note(text, 480),
        )
        assertEquals(2, ScaleStamp.note(text, 960)!!.scale)
    }

    @Test fun theScaleAloneHasNoNoteAndTheLayerDoes() {
        // what annotate shows (the scale only) leaves the note's corner empty; the saved layer fills it
        val scale = SavedScale(ScaleStyle.RING, locked = true)
        val alone = stamp.scaleOnly(scale, 480, 480)
        val layer = stamp.layer(scale, 480, 480)
        fun corner(img: com.bockelie.bebird.band.PixelImage) = (1 until 39).flatMap { y -> (373 until 479).map { x -> img.pixels[y * 480 + x] } }
        assertTrue(corner(alone).all { it == 0 })
        assertTrue(corner(layer).count { it != 0 } > 50)
        // outside the corner the two are the same pixels
        for (i in alone.pixels.indices) {
            val x = i % 480; val y = i / 480
            if (x in 373 until 479 && y in 1 until 39) continue
            assertEquals("pixel ($x, $y)", alone.pixels[i], layer.pixels[i])
        }
    }
}
