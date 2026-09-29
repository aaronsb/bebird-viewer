// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

/**
 * Synthetic raw frames: a blocky random texture (8-px cells) that moves between frames, scaled
 * by a gain (the LED-lit brightness step of an approach), with an optional static bright disc
 * in the lower right standing in for the probe tip, and an optional ring around it standing in
 * for the translucent cap. The scripts are the same, bit for bit, as those of
 * src/test/tools/focus_golden.py, which produced src/test/resources/focus/synthetic-*.csv from
 * the reference prototype, so the whole pipeline can be compared frame by frame.
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

    fun inRing(x: Int, y: Int): Boolean {
        val dx = x - 380
        val dy = y - 380
        return dx * dx + dy * dy in 10000 until 19600
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

    /** One frame of a script: scene step, gain / 16, roll, tip on, and the cap ring's value. */
    data class Scene(val sx: Int, val sy: Int, val gain: Int, val roll: Int, val tip: Boolean, val ring: Int? = null)

    /** A script run as frames at 10 fps from t = 0.35 s. */
    fun run(frames: Int, withTip: Boolean = true, script: (Int) -> Scene): Sequence<Step> = sequence {
        var ox = 0
        var oy = 0
        for (n in 0 until frames) {
            val sc = script(n)
            ox += sc.sx; oy += sc.sy
            val px = ByteArray(N * N)
            val lit = lit(sc.gain)
            val ring = sc.ring
            frame(px, ox, oy, withTip && sc.tip) { tex, x, y -> if (ring != null && inRing(x, y)) ring else lit(tex, x, y) }
            yield(Step(Math.round((0.35 + n * 0.1) * 10000) / 10000.0, sc.roll, px))
        }
    }

    private fun coarseRoll(n: Int) = 100 + (n * 37) % 21 - 10

    /**
     * Coarse handling in dim light (0-12 s), an approach with the brightness ramping up
     * (12-15 s), careful slow movement lit (15-25 s), set down (25 s+).
     */
    fun approach(tip: Boolean, frames: Int = 300) = run(frames, tip) { n ->
        when {
            n < 120 -> Scene(9, 5, 6, coarseRoll(n), true)
            n < 150 -> Scene(3, 1, 6 + (n - 120) * 16 / 30, 100 + n % 3 - 1, true)
            n < 250 -> Scene(1, 0, 22, 100 + (n / 3) % 2, true)
            else -> Scene(0, 0, 22, 100, true)
        }
    }

    /**
     * Coarse handling with the tip and a bright, soft ring around it drifting 150-210 (not
     * static: only cap evidence can add it), dimmed to 60 at 8-11 s (hysteresis keeps it) and
     * for good from 15 s (its evidence decays and it leaves).
     */
    fun cap(frames: Int = 300) = run(frames) { n ->
        val dim = n in 80 until 110 || n >= 150
        Scene(9, 5, 10, coarseRoll(n), true, if (dim) 60 else 150 + Math.abs((2 * n) % 120 - 60))
    }

    /** Coarse handling lit; the tip is taken off at 10 s. */
    fun removal(frames: Int = 250) = run(frames) { n -> Scene(9, 5, 10, coarseRoll(n), n < 100) }
}
