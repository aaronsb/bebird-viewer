// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import kotlin.math.ceil
import kotlin.math.floor

/**
 * What part of the frame the zoomed viewport shows. On screen the frame (frameSize square) is
 * drawn as a circle [side] px across, centred in a viewport w × h, then scaled by [zoom] about
 * the viewport's centre and moved by ([offsetX], [offsetY]) px.
 */
object ZoomCrop {
    /** A rectangle in frame pixels, [left, right) × [top, bottom). */
    data class Rect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
    }

    /** The visible part of the frame, or null when not zoomed in (the whole frame shows). */
    fun visible(
        frameSize: Int, viewportW: Float, viewportH: Float, side: Float, zoom: Float, offsetX: Float, offsetY: Float,
    ): Rect? {
        if (zoom <= 1.001f || side <= 0f) return null
        val shown = side * zoom                       // on-screen size of the frame
        val left = viewportW / 2 + offsetX - shown / 2  // where the frame's left edge is on screen
        val top = viewportH / 2 + offsetY - shown / 2
        val perPx = frameSize / shown                 // frame pixels per screen pixel
        val r = Rect(
            floor((0 - left) * perPx).toInt().coerceIn(0, frameSize),
            floor((0 - top) * perPx).toInt().coerceIn(0, frameSize),
            ceil((viewportW - left) * perPx).toInt().coerceIn(0, frameSize),
            ceil((viewportH - top) * perPx).toInt().coerceIn(0, frameSize),
        )
        return r.takeIf { it.width > 0 && it.height > 0 && (it.width < frameSize || it.height < frameSize) }
    }

    /** The integer factor that brings a crop [width] px wide up to at least [target] px (nearest neighbour). */
    fun upscale(width: Int, target: Int = 480): Int = maxOf(1, (target + width - 1) / width)
}
