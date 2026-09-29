// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import com.bockelie.bebird.band.Fonts
import com.bockelie.bebird.band.ViewerPalette
import com.bockelie.bebird.focus.OverlayRenderer
import com.bockelie.bebird.focus.OverlayShape
import com.bockelie.bebird.focus.ScaleOverlay
import com.bockelie.bebird.focus.ScaleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ClosePaletteTest {
    private val close = ScaleOverlay.shapes(ScaleStyle.NONE, locked = false, close = true)

    @Test fun darkLeavesCloseAsItWas() {
        assertEquals(close, closeInPalette(close, ViewerPalette.DARK))
    }

    @Test fun lightRecoloursTheLabelNotTheTriangle() {
        val light = closeInPalette(close, ViewerPalette.LIGHT)
        assertEquals(close.filterIsInstance<OverlayShape.Warning>(), light.filterIsInstance<OverlayShape.Warning>())
        assertEquals(listOf(ViewerPalette.LIGHT.warning), light.filterIsInstance<OverlayShape.Label>().map { it.color })
    }

    @Test fun theLabelsHaloIsTheLightField() {
        val renderer = OverlayRenderer(Fonts.source)
        val halo = ViewerPalette.LIGHT.halo
        val img = renderer.render(closeInPalette(close, ViewerPalette.LIGHT), 480, 480, 1.0, labelOutline = halo)
        fun at(x: Int, y: Int) = img.pixels[y * 480 + x]
        // "CLOSE", anchored left-middle at (66, 34): dark amber on a light halo, no black
        val label = (24 until 44).flatMap { y -> (64 until 112).map { x -> at(x, y) } }
        assertTrue(label.count { it == ViewerPalette.LIGHT.warning } > 30)
        assertTrue(label.any { it == halo })
        assertTrue(label.none { it == OverlayRenderer.BLACK })
        // the triangle keeps its black edge and "!"
        assertEquals(OverlayRenderer.BLACK, at(18 + 20, 16 + 20))
    }
}
