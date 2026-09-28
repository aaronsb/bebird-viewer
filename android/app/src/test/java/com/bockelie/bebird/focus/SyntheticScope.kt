// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

/**
 * Synthetic raw frames: a blocky random texture (8-px cells) that moves between frames, scaled
 * by a gain (the LED-lit brightness step of an approach), with an optional static bright disc
 * in the lower right standing in for the probe tip. [approach] is the same script, bit for bit,
 * as the generator that produced src/test/resources/focus/synthetic-*.csv from the reference
 * prototype, so the whole pipeline can be compared frame by frame.
 */
object SyntheticScope {
    const val N = FrameGeometry.SIZE
    private const val M = 0xFFFFFFFFL

    fun hash32(a: Int, b: Int): Int {
        var h = ((a.toLong() * 73856093L) xor (b.toLong() * 19349663L)) and M
        h = h xor (h ushr 13)
        h = (h * 1274126177L) and M
        h = h xor (h ushr 16)
        return h.toInt()
    }

    /** The texture value (0-127) at frame pixel (x, y) with the scene offset by (ox, oy). */
    fun texture(x: Int, y: Int, ox: Int, oy: Int) = hash32((x + ox) shr 3, (y + oy) shr 3) and 127

    fun inTip(x: Int, y: Int): Boolean {
        val dx = x - 380
        val dy = y - 380
        return dx * dx + dy * dy < 10000
    }

    /**
     * One frame into [out]: the tip (if [tip]) at a constant 205, and elsewhere [scene] of the
     * texture value and the pixel position.
     */
    fun frame(out: ByteArray, ox: Int, oy: Int, tip: Boolean, scene: (tex: Int, x: Int, y: Int) -> Int) {
        for (y in 0 until N) for (x in 0 until N) {
            val v = if (tip && inTip(x, y)) 205 else scene(texture(x, y, ox, oy), x, y)
            out[y * N + x] = v.coerceIn(0, 255).toByte()
        }
    }

    fun lit(gain: Int): (Int, Int, Int) -> Int = { tex, _, _ -> minOf(255, (40 + tex) * gain shr 4) }

    class Step(val t: Double, val roll: Int, val frame: ByteArray)

    /**
     * The golden script (30 s at 10 fps): coarse handling in dim light (0-12 s), an approach with
     * the brightness ramping up (12-15 s), careful slow movement lit (15-25 s), set down (25 s+).
     */
    fun approach(tip: Boolean, frames: Int = 300): Sequence<Step> = sequence {
        var ox = 0
        var oy = 0
        for (n in 0 until frames) {
            val (sx, sy, gain, roll) = script(n)
            ox += sx; oy += sy
            val px = ByteArray(N * N)
            frame(px, ox, oy, tip, lit(gain))
            yield(Step(Math.round((0.35 + n * 0.1) * 10000) / 10000.0, roll, px))
        }
    }

    /** (step x, step y, gain / 16, roll) of frame [n]. */
    private fun script(n: Int): List<Int> = when {
        n < 120 -> listOf(9, 5, 6, 100 + (n * 37) % 21 - 10)
        n < 150 -> listOf(3, 1, 6 + (n - 120) * 16 / 30, 100 + n % 3 - 1)
        n < 250 -> listOf(1, 0, 22, 100 + (n / 3) % 2)
        else -> listOf(0, 0, 22, 100)
    }
}
