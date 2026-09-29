// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

/** ITU-R 601 luma of an ARGB pixel, rounded as Pillow's convert("L") does. */
fun lumaOf(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (r * 19595 + g * 38470 + b * 7471 + 0x8000) shr 16
}

/**
 * Luma of ARGB pixels into [out] (same length). Named apart from the Bitmap adapter
 * (`Bitmap.lumaInto`): with the same name, a call inside that extension resolved to itself.
 */
fun argbToLuma(argb: IntArray, out: ByteArray) {
    require(argb.size == out.size) { "${argb.size} pixels into ${out.size}" }
    for (i in argb.indices) out[i] = lumaOf(argb[i]).toByte()
}

/**
 * A decoded frame to luma, whatever holds its pixels: checks it is the raw [FrameGeometry.SIZE]
 * square software ARGB_8888 frame, lets [readPixels] fill [argb], then converts into [out].
 * Pure, so the Bitmap adapter's whole path is testable on the JVM.
 */
fun frameToLuma(width: Int, height: Int, softwareArgb8888: Boolean, readPixels: (IntArray) -> Unit, argb: IntArray, out: ByteArray) {
    val n = FrameGeometry.SIZE
    require(width == n && height == n) { "frame is ${width}x$height, not ${n}x$n" }
    require(softwareArgb8888) { "frame bitmap is not a software ARGB_8888" }
    readPixels(argb)
    argbToLuma(argb, out)
}
