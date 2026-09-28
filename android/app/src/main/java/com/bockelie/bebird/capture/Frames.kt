// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.bockelie.bebird.band.BandData
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.PixelImage
import com.bockelie.bebird.band.toBitmap
import com.bockelie.bebird.band.toPixelImage

/** Bitmap steps shared by stills and video: rotate as shown, crop a zoomed view, add the overlay. */
object Frames {
    /** [frame] turned clockwise by [degrees] about its centre, same size, as the screen draws it. */
    fun rotated(frame: Bitmap, degrees: Int): Bitmap {
        if (degrees % 360 == 0) return frame
        val out = Bitmap.createBitmap(frame.width, frame.height, Bitmap.Config.ARGB_8888)
        Canvas(out).apply {
            drawColor(Color.BLACK)
            rotate(degrees.toFloat(), frame.width / 2f, frame.height / 2f)
            drawBitmap(frame, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return out
    }

    /** [image] with the overlay (band, ring, background) when [overlay] and the fonts are loaded. */
    fun composed(image: Bitmap, renderer: BandRenderer?, data: BandData, overlay: Boolean,
                 circle: BandRenderer.Circle = BandRenderer.Circle.inscribed(image.width, image.height)): Bitmap =
        if (overlay && renderer != null) renderer.compose(image.toPixelImage(), data, true, circle).toBitmap() else image

    /**
     * The zoomed view: [rect] of the (rotated) frame, enlarged by a whole factor (nearest
     * neighbour, so no new pixel values) to at least 480 px wide, with the overlay drawn around
     * where the scope's image circle falls in it.
     */
    fun zoomed(rotated: Bitmap, rect: ZoomCrop.Rect, renderer: BandRenderer?, data: BandData, overlay: Boolean): Bitmap {
        val k = ZoomCrop.upscale(rect.width)
        val src = rotated.toPixelImage()
        val w = rect.width * k
        val h = rect.height * k
        val px = IntArray(w * h) { i -> src.pixels[(rect.top + (i / w) / k) * src.width + rect.left + (i % w) / k] }
        // the frame's inscribed circle, in the enlarged crop's pixel coordinates
        val c = BandRenderer.Circle.inscribed(src.width, src.height)
        val circle = BandRenderer.Circle((c.cx - rect.left) * k + (k - 1) / 2.0, (c.cy - rect.top) * k + (k - 1) / 2.0, (c.r + 0.5) * k - 0.5)
        return composed(PixelImage(w, h, px).toBitmap(), renderer, data, overlay, circle)
    }
}
