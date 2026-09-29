// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import com.bockelie.bebird.annotate.AnnotationRenderer
import com.bockelie.bebird.annotate.Mark
import com.bockelie.bebird.annotate.Palette
import com.bockelie.bebird.annotate.Pt
import com.bockelie.bebird.annotate.annotatedStills
import com.bockelie.bebird.band.BandData
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.Fonts
import com.bockelie.bebird.band.PixelImage
import com.bockelie.bebird.band.PixelText
import com.bockelie.bebird.focus.OverlayRenderer
import com.bockelie.bebird.focus.ScaleOverlay
import com.bockelie.bebird.focus.ScaleStyle
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import kotlin.math.hypot

class ScaleStampTest {
    private val stamp = ScaleStamp(OverlayRenderer(Fonts.source), PixelText(Fonts.source))
    private val band = BandRenderer(Fonts.source)
    private val data = BandData(batteryPercent = 80, lightPercent = 50, roll = 12, device = "ES", time = LocalDateTime.of(2026, 9, 29, 10, 0, 0))
    private val grey = 0xFF505050.toInt()
    private val upright = PixelImage(480, 480, IntArray(480 * 480) { grey })
    private val locked = SavedScale(ScaleStyle.RING, locked = true)

    private fun PixelImage.at(x: Int, y: Int) = pixels[y * width + x]

    /** Whether [color] is drawn within [slack] px of ([x], [y]). */
    private fun PixelImage.near(x: Int, y: Int, color: Int, slack: Int = 2) =
        (y - slack..y + slack).any { yy -> (x - slack..x + slack).any { xx -> at(xx, yy) == color } }

    // --- no scale: nothing changes ---

    @Test fun withoutAScaleTheStillIsExactlyWhatItWas() {
        for (overlay in listOf(true, false)) {
            val before = Frames.composed(upright, band, data, overlay)
            assertArrayEquals(before.pixels, stamp.still(upright, band, data, overlay, null).pixels)
        }
        val rect = ZoomCrop.Rect(120, 120, 360, 360)
        val (crop, circle) = PixelOps.enlargedCrop(upright, rect)
        assertArrayEquals(Frames.composed(crop, band, data, true, circle).pixels, stamp.zoomed(upright, rect, band, data, true, null).pixels)
    }

    @Test fun withoutAScaleTheMetadataIsUnchanged() {
        val meta = SnapshotMeta(
            taken = ZonedDateTime.of(2026, 9, 29, 10, 0, 0, 0, ZoneOffset.UTC), roll = 1, rotationApplied = 0, autoRotate = true,
            trim = 0, lightPercent = 50, lightRaw = 25, batteryPercent = 80, batteryState = "battery", fps = 10, zoom = 1.0,
            zoomed = false, label = null, device = "ES", model = "ES",
        )
        assertFalse("proximity_scale" in meta.json())
        assertArrayEquals(ExifWriter.segment(meta), ExifWriter.segment(meta.copy(scale = null)))
        val withScale = meta.copy(scale = locked.meta(480)).json()
        assertEquals(meta.json().removeSuffix("}") +
            ", \"proximity_scale\": {\"style\": \"ring\", \"locked\": true, \"px_per_mm\": 40, \"tolerance_pct\": 10}}", withScale)
    }

    @Test fun noScaleStyleIsNoScale() {
        assertTrue(runCatching { SavedScale(ScaleStyle.NONE, true) }.isFailure)
    }

    // --- the scale as shown ---

    @Test fun theScaleIsCentredOnTheFrameAt40PxPerMm() {
        val still = stamp.still(upright, band, data, true, locked)
        for (k in 1..ScaleOverlay.RINGS) {
            val r = (k * ScaleOverlay.PX_PER_MM).toInt()
            // each ring crosses the vertical axis above and below the centre, and the horizontal axis to the left
            assertTrue("ring $k up", still.near(240, 240 - r, ScaleOverlay.LOCK))
            assertTrue("ring $k down", still.near(240, 240 + r, ScaleOverlay.LOCK))
            assertTrue("ring $k left", still.near(240 - r, 240, ScaleOverlay.LOCK))
        }
        // the centre itself stays clear (the crosshair starts 0.1 mm out)
        assertEquals(grey, still.at(240, 240))
    }

