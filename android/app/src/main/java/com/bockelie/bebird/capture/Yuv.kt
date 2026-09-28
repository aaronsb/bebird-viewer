// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

/**
 * ARGB pixels to YUV 4:2:0 (BT.601, limited range: Y 16-235, U/V 16-240), the encoder's input.
 * Chroma is the average of each 2 × 2 block. Width and height must be even.
 */
object Yuv {
    class Planes(val width: Int, val height: Int, val y: ByteArray, val u: ByteArray, val v: ByteArray)

    fun from(argb: IntArray, width: Int, height: Int): Planes = into(argb, planes(width, height))

    /** Empty planes for a [width] × [height] picture, to reuse with [into]. */
    fun planes(width: Int, height: Int): Planes {
        require(width % 2 == 0 && height % 2 == 0) { "$width x $height: must be even" }
        val c = (width / 2) * (height / 2)
        return Planes(width, height, ByteArray(width * height), ByteArray(c), ByteArray(c))
    }

    /** Convert [argb] (planes.width × planes.height) into [planes], reusing its arrays. */
    fun into(argb: IntArray, planes: Planes): Planes {
        val width = planes.width
        val height = planes.height
        require(argb.size == width * height) { "${argb.size} pixels for $width x $height" }
        val y = planes.y
        val cw = width / 2
        val u = planes.u
        val v = planes.v
        for (row in 0 until height) for (col in 0 until width) {
            val p = argb[row * width + col]
            y[row * width + col] = luma(p shr 16 and 0xFF, p shr 8 and 0xFF, p and 0xFF).toByte()
        }
        for (cy in 0 until height / 2) for (cx in 0 until cw) {
            var r = 0; var g = 0; var b = 0
            for (dy in 0..1) for (dx in 0..1) {
                val p = argb[(2 * cy + dy) * width + 2 * cx + dx]
                r += p shr 16 and 0xFF; g += p shr 8 and 0xFF; b += p and 0xFF
            }
            r /= 4; g /= 4; b /= 4
            u[cy * cw + cx] = clamp(((-38 * r - 74 * g + 112 * b + 128) shr 8) + 128).toByte()
            v[cy * cw + cx] = clamp(((112 * r - 94 * g - 18 * b + 128) shr 8) + 128).toByte()
        }
        return planes
    }

    fun luma(r: Int, g: Int, b: Int) = clamp(((66 * r + 129 * g + 25 * b + 128) shr 8) + 16)

    private fun clamp(x: Int) = x.coerceIn(0, 255)

    /** Encoders want sizes in multiples of 16: [n] rounded up to one (the caller fills the extra rows). */
    fun padTo16(n: Int) = (n + 15) / 16 * 16
}
