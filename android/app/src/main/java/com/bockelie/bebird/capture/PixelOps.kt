// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.PixelImage

/** Pure pixel steps for captures, so they can be tested without Android. */
object PixelOps {
    /**
     * [rect] of [src] enlarged by a whole factor (nearest neighbour, so no new pixel values) to
     * at least 480 px wide, and where [src]'s inscribed circle falls in the result.
     */
    fun enlargedCrop(src: PixelImage, rect: ZoomCrop.Rect): Pair<PixelImage, BandRenderer.Circle> {
        val k = ZoomCrop.upscale(rect.width)
        val w = rect.width * k
        val h = rect.height * k
        val px = IntArray(w * h) { i -> src.pixels[(rect.top + (i / w) / k) * src.width + rect.left + (i % w) / k] }
        // source pixel x's centre lands at k*x + (k-1)/2; the ring's outer edge scales with k
        val c = BandRenderer.Circle.inscribed(src.width, src.height)
        val circle = BandRenderer.Circle((c.cx - rect.left) * k + (k - 1) / 2.0, (c.cy - rect.top) * k + (k - 1) / 2.0, (c.r + 0.5) * k - 0.5)
        return PixelImage(w, h, px) to circle
    }

    /** [image] placed top-left in a [width] × [height] picture, cut or padded with the band's black. */
    fun fit(image: PixelImage, width: Int, height: Int): PixelImage {
        if (image.width == width && image.height == height) return image
        val px = IntArray(width * height) { BandRenderer.BACKGROUND }
        for (y in 0 until minOf(height, image.height)) {
            image.pixels.copyInto(px, y * width, y * image.width, y * image.width + minOf(width, image.width))
        }
        return PixelImage(width, height, px)
    }
}