    @Test fun unlockedIsGreyAndCloseIsNeverSaved() {
        for (style in listOf(ScaleStyle.RING, ScaleStyle.BOWTIE, ScaleStyle.BAR)) {
            val still = stamp.still(upright, band, data, true, SavedScale(style, locked = false))
            assertTrue(style.name, ScaleOverlay.GREY in still.pixels)
            assertFalse(style.name, ScaleOverlay.LOCK in still.pixels)
            assertFalse(style.name, ScaleOverlay.WARNING in still.pixels)
        }
    }

    @Test fun theScaleStaysInsideTheImageCircleAndOffTheBand() {
        for (style in listOf(ScaleStyle.RING, ScaleStyle.BOWTIE, ScaleStyle.BAR)) {
            val layer = stamp.scaleOnly(SavedScale(style, true), 480, 480)
            for (y in 0 until 480) for (x in 0 until 480) {
                if (hypot(x - 239.5, y - 239.5) > 239.0) assertEquals("$style ($x, $y)", 0, layer.at(x, y))
            }
            val plain = Frames.composed(upright, band, data, true)
            val still = stamp.still(upright, band, data, true, SavedScale(style, true))
            val from = 480 * 480
            assertArrayEquals(plain.pixels.copyOfRange(from, plain.pixels.size), still.pixels.copyOfRange(from, still.pixels.size))
            // the hair-thin circle is left as it is
            assertEquals(BandRenderer.CIRCLE, still.at(0, 240))
        }
    }

    @Test fun theNoteIsInTheCornerOutsideTheCircle() {
        val note = stamp.withNote(PixelImage(480, 480, IntArray(480 * 480)))
        val c = BandRenderer.Circle.inscribed(480, 480)
        var drawnPixels = 0
        for (y in 0 until 480) for (x in 0 until 480) {
            if (note.at(x, y) == 0) continue
            drawnPixels++
            assertTrue("($x, $y) inside the circle", hypot(x - c.cx, y - c.cy) - c.r >= 0.5)
        }
        assertTrue(drawnPixels > 100)
        // so with the overlay on, the hair-thin ring is exactly as without the scale
        val plain = Frames.composed(upright, band, data, true)
        val still = stamp.still(upright, band, data, true, locked)
        for (i in 0 until 480 * 480) if (plain.pixels[i] == BandRenderer.CIRCLE) assertEquals(BandRenderer.CIRCLE, still.pixels[i])
    }

    @Test fun aNonSquareFrameHasTheScaleAtItsCentre() {
        val layer = stamp.scaleOnly(locked, 600, 480)
        assertTrue(layer.near(300, 240 - 40, ScaleOverlay.LOCK))
        assertTrue(layer.near(300, 240 + 80, ScaleOverlay.LOCK))
        assertTrue(layer.near(300 - 120, 240, ScaleOverlay.LOCK))
    }

    @Test fun drawingTheScaleCanFailWithoutStoppingAnything() {
        var told: Throwable? = null
        assertEquals(null, ScaleStamp.drawnOrNone<String>(null, { told = it }) { "x" })
        assertEquals(locked to "x", ScaleStamp.drawnOrNone(locked, { told = it }) { "x" })
        assertEquals(null, ScaleStamp.drawnOrNone<String>(locked, { told = it }) { null })
        assertEquals(null, told)
        assertEquals(null, ScaleStamp.drawnOrNone<String>(locked, { told = it }) { throw OutOfMemoryError("no room") })
        assertTrue(told is OutOfMemoryError)
    }

    @Test fun aScaleThatFailsToDrawLeavesThePlainSnapshot() {
        val meta = SnapshotMeta(
            taken = ZonedDateTime.of(2026, 9, 29, 10, 0, 0, 0, ZoneOffset.UTC), roll = 1, rotationApplied = 0, autoRotate = true,
            trim = 0, lightPercent = 50, lightRaw = 25, batteryPercent = 80, batteryState = "battery", fps = 10, zoom = 1.0,
            zoomed = false, label = null, device = "ES", model = "ES",
        )
        val plain = { Frames.composed(upright, band, data, true) }
        var told: Throwable? = null
        val (image, saved) = ScaleStamp.withScaleOrPlain(locked, meta, { it.meta(480) }, { told = it }, plain) {
            throw IllegalStateException("renderer broke")
        }
        assertTrue(told is IllegalStateException)
        assertArrayEquals(plain().pixels, image.pixels)
        assertEquals(meta, saved)
        assertFalse("proximity_scale" in saved.json())
        // and when it draws: the scaled image and the scale in the metadata
        val (drawn, withScale) = ScaleStamp.withScaleOrPlain(locked, meta, { it.meta(480) }, { told = it }, plain) {
            stamp.still(upright, band, data, true, it)
        }
        assertTrue(ScaleOverlay.LOCK in drawn.pixels)
        assertEquals(locked.meta(480), withScale.scale)
        // no scale: the plain still and the metadata untouched
        val (none, noneMeta) = ScaleStamp.withScaleOrPlain(null, meta, { it.meta(480) }, { told = it }, plain) { error("not called") }
        assertArrayEquals(plain().pixels, none.pixels)
        assertEquals(meta, noneMeta)
    }

