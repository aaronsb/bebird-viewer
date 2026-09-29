// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import androidx.compose.ui.geometry.Offset
import com.bockelie.bebird.R
import com.bockelie.bebird.focus.ScaleOverlay
import com.bockelie.bebird.focus.ScaleStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What TalkBack gets from the live view (#40): its description, zoom actions and the scale's description. */
class LiveViewA11yTest {
    @Test fun theZoomIsSaidOnlyWhenZoomedIn() {
        assertNull(zoomLabel(1f))
        assertNull(zoomLabel(1.04f))
        assertEquals("1.5", zoomLabel(1.5f))
        assertEquals("2", zoomLabel(2f))
        assertEquals("3", zoomLabel(2.97f))
        assertEquals("6", zoomLabel(6f))
    }

    @Test fun onlyActionsThatDoSomethingAreOffered() {
        assertEquals(listOf(ZoomAction.IN), zoomActions(1f, Offset.Zero))
        assertEquals(listOf(ZoomAction.IN, ZoomAction.RESET), zoomActions(1f, Offset(5f, 0f)))
        assertEquals(listOf(ZoomAction.IN, ZoomAction.OUT, ZoomAction.RESET), zoomActions(2f, Offset.Zero))
        assertEquals(listOf(ZoomAction.OUT, ZoomAction.RESET), zoomActions(6f, Offset.Zero))
    }

    @Test fun zoomStepsGoStopToStopWithinOneToSix() {
        fun z(a: ZoomAction, from: Float) = zoomStep(a, from, Offset.Zero, 1000f, 1000f, 1000f).first
        assertEquals(listOf(1.5f, 2f, 3f, 4f, 6f, 6f), listOf(1f, 1.5f, 2f, 3f, 4f, 6f).map { z(ZoomAction.IN, it) })
        assertEquals(2f, z(ZoomAction.IN, 1.7f))  // after a pinch: the next stop up
        assertEquals(listOf(1f, 1f, 1.5f, 2f, 3f, 4f), listOf(1f, 1.5f, 2f, 3f, 4f, 6f).map { z(ZoomAction.OUT, it) })
        assertEquals(1f to Offset.Zero, zoomStep(ZoomAction.RESET, 4f, Offset(300f, -200f), 1000f, 1000f, 1000f))
    }

    @Test fun theOffsetScalesAboutTheCentreAndStaysInBounds() {
        // square 1000-px viewport: zoom 2 -> 1.5 scales the offset by 0.75, inside the 250-px overhang
        assertEquals(1.5f to Offset(225f, -150f), zoomStep(ZoomAction.OUT, 2f, Offset(300f, -200f), 1000f, 1000f, 1000f))
        assertEquals(3f to Offset(750f, 0f), zoomStep(ZoomAction.IN, 2f, Offset(500f, 0f), 1000f, 1000f, 1000f))
        // tall viewport: x clamped to the 250-px overhang at 1.5x, y to 0 (no vertical overhang)
        assertEquals(1.5f to Offset(250f, 0f), zoomStep(ZoomAction.OUT, 2f, Offset(500f, 100f), 1000f, 2000f, 1000f))
    }

    @Test fun theScaleIsNamedWithItsLockState() {
        fun spoken(style: ScaleStyle, locked: Boolean) =
            scaleSpoken(ScaleOverlay.shapes(style, locked, close = true).filterNot(ScaleOverlay::isClose))
        assertEquals(ScaleKind.RING to false, spoken(ScaleStyle.RING, false))
        assertEquals(ScaleKind.RING to true, spoken(ScaleStyle.RING, true))
        assertEquals(ScaleKind.BOWTIE to false, spoken(ScaleStyle.BOWTIE, false))
        assertEquals(ScaleKind.BOWTIE to true, spoken(ScaleStyle.BOWTIE, true))
        assertEquals(ScaleKind.BAR to false, spoken(ScaleStyle.BAR, false))
        assertEquals(ScaleKind.BAR to true, spoken(ScaleStyle.BAR, true))
        assertNull(spoken(ScaleStyle.NONE, true))
        assertNull(scaleSpoken(emptyList()))
    }

    @Test fun theWording() {
        assertEquals("Live picture", AppStrings.text(R.string.live_picture))
        assertEquals("Live picture, zoom %1\$s×", AppStrings.text(R.string.live_picture_zoom))
        assertEquals(listOf("Zoom in", "Zoom out", "Reset zoom"), ZoomAction.entries.map { AppStrings.text(it.label) })
        assertEquals(
            listOf("Ring scale, 2 to 10 millimetres", "Bowtie scale, 2 to 10 millimetres", "Bar scale, 0 to 10 millimetres"),
            ScaleKind.entries.map { AppStrings.text(it.text) },
        )
        assertEquals(listOf("locked", "not locked", "%1\$s, %2\$s"), listOf(R.string.scale_locked, R.string.scale_not_locked, R.string.scale_description).map(AppStrings::text))
    }
}
