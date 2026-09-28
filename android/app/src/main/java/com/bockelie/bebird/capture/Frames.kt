// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.bockelie.bebird.band.BandData
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.PixelImage
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
    fun composed(image: PixelImage, renderer: BandRenderer?, data: BandData, overlay: Boolean,
                 circle: BandRenderer.Circle = BandRenderer.Circle.inscribed(image.width, image.height)): PixelImage =
        if (overlay && renderer != null) renderer.compose(image, data, true, circle) else image

    /** The zoomed view: [rect] of the rotated frame, enlarged, with the overlay around the scope's circle in it. */
    fun zoomed(rotated: Bitmap, rect: ZoomCrop.Rect, renderer: BandRenderer?, data: BandData, overlay: Boolean): PixelImage {
        val (crop, circle) = PixelOps.enlargedCrop(rotated.toPixelImage(), rect)
        return composed(crop, renderer, data, overlay, circle)
    }
}