    @Test fun theNoteSitsAtTheUpperRight() {
        val still = stamp.still(upright, band, data, false, locked)
        fun tagIn(x0: Int, x1: Int, y0: Int, y1: Int) = (y0 until y1).any { y -> (x0 until x1).any { x -> still.at(x, y) == BandRenderer.TAG } }
        assertTrue(tagIn(300, 480, 0, 60))
        assertFalse(tagIn(0, 240, 0, 60))
        // and not on the raw frame without a scale
        assertFalse(BandRenderer.TAG in stamp.still(upright, band, data, false, null).pixels)
    }

    @Test fun aZoomedCropsRingsStayThin() {
        // a 3x crop of the middle 160 px: 1 mm is 120 px, but a locked ring is still 2 px thick
        val rect = ZoomCrop.Rect(160, 160, 320, 320)
        assertEquals(3, ZoomCrop.upscale(rect.width))
        val zoomed = stamp.zoomed(upright, rect, band, data, false, locked)
        val centre = (240 - 160) * 3 + 1  // frame pixel 240's centre in the crop
        val thick = (centre - 120 - 8..centre - 120 + 8).count { y -> zoomed.at(centre, y) == ScaleOverlay.LOCK }
        assertTrue("ring $thick px thick", thick in 1..3)
    }

    @Test fun aZoomedCropHasTheScaleCroppedAndEnlarged() {
        // the middle 240 px, enlarged twice: 1 mm is 80 px in the crop
        val rect = ZoomCrop.Rect(120, 120, 360, 360)
        val zoomed = stamp.zoomed(upright, rect, band, data, true, locked)
        assertEquals(480, zoomed.width)
        assertTrue(zoomed.near(240, 240 - 80, ScaleOverlay.LOCK, slack = 3))
        assertTrue(zoomed.near(240 - 160, 240, ScaleOverlay.LOCK, slack = 3))
        assertEquals(grey, zoomed.at(240, 240))
        // its own note, at the crop's upper right
        assertTrue((0 until 60).any { y -> (300 until 480).any { x -> zoomed.at(x, y) == BandRenderer.TAG } })
        assertEquals(ScaleMeta("ring", true, 80.0), locked.meta(480, ZoomCrop.upscale(rect.width)))
    }

    // --- annotate ---

    @Test fun theRawFrameHasNoScaleAndTheAnnotatedCopyHasBoth() {
        val marks = listOf(Mark.Box(Pt(0.1f, 0.1f), Pt(0.3f, 0.3f), Palette.RED))
        val (plain, annotated) = annotatedStills(
            upright, band, data, true, marks, AnnotationRenderer(Fonts.source), stamp.layer(locked, 480, 480),
        )
        assertArrayEquals(Frames.composed(upright, band, data, true).pixels, plain.pixels)
        assertFalse(ScaleOverlay.LOCK in plain.pixels)
        assertTrue(ScaleOverlay.LOCK in annotated.pixels)
        assertTrue(Palette.RED in annotated.pixels)
        // with no scale at the pause, the copy is the marks alone, as before
        val (_, marksOnly) = annotatedStills(upright, band, data, true, marks, AnnotationRenderer(Fonts.source))
        assertFalse(ScaleOverlay.LOCK in marksOnly.pixels)
    }

    @Test fun overKeepsTheImageWhereTheLayerIsClear() {
        val image = PixelImage(4, 3, IntArray(12) { it + 1 })
        val layer = PixelImage(2, 2, intArrayOf(0, 7, 0, 0))
        val out = ScaleStamp.over(image, layer)
        assertEquals(7, out.at(1, 0))
        assertEquals(listOf(1, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12), out.pixels.filterIndexed { i, _ -> i != 1 })
        assertEquals(2, image.pixels[1])  // the image itself is untouched
    }
}
