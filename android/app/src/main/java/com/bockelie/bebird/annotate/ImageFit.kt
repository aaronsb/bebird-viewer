// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.annotate

/**
 * Where an [imageW] × [imageH] image sits when fitted whole into a [viewW] × [viewH] view:
 * scaled to touch two edges, centred, letterboxed on the others. Maps view pixels to image
 * coordinates ([Pt]) and back.
 */
class ImageFit(viewW: Float, viewH: Float, imageW: Int, imageH: Int) {
    private val scale = minOf(viewW / imageW, viewH / imageH)
    val width = imageW * scale
    val height = imageH * scale
    val left = (viewW - width) / 2
    val top = (viewH - height) / 2

    /** The image point under view pixel ([x], [y]); outside the image it is held to the nearest edge. */
    fun toImage(x: Float, y: Float) = Pt(((x - left) / width).coerceIn(0f, 1f), ((y - top) / height).coerceIn(0f, 1f))

    fun toViewX(p: Pt) = left + p.x * width
    fun toViewY(p: Pt) = top + p.y * height
}
