// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import android.graphics.Bitmap

/**
 * The adapter from a decoded frame to the estimator's input: the raw, unrotated
 * [FrameGeometry.SIZE]-square bitmap to luma, reusing [argb] and [out] between frames.
 */
fun Bitmap.lumaInto(argb: IntArray, out: ByteArray) {
    val n = FrameGeometry.SIZE
    require(width == n && height == n) { "frame is ${width}x$height, not ${n}x$n" }
    getPixels(argb, 0, n, 0, 0, n, n)
    lumaInto(argb, out)
}
