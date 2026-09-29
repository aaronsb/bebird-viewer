// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import android.graphics.Bitmap

/**
 * The adapter from a decoded frame to the estimator's input: the raw, unrotated
 * [FrameGeometry.SIZE]-square bitmap to luma, reusing [argb] and [out] between frames. The
 * bitmap must be a software ARGB_8888 one (BitmapFactory's default): a HARDWARE bitmap can't
 * be read back with getPixels.
 */
fun Bitmap.lumaInto(argb: IntArray, out: ByteArray) {
    val n = FrameGeometry.SIZE
    require(width == n && height == n) { "frame is ${width}x$height, not ${n}x$n" }
    require(config == Bitmap.Config.ARGB_8888) { "frame bitmap is $config, not a software ARGB_8888" }
    getPixels(argb, 0, n, 0, 0, n, n)
    lumaInto(argb, out)
}

/** [lumaInto] with a [ProximityGate]'s buffers. */
fun Bitmap.lumaInto(buffers: LumaBuffers) = lumaInto(buffers.argb, buffers.luma)
