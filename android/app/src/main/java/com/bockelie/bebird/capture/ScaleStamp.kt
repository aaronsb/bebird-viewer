// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import com.bockelie.bebird.band.BandData
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.PixelImage
import com.bockelie.bebird.band.PixelText
import com.bockelie.bebird.focus.FrameGeometry
import com.bockelie.bebird.focus.OverlayRenderer
import com.bockelie.bebird.focus.ScaleOverlay
import com.bockelie.bebird.focus.ScaleStyle

/** The proximity scale as shown at capture (#43): its [style] and whether it was [locked]. Never [ScaleStyle.NONE]. */
data class SavedScale(val style: ScaleStyle, val locked: Boolean) {
    init { require(style != ScaleStyle.NONE) }

    /** For the metadata of an image [width] px wide at [zoom] (a zoomed crop's whole enlargement). */
    fun meta(width: Int, zoom: Int = 1) =
        ScaleMeta(style.name.lowercase(), locked, ScaleOverlay.PX_PER_MM * width / FrameGeometry.SIZE * zoom)
}

/**
 * The proximity scale burned into saved stills (#43), as the screen shows it at zoom 1: upright,
 * centred on the (upright) frame's centre, at the same px/mm, and only inside the image circle,
 * where the screen clips it. CLOSE is never drawn: it is a live warning, not part of the record.
 * A short note goes with it in the frame's upper right corner, since the file travels without
 * the app. Stills without a scale are exactly what they were. Pure.
 */
class ScaleStamp(private val overlay: OverlayRenderer, private val text: PixelText) {
    /**
     * The scale alone on a transparent [w] × [h] frame-size image, centred on the frame's centre
     * at the screen's px/mm for a frame that size, inside the image circle only.
     */
    fun scaleOnly(scale: SavedScale, w: Int, h: Int): PixelImage {
        val f = minOf(w, h).toDouble() / FrameGeometry.SIZE
        return drawn(scale, w, h, f, w / 2.0, h / 2.0, BandRenderer.Circle.inscribed(w, h), strokes(w))
    }

    /** Strokes and text at a whole scale for an image [width] px wide (1 at 480), as on the full frame. */
    private fun strokes(width: Int) = maxOf(1, width / FrameGeometry.SIZE)

    /**
     * The scale over [f] image pixels per raw-frame pixel, its centre at ([cx], [cy]), strokes and
     * text [k] times their size, kept inside [circle] by the band's own test for "inside" (its
     * ring is at |d| < 0.5, so the ring stays clear).
     */
    private fun drawn(scale: SavedScale, w: Int, h: Int, f: Double, cx: Double, cy: Double, circle: BandRenderer.Circle, k: Int): PixelImage {
        val shapes = ScaleOverlay.shapes(scale.style, scale.locked, close = false)  // raw centre (240, 240)
        val c = FrameGeometry.CENTER.toDouble()
        val image = overlay.render(shapes, w, h, f, originX = c - cx / f, originY = c - cy / f, k = k)
        for (y in 0 until h) for (x in 0 until w) {
            if (Math.hypot(x - circle.cx, y - circle.cy) - circle.r > -0.5) image.pixels[y * w + x] = 0
        }
        return image
    }

    /**
     * [base] with [NOTE] added in its upper right corner, at a whole scale for its width (1 at
     * 480 px): on a full frame that corner is outside the image circle, so the note sits on
     * the black and the hair-thin ring never crosses it.
     */
    fun withNote(base: PixelImage): PixelImage {
        val s = maxOf(1, base.width / FrameGeometry.SIZE)
        val lines = text.rightAligned(NOTE, base.width / 2 / s - 2, NOTE.size) ?: return base
        val nw = (lines.maxOfOrNull { it.x + text.width(it.text) } ?: 0) + 2
        val nh = text.height(lines.size) + 2
        val note = text.draw(lines.map { it.copy(x = it.x + 1, y = it.y + 1) }, nw, nh, BandRenderer.TAG, outline = true)
        val left = base.width - (NOTE_INSET + nw) * s
        val top = NOTE_INSET * s
        val px = base.pixels.copyOf()
        for (y in 0 until nh * s) for (x in 0 until nw * s) {
            val p = note.pixels[(y / s) * nw + x / s]
            val tx = left + x
            val ty = top + y
            if (p != 0 && tx in 0 until base.width && ty in 0 until base.height) px[ty * base.width + tx] = p
        }
        return PixelImage(base.width, base.height, px)
    }

