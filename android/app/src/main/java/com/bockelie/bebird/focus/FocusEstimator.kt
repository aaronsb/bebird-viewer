// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import com.bockelie.bebird.focus.FrameGeometry.BLOCKS
import com.bockelie.bebird.focus.FrameGeometry.SIZE
import kotlin.math.abs

/**
 * The focus / proximity estimator (docs/focus-detection.md): feed every decoded frame, raw and
 * unrotated (the tip mask relies on the probe tip staying put on the raw frame), with its
 * arrival time and the roll from its packet header. Not thread-safe; run it off the UI thread.
 *
 * The result is best effort. The absence of CLOSE or IN-ZONE never means clearance.
 */
class FocusEstimator(private val cfg: FocusConfig = FocusConfig()) {
    private val prim = FramePrimitives()
    private val tip = TipMask(cfg)
    private val tracker = FocusTracker(cfg)
    private val prevSmall = IntArray(BLOCKS)
    private var hasPrev = false
    private val absd = IntArray(BLOCKS)
    private val hist = IntArray(512)
    private var lumaBuf: ByteArray? = null

    /** Tip mask blocks (60×60, row-major, 8×8 px each on the raw frame); true = masked out. */
    fun tipBlocks(): BooleanArray = tip.blocks.copyOf()

    /**
     * One frame: [luma] is SIZE×SIZE, row-major, unsigned bytes (ITU-R 601 luma), [t] the
     * arrival time in seconds, [roll] the packet's roll angle in degrees (0-359).
     */
    fun update(luma: ByteArray, t: Double, roll: Int): FocusResult {
        prim.compute(luma)
        val small = prim.small
        var motion = 0.0
        if (hasPrev) {
            motion = motionOutsideTip(small)
            for (i in 0 until BLOCKS) absd[i] = abs(small[i] - prevSmall[i])
            hist.fill(0)
            for (i in FrameGeometry.circleBlocks) hist[absd[i]]++
            val med = kth(hist, FrameGeometry.circleBlocks.size / 2)
            tip.learn(t, small, prim.edge, absd, med)
        }
        small.copyInto(prevSmall)
        hasPrev = true

        val mask = tip.blocks
        val bright = bright(mask)
        val sharpRaw = sharpness(mask)
        if (tip.rebuilt) {
            val bOld = bright(tip.previous)
            val sOld = sharpness(tip.previous)
            tracker.rescale(
                sharpRatio = if (sOld > 1e-6) sharpRaw / sOld else null,
                brightRatio = if (bOld > 1e-6) bright / bOld else null,
            )
        }
        return tracker.update(t, roll, bright, sharpRaw, motion, tip.fraction)
    }

    /** [update] for a frame held as ints (0-255), e.g. from an ARGB-to-luma conversion. */
    fun update(luma: IntArray, t: Double, roll: Int): FocusResult {
        val buf = lumaBuf ?: ByteArray(SIZE * SIZE).also { lumaBuf = it }
        require(luma.size == buf.size) { "frame is ${luma.size} px, not $SIZE x $SIZE" }
        for (i in luma.indices) buf[i] = luma[i].toByte()
        return update(buf, t, roll)
    }

    /**
     * Median over the non-tip circle blocks of |(small − prev) − shift|, where shift is the
     * median signed difference: a global exposure change counts as no motion.
     */
    private fun motionOutsideTip(small: IntArray): Double {
        val mask = tip.blocks
        hist.fill(0)
        var n = 0
        for (i in FrameGeometry.circleBlocks) if (!mask[i]) {
            hist[signedDiff(small[i], prevSmall[i]) + 256]++
            n++
        }
        if (n == 0) return 0.0
        val shift = kth(hist, n / 2) - 256
        hist.fill(0)
        for (i in FrameGeometry.circleBlocks) if (!mask[i]) {
            hist[abs(signedDiff(small[i], prevSmall[i]) - shift)]++
        }
        return kth(hist, n / 2).toDouble()
    }

    /** a − b as the prototype's offset subtract gives it: clamped to −128..127. */
    private fun signedDiff(a: Int, b: Int) = (a - b).coerceIn(-128, 127)

    /** Mean luma over the circle, tip blocks excluded. */
    private fun bright(mask: BooleanArray): Double {
        var s = 0L
        var n = 0L
        for (b in 0 until BLOCKS) if (!mask[b]) {
            s += prim.lumaSum[b]
            n += FrameGeometry.circleCount[b]
        }
        return if (n == 0L) 0.0 else s.toDouble() / n
    }

    /** Population variance of the Laplacian over the half-resolution circle, tip excluded. */
    private fun sharpness(mask: BooleanArray): Double {
        var s = 0L
        var q = 0L
        var n = 0L
        for (b in 0 until BLOCKS) if (!mask[b]) {
            s += prim.lapSum[b]
            q += prim.lapSq[b]
            n += FrameGeometry.halfCircleCount[b]
        }
        if (n == 0L) return 0.0
        return (q - s.toDouble() * s / n) / n
    }

    private companion object {
        /** The [k]-th smallest (0-based) value counted in [hist]. */
        fun kth(hist: IntArray, k: Int): Int {
            var c = 0
            for (v in hist.indices) {
                c += hist[v]
                if (c > k) return v
            }
            return hist.size - 1
        }
    }
}
