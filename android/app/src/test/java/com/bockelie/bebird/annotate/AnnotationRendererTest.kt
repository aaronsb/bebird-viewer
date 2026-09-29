// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.annotate

import com.bockelie.bebird.band.BandData
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.Fonts
import com.bockelie.bebird.band.PixelImage
import com.bockelie.bebird.capture.Frames
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class AnnotationRendererTest {
    private val renderer = AnnotationRenderer(Fonts.source)
    private val red = Palette.RED
    private val black = AnnotationRenderer.BLACK
    private val clear = 0

    private fun PixelImage.at(x: Int, y: Int) = pixels[y * width + x]

    /** Coloured pixels' mean position, for comparing a mark's place at two sizes. */
    private fun PixelImage.centroid(color: Int): Pair<Double, Double> {
        var sx = 0.0; var sy = 0.0; var n = 0
        for (y in 0 until height) for (x in 0 until width) if (at(x, y) == color) { sx += x + 0.5; sy += y + 0.5; n++ }
        assertTrue("no pixels of that colour", n > 0)
        return sx / n to sy / n
    }

    @Test fun strokeSizesFollowTheWidth() {
        assertEquals(1.5, AnnotationRenderer.half(480), 1e-9)
        assertEquals(1.0, AnnotationRenderer.half(100), 1e-9)  // never under a 2-px stroke
        assertEquals(1.0, AnnotationRenderer.edge(480), 1e-9)
        assertEquals(2, AnnotationRenderer.textScale(480))
        assertEquals(1, AnnotationRenderer.textScale(100))
    }

    @Test fun aBoxIsItsOutlineWithABlackEdgeAndAClearInside() {
        val img = renderer.render(listOf(Mark.Box(Pt(0.25f, 0.25f), Pt(0.75f, 0.75f), red)), 100, 100)
        // the left side sits on x = 25: pixels 24 and 25 are within the 1-px half-width
        assertEquals(red, img.at(24, 50)); assertEquals(red, img.at(25, 50))
        assertEquals(black, img.at(23, 50)); assertEquals(black, img.at(26, 50))
        assertEquals(clear, img.at(21, 50)); assertEquals(clear, img.at(50, 50))
        // corners are drawn too
        assertEquals(red, img.at(25, 25)); assertEquals(red, img.at(74, 74))
    }

    @Test fun cornersMayBeGivenInAnyOrder() {
        val a = renderer.render(listOf(Mark.Box(Pt(0.25f, 0.25f), Pt(0.75f, 0.75f), red)), 100, 100)
        val b = renderer.render(listOf(Mark.Box(Pt(0.75f, 0.25f), Pt(0.25f, 0.75f), red)), 100, 100)
        assertArrayEquals(a.pixels, b.pixels)
    }

    @Test fun anEllipseTouchesItsBoxSidesButNotItsCorners() {
        val img = renderer.render(listOf(Mark.Ellipse(Pt(0.25f, 0.25f), Pt(0.75f, 0.75f), red)), 100, 100)
        assertEquals(red, img.at(75, 50)); assertEquals(red, img.at(50, 25))
        assertEquals(clear, img.at(50, 50))
        assertEquals(clear, img.at(26, 26))
    }

    @Test fun anArrowHasItsHeadAtTheEnd() {
        // left to right along y = 50, from x = 40 to the head at x = 160
        val img = renderer.render(listOf(Mark.Arrow(Pt(0.2f, 0.5f), Pt(0.8f, 0.5f), red)), 200, 100)
        assertEquals(red, img.at(80, 50)); assertEquals(red, img.at(40, 50))
        // the barbs spread back from the tip, above and below the shaft; nothing past the tip
        val len = 200 * AnnotationRenderer.HEAD
        val dy = (len * Math.sin(Math.toRadians(AnnotationRenderer.HEAD_DEG)) * 0.8).toInt()
        val dx = (len * Math.cos(Math.toRadians(AnnotationRenderer.HEAD_DEG)) * 0.8).toInt()
        assertEquals(red, img.at(160 - dx, 50 - dy)); assertEquals(red, img.at(160 - dx, 50 + dy))
        assertEquals(clear, img.at(40 + dx, 50 - dy))  // no head at the tail
        assertEquals(clear, img.at(166, 50))
    }

    @Test fun thePenFollowsItsPoints() {
        val pts = listOf(Pt(0.1f, 0.1f), Pt(0.5f, 0.1f), Pt(0.5f, 0.9f))
        val img = renderer.render(listOf(Mark.Pen(pts, red)), 100, 100)
        assertEquals(red, img.at(10, 10)); assertEquals(red, img.at(30, 10)); assertEquals(red, img.at(50, 50))
        assertEquals(clear, img.at(30, 50))
        // a joint has no black notch from the next piece's edge
        assertEquals(red, img.at(50, 10))
    }

    @Test fun textIsDrawnAtTheTapWithAnOutline() {
        val img = renderer.render(listOf(Mark.Text(Pt(0.1f, 0.5f), "AB", red)), 480, 480)
        val k = AnnotationRenderer.textScale(480)
        var colored = 0
        for (y in 0 until 480) for (x in 0 until 480) {
            val p = img.at(x, y)
            if (p == clear) continue
            if (p == red) colored++
            // two cells wide from x = 48, a glyph's height centred on y = 240, plus the outline
            assertTrue("($x, $y)", x in 48 - k until 48 + 2 * 8 * k + k)
            assertTrue("($x, $y)", y in 240 - 8 * k - k until 240 + 8 * k + k)
        }
        assertTrue(colored > 20)
    }

    @Test fun theScreenLayerAndTheSavedFileAgreeOnPlace() {
        val marks = listOf(Mark.Ellipse(Pt(0.3f, 0.2f), Pt(0.7f, 0.5f), red))
        val (fx, fy) = renderer.render(marks, 480, 480).centroid(red)
        val (sx, sy) = renderer.render(marks, 1080, 1080).centroid(red)
        assertEquals(fx * 1080 / 480, sx, 1.0)
        assertEquals(fy * 1080 / 480, sy, 1.0)
    }

    @Test fun paintDrawsOnACopyAndOnlyInsideTheArea() {
        val grey = 0xFF808080.toInt()
        val image = PixelImage(100, 130, IntArray(100 * 130) { grey })  // a 100 x 100 frame over a 30-px band
        // an arrow running off the bottom of the frame, towards the band
        val out = renderer.paint(image, listOf(Mark.Arrow(Pt(0.5f, 0.5f), Pt(0.5f, 1f), red)), 100, 100)
        assertTrue(image.pixels.all { it == grey })
        assertEquals(red, out.at(50, 99))
        for (y in 100 until 130) for (x in 0 until 100) assertEquals(grey, out.at(x, y))
    }

    @Test fun noMarksLeaveThePictureAsItWas() {
        val image = PixelImage(10, 10, IntArray(100) { it })
        assertArrayEquals(image.pixels, renderer.paint(image, emptyList()).pixels)
    }

    // --- the two saved pictures ---

    private val grey = 0xFF808080.toInt()
    private val upright = PixelImage(480, 480, IntArray(480 * 480) { grey })
    private val band = BandRenderer(Fonts.source)
    private val data = BandData(batteryPercent = 80, lightPercent = 50, roll = 12, device = "ES", time = LocalDateTime.of(2026, 9, 29, 10, 0, 0))
    private val marks = listOf(Mark.Box(Pt(0.3f, 0.3f), Pt(0.7f, 0.7f), red), Mark.Arrow(Pt(0.5f, 0.5f), Pt(0.5f, 1f), red))

    @Test fun theOriginalIsTheSnapshotAndTheCopyAddsOnlyTheMarks() {
        val (plain, annotated) = annotatedStills(upright, band, data, true, marks, renderer)
        val snapshot = Frames.composed(upright, band, data, true)
        assertArrayEquals(snapshot.pixels, plain.pixels)
        assertFalse(red in plain.pixels)
        assertEquals(plain.width, annotated.width); assertEquals(plain.height, annotated.height)
        assertEquals(red, annotated.at(144, 240))  // the box's left side, x = 0.3 * 480
        // the band below the frame is untouched, though the arrow runs to the frame's bottom edge
        val bandFrom = 480 * 480
        assertArrayEquals(plain.pixels.copyOfRange(bandFrom, plain.pixels.size), annotated.pixels.copyOfRange(bandFrom, annotated.pixels.size))
        assertEquals(red, annotated.at(240, 479))
    }

    @Test fun withTheOverlayOffTheOriginalIsTheFrameItself() {
        val (plain, annotated) = annotatedStills(upright, band, data, false, marks, renderer)
        assertSame(upright, plain)
        assertEquals(480, annotated.height)
        assertEquals(red, annotated.at(144, 240))
    }
}