    /** What goes over a full frame (and what the annotate view shows): the scale and the note. */
    fun layer(scale: SavedScale, w: Int, h: Int): PixelImage = withNote(scaleOnly(scale, w, h))

    /** A snapshot's still: [Frames.composed] as always, then with [scale] (if any) over the frame. */
    fun still(upright: PixelImage, band: BandRenderer?, data: BandData, overlay: Boolean, scale: SavedScale?): PixelImage {
        val plain = Frames.composed(upright, band, data, overlay)
        return if (scale == null) plain else over(plain, layer(scale, upright.width, upright.height))
    }

    /**
     * The zoomed crop's still: the scale drawn afresh at the crop's own resolution where the
     * frame's scale falls in it, its strokes and text as thin as on the full frame rather than
     * enlarged with the picture, and the note at the crop's corner.
     */
    fun zoomed(upright: PixelImage, rect: ZoomCrop.Rect, band: BandRenderer?, data: BandData, overlay: Boolean, scale: SavedScale?): PixelImage {
        val (crop, circle) = PixelOps.enlargedCrop(upright, rect)
        val plain = Frames.composed(crop, band, data, overlay, circle)
        if (scale == null) return plain
        // frame pixel x's centre lands at k·(x − left) + (k − 1)/2 in the crop, as in PixelOps
        val k = ZoomCrop.upscale(rect.width)
        val f = minOf(upright.width, upright.height).toDouble() / FrameGeometry.SIZE * k
        val cx = (upright.width / 2.0 - rect.left) * k + (k - 1) / 2.0
        val cy = (upright.height / 2.0 - rect.top) * k + (k - 1) / 2.0
        return over(plain, withNote(drawn(scale, crop.width, crop.height, f, cx, cy, circle, strokes(crop.width))))
    }

    companion object {
        /**
         * The note: the scale is an estimate, and only when the picture is in focus. Fixed
         * English on purpose, not in strings.xml: the file is a record read without the app,
         * like the band's tags and the EXIF keys, so it doesn't change with the phone's
         * language, and plain ASCII is always in the band's font (Terminus).
         */
        val NOTE = listOf("APPROX. SCALE", "needs focus")

        /** The note's gap from the top and right edges, in note pixels: small, to stay in the corner outside the circle. */
        const val NOTE_INSET = 1

        /**
         * [scale] and what [draw] makes of it, or no scale at all if drawing fails ([onFailure] is
         * told): the scale is extra, so a failure to draw it never stops what it goes with, and
         * a scale that wasn't drawn isn't recorded either.
         */
        fun <T : Any> drawnOrNone(scale: SavedScale?, onFailure: (Throwable) -> Unit, draw: (SavedScale) -> T?): Pair<SavedScale, T>? {
            if (scale == null) return null
            return try {
                draw(scale)?.let { scale to it }
            } catch (e: Throwable) {
                onFailure(e)
                null
            }
        }

        /** [image] with [drawn]'s non-transparent pixels over its top-left corner. */
        fun over(image: PixelImage, drawn: PixelImage): PixelImage {
            require(drawn.width <= image.width && drawn.height <= image.height) { "drawing larger than the image" }
            val px = image.pixels.copyOf()
            for (y in 0 until drawn.height) for (x in 0 until drawn.width) {
                val p = drawn.pixels[y * drawn.width + x]
                if (p != 0) px[y * image.width + x] = p
            }
            return PixelImage(image.width, image.height, px)
        }
    }
}
