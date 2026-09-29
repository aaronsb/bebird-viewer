// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

/**
 * The raw, unrotated sensor frame the estimator works on: 480×480, optical centre (240, 240),
 * metrics restricted to the image circle of radius 230, and a 60×60 grid of 8×8 blocks.
 */
object FrameGeometry {
    const val SIZE = 480
    const val CENTER = 240
    const val HALF = SIZE / 2
    const val BLOCK = 8
    const val GRID = SIZE / BLOCK
    const val BLOCKS = GRID * GRID

    /**
     * Squared radius of the image circle. The prototype rasterises an r = 230 disc with Pillow;
     * this integer test gives the same set of circle blocks and differs by 40 edge pixels.
     */
    private const val R2 = 53120

    fun inCircle(x: Int, y: Int): Boolean {
        val dx = x - CENTER
        val dy = y - CENTER
        return dx * dx + dy * dy <= R2
    }

    /** First and last circle pixel of each row, or -1 / -2 for a row that misses the circle. */
    internal val spanLeft = IntArray(SIZE)
    internal val spanRight = IntArray(SIZE)

    /** Circle pixels per block at full resolution, and at half resolution (pixel (2x+1, 2y+1)). */
    internal val circleCount = IntArray(BLOCKS)
    internal val halfCircleCount = IntArray(BLOCKS)

    /** "Circle blocks": more than 200/255 covered by the disc (box-downsampled like the frame). */
    val circleBlocks: IntArray
    val isCircleBlock = BooleanArray(BLOCKS)

    init {
        for (y in 0 until SIZE) {
            spanLeft[y] = -1; spanRight[y] = -2
            for (x in 0 until SIZE) if (inCircle(x, y)) {
                if (spanLeft[y] < 0) spanLeft[y] = x
                spanRight[y] = x
                circleCount[(y / BLOCK) * GRID + x / BLOCK]++
                if (x % 2 == 1 && y % 2 == 1) halfCircleCount[(y / BLOCK) * GRID + x / BLOCK]++
            }
        }
        // The disc mask downsampled exactly as a frame is (rows first, rounded, then columns).
        val list = ArrayList<Int>()
        for (by in 0 until GRID) for (bx in 0 until GRID) {
            var acc = 0
            for (yy in 0 until BLOCK) {
                val y = by * BLOCK + yy
                var n = 0
                for (xx in 0 until BLOCK) if (inCircle(bx * BLOCK + xx, y)) n++
                acc += (n * 255 + BLOCK / 2) / BLOCK
            }
            if ((acc + BLOCK / 2) / BLOCK > 200) {
                list.add(by * GRID + bx)
                isCircleBlock[by * GRID + bx] = true
            }
        }
        circleBlocks = list.toIntArray()
    }
}
