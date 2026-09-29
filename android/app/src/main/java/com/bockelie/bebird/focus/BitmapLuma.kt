// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import android.graphics.Bitmap

/**
 * The adapter from a decoded frame to the estimator's input: the raw, unrotated
 * [FrameGeometry.SIZE]-square bitmap to luma, reusing [argb] and [out] between frames. The
 * bitmap must be a software ARGB_8888 one (BitmapFactory's default): a HARDWARE bitmap can't
 * be read back with getPixels. One line over the pure [frameToLuma], which the tests cover.
 */
fun Bitmap.lumaInto(argb: IntArray, out: ByteArray) =
    frameToLuma(width, height, config == Bitmap.Config.ARGB_8888, { getPixels(it, 0, width, 0, 0, width, height) }, argb, out)

/** [lumaInto] with a [ProximityGate]'s buffers. */
fun Bitmap.lumaInto(buffers: LumaBuffers) = lumaInto(buffers.argb, buffers.luma)
