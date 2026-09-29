// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import com.bockelie.bebird.focus.FrameGeometry.BLOCK
import com.bockelie.bebird.focus.FrameGeometry.BLOCKS
import com.bockelie.bebird.focus.FrameGeometry.GRID
import com.bockelie.bebird.focus.FrameGeometry.HALF
import com.bockelie.bebird.focus.FrameGeometry.SIZE
import kotlin.math.abs

/**
 * The per-frame primitives of §2, computed into reused buffers. Downsampling rounds like
 * Pillow's box filter (rows first, rounded, then columns), and the Laplacian is offset by 128
 * and clamped to 0..255 with the border copied, as the prototype's kernel filter does: the
 * clamp caps |Laplacian| near 127, which changes the variance.
 *
 * Per block it also keeps the luma sum over circle pixels, and the Laplacian sum and sum of
 * squares over half-resolution circle pixels, so brightness and sharpness under any tip mask
 * (the current one and the one before a rebuild) are sums over the non-tip blocks.
 */
internal class FramePrimitives {
    /** Mean of each 8×8 block (60×60). */
    val small = IntArray(BLOCKS)
    /** Mean of each 2×2 block (240×240). */
    val half = IntArray(HALF * HALF)
    /** 3×3 Laplacian of [half], plus 128, clamped. */
    val lap = IntArray(HALF * HALF)
    /** Mean |lap − 128| over each 4×4 block of [lap] (60×60). */
    val edge = IntArray(BLOCKS)

    val lumaSum = LongArray(BLOCKS)
    val lapSum = LongArray(BLOCKS)
    val lapSq = LongArray(BLOCKS)

    private val rowAcc = IntArray(GRID)

    fun compute(luma: ByteArray) {
        require(luma.size == SIZE * SIZE) { "frame is ${luma.size} px, not $SIZE x $SIZE" }
        downsample(luma)
        laplacian()
    }

    private fun downsample(luma: ByteArray) {
        lumaSum.fill(0)
        for (by in 0 until GRID) {
            rowAcc.fill(0)
            for (yy in 0 until BLOCK) {
                val y = by * BLOCK + yy
                val row = y * SIZE
                var x = 0
                for (bx in 0 until GRID) {
                    var s = 0
                    for (k in 0 until BLOCK) s += luma[row + x + k].toInt() and 0xFF
                    rowAcc[bx] += (s + BLOCK / 2) / BLOCK
                    x += BLOCK
                }
                val gy = by * GRID
                for (px in FrameGeometry.spanLeft[y]..FrameGeometry.spanRight[y]) {
                    lumaSum[gy + px / BLOCK] += (luma[row + px].toInt() and 0xFF).toLong()
                }
            }
            for (bx in 0 until GRID) small[by * GRID + bx] = (rowAcc[bx] + BLOCK / 2) / BLOCK
        }
        for (hy in 0 until HALF) {
            val r0 = 2 * hy * SIZE
            val r1 = r0 + SIZE
            val out = hy * HALF
            for (hx in 0 until HALF) {
                val x = 2 * hx
                val a = ((luma[r0 + x].toInt() and 0xFF) + (luma[r0 + x + 1].toInt() and 0xFF) + 1) shr 1
                val b = ((luma[r1 + x].toInt() and 0xFF) + (luma[r1 + x + 1].toInt() and 0xFF) + 1) shr 1
                half[out + hx] = (a + b + 1) shr 1
            }
        }
    }

    private fun laplacian() {
        val n = HALF
        half.copyInto(lap, 0, 0, n)
        half.copyInto(lap, (n - 1) * n, (n - 1) * n, n * n)
        for (y in 1 until n - 1) {
            val r = y * n
            lap[r] = half[r]
            lap[r + n - 1] = half[r + n - 1]
            for (x in 1 until n - 1) {
                val i = r + x
                val v = half[i - n] + half[i + n] + half[i - 1] + half[i + 1] - 4 * half[i] + 128
                lap[i] = if (v < 0) 0 else if (v > 255) 255 else v
            }
        }
        lapSum.fill(0)
        lapSq.fill(0)
        val q = BLOCK / 2  // half-resolution pixels per block side
        for (by in 0 until GRID) {
            rowAcc.fill(0)
            for (yy in 0 until q) {
                val hy = by * q + yy
                val r = hy * n
                for (bx in 0 until GRID) {
                    var s = 0
                    for (k in 0 until q) s += abs(lap[r + bx * q + k] - 128)
                    rowAcc[bx] += (s + q / 2) / q
                }
                // circle pixels at half resolution: full-resolution (2x+1, 2y+1) inside the disc
                val fy = 2 * hy + 1
                if (FrameGeometry.spanLeft[fy] < 0) continue
                val lo = FrameGeometry.spanLeft[fy] / 2         // smallest hx with 2hx+1 >= left
                val hi = (FrameGeometry.spanRight[fy] - 1) / 2  // largest hx with 2hx+1 <= right
                val gy = by * GRID
                for (hx in lo..hi) {
                    val v = lap[r + hx].toLong()
                    val b = gy + hx / q
                    lapSum[b] += v
                    lapSq[b] += v * v
                }
            }
            for (bx in 0 until GRID) edge[by * GRID + bx] = (rowAcc[bx] + q / 2) / q
        }
    }
}
