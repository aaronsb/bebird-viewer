// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import com.bockelie.bebird.band.BandData
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.PixelImage
import com.bockelie.bebird.band.PixelText
import com.bockelie.bebird.focus.FrameGeometry
import com.bockelie.bebird.focus.OverlayRenderer
import com.bockelie.bebird.focus.ScaleDisclaimer
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
 * A short note goes with it at the frame's upper right, since the file travels without the
 * app. Stills without a scale are exactly what they were. Pure.
 */
class ScaleStamp(private val overlay: OverlayRenderer, private val text: PixelText) {
    /** The scale alone on a transparent [w] × [h] (frame-size) image, inside the image circle only. */
    fun scaleOnly(scale: SavedScale, w: Int, h: Int): PixelImage {
        val shapes = ScaleOverlay.shapes(scale.style, scale.locked, close = false)
        val image = overlay.render(shapes, w, h, w.toDouble() / FrameGeometry.SIZE)
        // the band's own test for "inside the circle" (its ring is at |d| < 0.5), so the ring stays clear
        val c = BandRenderer.Circle.inscribed(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            if (Math.hypot(x - c.cx, y - c.cy) - c.r > -0.5) image.pixels[y * w + x] = 0
        }
        return image
    }

    /** [layer] with [NOTE] added at its upper right, at a whole scale for its width (1 at 480 px). */
    fun withNote(layer: PixelImage): PixelImage {
        val s = maxOf(1, layer.width / FrameGeometry.SIZE)
        val lines = text.rightAligned(NOTE, layer.width / 2 / s - 2, NOTE.size) ?: return layer
        val nw = (lines.maxOfOrNull { it.x + text.width(it.text) } ?: 0) + 2
        val nh = text.height(lines.size) + 2
        val note = text.draw(lines.map { it.copy(x = it.x + 1, y = it.y + 1) }, nw, nh, BandRenderer.TAG, outline = true)
        val left = layer.width - (ScaleDisclaimer.INSET + nw) * s
        val top = ScaleDisclaimer.INSET * s
        val px = layer.pixels.copyOf()
        for (y in 0 until nh * s) for (x in 0 until nw * s) {
            val p = note.pixels[(y / s) * nw + x / s]
            val tx = left + x
            val ty = top + y
            if (p != 0 && tx in 0 until layer.width && ty in 0 until layer.height) px[ty * layer.width + tx] = p
        }
        return PixelImage(layer.width, layer.height, px)
    }

    /** What goes over a full frame (and what the annotate view shows): the scale and the note. */
    fun layer(scale: SavedScale, w: Int, h: Int): PixelImage = withNote(scaleOnly(scale, w, h))

    /** A snapshot's still: [Frames.composed] as always, then with [scale] (if any) over the frame. */
    fun still(upright: PixelImage, band: BandRenderer?, data: BandData, overlay: Boolean, scale: SavedScale?): PixelImage {
        val plain = Frames.composed(upright, band, data, overlay)
        return if (scale == null) plain else over(plain, layer(scale, upright.width, upright.height))
    }

    /** The zoomed crop's still: the scale cropped and enlarged with the picture, and the note at the crop's corner. */
    fun zoomed(upright: PixelImage, rect: ZoomCrop.Rect, band: BandRenderer?, data: BandData, overlay: Boolean, scale: SavedScale?): PixelImage {
        val (crop, circle) = PixelOps.enlargedCrop(upright, rect)
        val plain = Frames.composed(crop, band, data, overlay, circle)
        if (scale == null) return plain
        val drawn = PixelOps.enlargedCrop(scaleOnly(scale, upright.width, upright.height), rect).first
        return over(plain, withNote(drawn))
    }

    companion object {
        /** The note: the scale is an estimate, and only when the picture is in focus. */
        val NOTE = listOf("APPROX. SCALE", "needs focus")

        /** [image] with [layer]'s drawn (non-transparent) pixels over its top-left corner. */
        fun over(image: PixelImage, layer: PixelImage): PixelImage {
            require(layer.width <= image.width && layer.height <= image.height) { "layer larger than the image" }
            val px = image.pixels.copyOf()
            for (y in 0 until layer.height) for (x in 0 until layer.width) {
                val p = layer.pixels[y * layer.width + x]
                if (p != 0) px[y * image.width + x] = p
            }
            return PixelImage(image.width, image.height, px)
        }
    }
}
