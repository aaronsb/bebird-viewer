// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

/** ITU-R 601 luma of an ARGB pixel, rounded as Pillow's convert("L") does. */
fun lumaOf(argb: Int): Int {
    val r = (argb shr 16) and 0xFF
    val g = (argb shr 8) and 0xFF
    val b = argb and 0xFF
    return (r * 19595 + g * 38470 + b * 7471 + 0x8000) shr 16
}

/** Luma of ARGB pixels into [out] (same length). */
fun lumaInto(argb: IntArray, out: ByteArray) {
    require(argb.size == out.size) { "${argb.size} pixels into ${out.size}" }
    for (i in argb.indices) out[i] = lumaOf(argb[i]).toByte()
}
