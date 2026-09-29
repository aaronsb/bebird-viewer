// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.band

import com.bockelie.bebird.band.ViewerPalette.Companion.contrast
import com.bockelie.bebird.focus.ScaleOverlay
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class ViewerPaletteTest {
    private val renderer = BandRenderer(Fonts.source)
    private val data = BandData(
        batteryPercent = 100, charging = true, lightPercent = 100, roll = 359, trim = -180, fps = 12,
        device = "ES-123456", time = LocalDateTime.of(2026, 9, 28, 13, 5, 9), label = "left ear",
    )

    @Test fun contrastIsWcags() {
        assertEquals(21.0, contrast(0xFFFFFFFF.toInt(), 0xFF000000.toInt()), 1e-9)
        assertEquals(1.0, contrast(0xFF777777.toInt(), 0xFF777777.toInt()), 1e-9)
        assertEquals(4.48, contrast(0xFF777777.toInt(), 0xFFFFFFFF.toInt()), 0.01)  // the classic just-fails grey
    }

    @Test fun everyTextColourReadsOnItsField() {
        for ((name, p) in listOf("dark" to ViewerPalette.DARK, "light" to ViewerPalette.LIGHT)) {
            for ((role, c) in listOf("tag" to p.tag, "value" to p.value, "warning" to p.warning, "note" to p.note, "battery" to p.batteryLow)) {
                val r = contrast(c, p.field)
                assertTrue("$name $role: %.2f:1".format(r), r >= 4.5)
                assertTrue("$name $role on its halo: %.2f:1".format(contrast(c, p.halo)), contrast(c, p.halo) >= 4.5)
            }
            // the circle's outline isn't text: 3:1 keeps it visible
            assertTrue("$name circle", contrast(p.circle, p.field) >= 3.0)
        }
    }

    @Test fun darkIsTheBandsOwnColours() {
        with(ViewerPalette.DARK) {
            assertEquals(listOf(BandRenderer.BACKGROUND, BandRenderer.TAG, BandRenderer.VALUE, BandRenderer.CIRCLE),
                listOf(field, tag, value, circle))
            assertEquals(ScaleOverlay.WARNING, warning)  // CLOSE's own yellow
        }
        // and the default for renderInto: the pinned hash (saved output) holds
        val px = IntArray(480 * BandRenderer.height(480))
        assertEquals(BandTest.PIN_480, renderer.renderInto(data, 480, px).pixels.contentHashCode())
        assertEquals(BandTest.PIN_480, renderer.renderInto(data, 480, px, ViewerPalette.DARK).pixels.contentHashCode())
    }

    @Test fun theLightBandDiffersOnlyInColour() {
        val dark = renderer.render(data, 480).pixels
        val light = renderer.renderInto(data, 480, IntArray(dark.size), ViewerPalette.LIGHT).pixels
        val map = mapOf(
            BandRenderer.BACKGROUND to ViewerPalette.LIGHT.field,
            BandRenderer.TAG to ViewerPalette.LIGHT.tag,
            BandRenderer.VALUE to ViewerPalette.LIGHT.value,
        )
        for (i in dark.indices) assertEquals("pixel $i", map.getValue(dark[i]), light[i])
        assertTrue(light.any { it == ViewerPalette.LIGHT.value } && light.any { it == ViewerPalette.LIGHT.tag })
    }

    @Test fun textOutlinesTakeTheGivenColour() {
        val text = PixelText(Fonts.source)
        val halo = ViewerPalette.LIGHT.halo
        val img = text.draw(listOf(PixelText.Line("I", 1, 1)), 10, 18, ViewerPalette.LIGHT.note, outline = true, outlineColor = halo)
        assertEquals(setOf(0, halo, ViewerPalette.LIGHT.note), img.pixels.toSet())
    }
}
