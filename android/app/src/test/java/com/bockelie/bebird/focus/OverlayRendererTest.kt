// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import com.bockelie.bebird.band.Fonts
import com.bockelie.bebird.band.PixelImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/** The proximity overlay's primitives drawn into a pixel buffer, the band's way. */
class OverlayRendererTest {
    private val renderer = OverlayRenderer(Fonts.source)
    private val c = FrameGeometry.CENTER.toDouble()

    private fun PixelImage.at(x: Int, y: Int) = pixels[y * width + x]

    /** The colour at angle [deg] (clockwise from +x) on a circle of radius [r] about the centre. */
    private fun PixelImage.onCircle(r: Double, deg: Double, f: Double = 1.0): Int {
        val a = Math.toRadians(deg)
        return at(((c + r * cos(a)) * f).roundToInt(), ((c + r * sin(a)) * f).roundToInt())
    }

    private fun ring(locked: Boolean, f: Double = 1.0): PixelImage {
        val size = (FrameGeometry.SIZE * f).toInt()
        return renderer.render(ScaleOverlay.shapes(ScaleStyle.RING, locked, close = false), size, size, f)
    }

    @Test fun writesAPreview() {
        // For eyeballing: build/proximity-preview.ppm, each style (unlocked, locked) over grey, CLOSE on the last.
        val tiles = listOf(ScaleStyle.RING to false, ScaleStyle.RING to true, ScaleStyle.BOWTIE to true, ScaleStyle.BAR to true)
        val w = 480 * tiles.size
        val out = IntArray(w * 480) { 0xFF505050.toInt() }
        tiles.forEachIndexed { i, (style, locked) ->
            val img = renderer.render(ScaleOverlay.shapes(style, locked, close = i == tiles.size - 1), 480, 480, 1.0)
            for (y in 0 until 480) for (x in 0 until 480) img.at(x, y).takeIf { it != 0 }?.let { out[y * w + i * 480 + x] = it }
        }
        java.io.File("build/proximity-preview.ppm").outputStream().buffered().use { o ->
            o.write("P6 $w 480 255\n".toByteArray())
            for (p in out) { o.write(p shr 16 and 0xFF); o.write(p shr 8 and 0xFF); o.write(p and 0xFF) }
        }
    }

    @Test fun ringsAtTheScale() {
        val img = ring(locked = true)
        // 1 mm radius is 40 px: the first ring, in lock colour; half-way between rings, nothing
        assertEquals(ScaleOverlay.LOCK, img.at(240 + 40, 240))
        assertEquals(ScaleOverlay.LOCK, img.at(240 + 120, 240))
        assertEquals(0, img.at(240 + 60, 240))
        assertEquals(0, img.at(240, 240))  // the centre is left clear
    }

    @Test fun theCrosshairMarksTheCentreAndLeavesItClear() {
        val locked = ring(locked = true)
        for ((dx, dy) in listOf(10 to 0, -10 to 0, 0 to 10, 0 to -10)) assertEquals("($dx, $dy)", ScaleOverlay.LOCK, locked.at(240 + dx, 240 + dy))
        // locked arms are 2 px thick, like the locked rings: rows 240-241, columns 239-240
        for (x in listOf(230, 250)) assertEquals(listOf(0, ScaleOverlay.LOCK, ScaleOverlay.LOCK, 0), (239..242).map { locked.at(x, it) })
        for (y in listOf(230, 250)) assertEquals(listOf(0, ScaleOverlay.LOCK, ScaleOverlay.LOCK, 0), (238..241).map { locked.at(it, y) })
        for (d in listOf(0, 1, 2)) assertEquals("gap $d", 0, locked.at(240 + d, 240))
        assertEquals("past the arm", 0, locked.at(240 + 20, 240))
        assertEquals("not diagonal", 0, locked.at(240 + 10, 240 + 10))
        val unlocked = ring(locked = false)
        assertEquals(ScaleOverlay.GREY, unlocked.at(240 + 10, 240))
        assertEquals(ScaleOverlay.GREY, unlocked.at(240, 240 - 10))
    }

    @Test fun linesAreAsThickAsRingsOfTheSameWidth() {
        val shapes = listOf(
            OverlayShape.Line(100.0, 240.0, 380.0, 240.0, ScaleOverlay.LOCK, 2, false),  // horizontal
            OverlayShape.Line(120.0, 100.0, 120.0, 380.0, ScaleOverlay.LOCK, 2, false),  // vertical
            OverlayShape.Arc(240.0, 240.0, 100.0, 0.0, 360.0, 0.0, ScaleOverlay.LOCK, 2),
        )
        for ((f, px) in listOf(1.0 to 2, 2.0 to 4)) {
            val img = renderer.render(shapes, (480 * f).toInt(), (480 * f).toInt(), f)
            fun column(x: Int, ys: IntRange) = ys.count { img.at(x, it) == ScaleOverlay.LOCK }
            fun row(y: Int, xs: IntRange) = xs.count { img.at(it, y) == ScaleOverlay.LOCK }
            val s = f.toInt()
            assertEquals("horizontal line at f=$f", px, column(300 * s, 200 * s until 280 * s))
            assertEquals("vertical line at f=$f", px, row(300 * s, 100 * s until 130 * s))
            assertEquals("ring at f=$f", px, column(240 * s, 130 * s until 150 * s))  // its top, 100 above the centre
        }
    }

    @Test fun barTicksAreEvenOnAFractionalScale() {
        // a 1000-px circle: f = 2.0833, so some ticks land on whole pixels and some don't
        val f = 1000.0 / 480
        val img = renderer.render(ScaleOverlay.shapes(ScaleStyle.BAR, locked = true, close = false), 1000, 1000, f)
        // a row through the ticks, 5 raw px below the bar: only the ticks cross it
        val y = ((240 + 5) * f).toInt()
        val runs = ArrayList<Int>()
        var run = 0
        for (x in 0 until 1000) {
            if (img.at(x, y) == ScaleOverlay.LOCK) run++ else if (run > 0) { runs += run; run = 0 }
        }
        assertEquals(11, runs.size)
        assertEquals(listOf(4), runs.distinct())  // centre tick ("5") as wide as the rest
    }

    @Test fun ringsFollowTheDisplayScale() {
        // drawn at the screen size: 2.5 px per raw pixel puts the 1 mm ring at 100 px
        val img = ring(locked = true, f = 2.5)
        assertEquals(1200, img.width)
        assertEquals(ScaleOverlay.LOCK, img.at(600 + 100, 600))
        assertEquals(0, img.at(600 + 150, 600))
    }

    @Test fun lockedRingsAreSolidUnlockedAreDashed() {
        val r = 80.0  // the 2 mm ring; sample the lower half, away from the labels above
        val locked = ring(locked = true)
        val unlocked = ring(locked = false)
        val angles = (5 until 175).map { it.toDouble() }
        assertTrue(angles.all { locked.onCircle(r, it) == ScaleOverlay.LOCK })
        val on = angles.map { unlocked.onCircle(r, it) == ScaleOverlay.GREY }
        // dashes: on and off in runs of about DASH_DEG
        assertTrue(on.count { it } in 60..110)
        assertTrue(on.count { !it } in 60..110)
        val runs = on.zipWithNext().count { (a, b) -> a != b }
        assertTrue("runs $runs", runs >= 20)
    }

    @Test fun theDiameterLabelIsHandDrawn() {
        // the ⌀ glyph has both a ring and a slash
        val pixels = (0 until 16).flatMap { y -> (0 until 8).filter { x -> OverlayRenderer.diameter(x, y) }.map { x -> x to y } }
        assertTrue(pixels.size in 12..40)
        assertTrue(OverlayRenderer.diameter(7, 4))  // top of the slash
        // and a label with it renders in the scale's colour
        val img = ring(locked = true)
        // "⌀2" sits on the horizontal axis, just right of the 1 mm ring: baseline 237, from x 283
        val labelRow = (237 - 12 until 237 + 4).flatMap { y -> (283 until 283 + 24).map { x -> img.at(x, y) } }
        assertTrue(labelRow.any { it == ScaleOverlay.LOCK })
        assertTrue(labelRow.any { it == OverlayRenderer.BLACK })  // outlined
    }

    @Test fun closeIsATriangleAndLabelAtTheUpperLeft() {
        val shapes = ScaleOverlay.shapes(ScaleStyle.NONE, locked = false, close = true)
        val img = renderer.render(shapes, 480, 480, 1.0)
        // the triangle: warning yellow inside, black edge at the apex, black "!" in the middle
        assertEquals(ScaleOverlay.WARNING, img.at(18 + 12, 16 + 34))
        assertEquals(OverlayRenderer.BLACK, img.at(18 + 20, 16 + 20))  // the "!" bar
        assertEquals(0, img.at(10, 10))
        // "CLOSE" to its right
        // anchored left-middle at (66, 34): rows 26..42
        val label = (26 until 42).flatMap { y -> (66 until 110).map { x -> img.at(x, y) } }
        assertTrue(label.count { it == ScaleOverlay.WARNING } > 30)
        // nothing near the centre: CLOSE alone draws no scale
        assertTrue((200 until 280).all { img.at(it, 240) == 0 })
    }

    @Test fun aWindowMapsRawCoordinates() {
        // the CLOSE layer is rendered on its own, from raw (10, 8), at a whole scale
        val shapes = ScaleOverlay.shapes(ScaleStyle.NONE, locked = false, close = true)
        val img = renderer.render(shapes, 112 * 2, 58 * 2, 2.0, 10.0, 8.0)
        assertEquals(ScaleOverlay.WARNING, img.at((18 + 12 - 10) * 2, (16 + 34 - 8) * 2))
    }
}
