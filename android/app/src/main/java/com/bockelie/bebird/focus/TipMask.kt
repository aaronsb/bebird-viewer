// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import com.bockelie.bebird.focus.FrameGeometry.BLOCKS
import com.bockelie.bebird.focus.FrameGeometry.GRID
import kotlin.math.abs
import kotlin.math.min

/**
 * The probe tip's mask on the raw frame (§3). The tip is fixed to the camera, so its blocks keep
 * their value while the scene moves: per block, a long-run mean and variance are updated only
 * on frames where the scene clearly moves, and a bright, static block joins. It leaves only
 * after sustained change, so walls briefly pressing on the rim don't erase it. The translucent
 * cap (lit by the scene, so not static) is grown in from bright, soft, steady evidence next to
 * the static rim, and a lower-right prior drops what can't be the probe (LED glints, paper).
 */
internal class TipMask(private val cfg: FocusConfig) {
    private val mu = DoubleArray(BLOCKS)
    private val variance = DoubleArray(BLOCKS) { cfg.tipSdOff * cfg.tipSdOff }
    private val on = BooleanArray(BLOCKS)
    private val off = IntArray(BLOCKS)
    private val blob = DoubleArray(BLOCKS)

    // the last tipFastFrames strongly moving frames, for the fast seed
    private val ringFrames = Array(cfg.tipFastFrames) { IntArray(BLOCKS) }
    private val ringTimes = DoubleArray(cfg.tipFastFrames)
    private var ringStart = 0
    private var ringSize = 0

    private var updates = 0
    private var frames = 0

    /** Mask blocks after the median and dilation (60×60; may reach past the circle blocks). */
    val blocks = BooleanArray(BLOCKS)
    /** [blocks] before the last rebuild, while [rebuilt] is set. */
    val previous = BooleanArray(BLOCKS)
    /** Set when [learn] rebuilt the mask on this frame; the caller rescales running levels. */
    var rebuilt = false; private set
    var fraction = 0.0; private set
    var any = false; private set

    private val core = BooleanArray(BLOCKS)  // pre-dilation mask of the last rebuild
    private val work = BooleanArray(BLOCKS)
    private val work2 = BooleanArray(BLOCKS)
    private val isCand = BooleanArray(BLOCKS)
    private val stack = IntArray(BLOCKS)
    private val comp = IntArray(BLOCKS)
    private val seen = BooleanArray(BLOCKS)
    private val queue = IntArray(BLOCKS)
    private val dist = IntArray(BLOCKS)
    private val fresh = IntArray(BLOCKS)

    /**
     * One frame (not the first, which has no previous): [small] block means, [edge] block
     * Laplacian energy, [absd] |small − previous small|, [med] the median of absd over the
     * circle blocks.
     */
    fun learn(t: Double, small: IntArray, edge: IntArray, absd: IntArray, med: Int) {
        rebuilt = false
        val circle = FrameGeometry.circleBlocks
        for (i in circle) {
            if (small[i] >= cfg.blobLuma && edge[i] <= cfg.blobEdge && absd[i] <= cfg.blobStep) {
                blob[i] += cfg.blobAlpha * (1.0 - blob[i])
            } else {
                blob[i] -= cfg.blobDecay * blob[i]  // slow: walls occluding the cap mustn't erase it
            }
        }
        frames++
        if (any && frames % cfg.blobRebuildEvery == 0) rebuild()
        if (med < cfg.tipMotion) return  // the scene isn't moving: nothing to learn
        // A wall on the lens or white paper saturates and looks static; the tip alone is ~20-25 %.
        // Counted over the whole grid, as the prototype does (the corners are dark).
        var saturated = 0
        for (v in small) if (v >= cfg.tipSaturatedLuma) saturated++
        if (saturated > cfg.tipLearnMaxSaturated * circle.size) return

        val first = updates == 0
        if (med >= cfg.tipFastMotion) pushRing(t, small)
        while (ringSize > 0 && (ringSize > cfg.tipFastFrames || ringTimes[ringStart] < t - cfg.tipFastAge)) {
            ringStart = (ringStart + 1) % ringFrames.size
            ringSize--
        }
        val seed = ringSize == cfg.tipFastFrames && med >= cfg.tipFastMotion
        val sdOn = cfg.tipSdOn * cfg.tipSdOn
        val sdOff = cfg.tipSdOff * cfg.tipSdOff
        for (i in circle) {
            val v = small[i]
            if (first) {
                mu[i] = v.toDouble()
                continue
            }
            if (seed && v >= cfg.tipMinLuma) {
                val range = ringRange(i)
                if (range <= cfg.tipFastRange) {
                    mu[i] = v.toDouble()
                    variance[i] = min(variance[i], (range / 2.0) * (range / 2.0))
                }
            }
            val dv = min(cfg.tipDvClamp, abs(v - mu[i]))
            mu[i] += cfg.tipAlpha * (v - mu[i])
            variance[i] += cfg.tipAlpha * (dv * dv - variance[i])
            if (on[i]) {
                if (variance[i] > sdOff || mu[i] < cfg.tipMinLuma * 0.8) {
                    off[i]++
                    if (off[i] >= cfg.tipOffUpdates) on[i] = false
                } else {
                    off[i] = 0
                }
            } else if (variance[i] < sdOn && mu[i] >= cfg.tipMinLuma) {
                on[i] = true
                off[i] = 0
            }
        }
        updates++
        if (updates % cfg.tipRebuildEvery == 0) rebuild()
    }

    private fun pushRing(t: Double, small: IntArray) {
        val cap = ringFrames.size
        if (ringSize == cap) {  // full: overwrite the oldest (it would be dropped right after)
            ringStart = (ringStart + 1) % cap
            ringSize--
        }
        val slot = (ringStart + ringSize) % cap
        small.copyInto(ringFrames[slot])
        ringTimes[slot] = t
        ringSize++
    }

    private fun ringRange(i: Int): Int {
        var hi = 0
        var lo = 255
        for (k in 0 until ringSize) {
            val v = ringFrames[(ringStart + k) % ringFrames.size][i]
            if (v > hi) hi = v
            if (v < lo) lo = v
        }
        return hi - lo
    }

    private fun inRegion(i: Int) = i % GRID >= cfg.tipRegion * GRID && i / GRID >= cfg.tipRegion * GRID

    private fun rebuild() {
        if (!rebuilt) blocks.copyInto(previous)  // the mask the running levels were measured with
        rebuilt = true
        val ready = updates >= cfg.tipMinUpdates
        for (i in 0 until BLOCKS) work[i] = ready && on[i]
        applyPrior()
        growCap()
        limitGrowth()
        core.fill(false)
        for (i in FrameGeometry.circleBlocks) core[i] = work[i]
        // drop isolated blocks (3×3 median), then dilate one block (3×3 max); edges replicate
        rank3x3(work, work2, 5)
        rank3x3(work2, blocks, 1)
        var count = 0
        for (b in blocks) if (b) count++
        fraction = count.toDouble() / FrameGeometry.circleBlocks.size
        any = count > 0
    }

    /**
     * Lower-right prior: keep a 4-connected component of [work] only if enough of it lies in the
     * corner where the probe body sits, and only its part inside the tip region.
     */
    private fun applyPrior() {
        seen.fill(false)
        for (i0 in 0 until BLOCKS) {
            if (!work[i0] || seen[i0]) continue
            var sp = 0
            var n = 0
            stack[sp++] = i0
            seen[i0] = true
            while (sp > 0) {
                val i = stack[--sp]
                comp[n++] = i
                val x = i % GRID
                if (x > 0 && work[i - 1] && !seen[i - 1]) { seen[i - 1] = true; stack[sp++] = i - 1 }
                if (x < GRID - 1 && work[i + 1] && !seen[i + 1]) { seen[i + 1] = true; stack[sp++] = i + 1 }
                if (i >= GRID && work[i - GRID] && !seen[i - GRID]) { seen[i - GRID] = true; stack[sp++] = i - GRID }
                if (i + GRID < BLOCKS && work[i + GRID] && !seen[i + GRID]) { seen[i + GRID] = true; stack[sp++] = i + GRID }
            }
            var inCorner = 0
            for (k in 0 until n) {
                val i = comp[k]
                if (i % GRID >= cfg.tipPriorX * GRID && i / GRID >= cfg.tipPriorY * GRID) inCorner++
            }
            for (k in 0 until n) {
                val i = comp[k]
                if (inCorner < cfg.tipPriorMin || !inRegion(i)) work[i] = false
            }
        }
    }

    /**
     * Grow into the adjoining bright, soft blob (the cap), at most blobReach steps from the
     * static part, with hysteresis for blocks already in the mask.
     */
    private fun growCap() {
        isCand.fill(false)
        var qh = 0
        var qt = 0
        for (i in FrameGeometry.circleBlocks) {
            isCand[i] = blob[i] > (if (core[i]) cfg.blobOff else cfg.blobOn)
            if (work[i]) { queue[qt] = i; dist[qt] = 0; qt++ }
        }
        while (qh < qt) {
            val i = queue[qh]
            val d = dist[qh]
            qh++
            if (d >= cfg.blobReach) continue
            val x = i % GRID
            val y = i / GRID
            if (x <= 0 || x >= GRID - 1 || y <= 0 || y >= GRID - 1) continue
            for (k in 0 until 4) {
                val j = when (k) { 0 -> i - 1; 1 -> i + 1; 2 -> i - GRID; else -> i + GRID }
                if (isCand[j] && !work[j] && inRegion(j)) {
                    work[j] = true
                    queue[qt] = j; dist[qt] = d + 1; qt++
                }
            }
        }
    }

    /**
     * At most tipMaxGrow of the circle may join per rebuild (a transient passing every test can't
     * balloon the mask in one step); the most static (lowest variance, then lowest index) stay.
     */
    private fun limitGrowth() {
        var n = 0
        for (i in FrameGeometry.circleBlocks) if (work[i] && !core[i]) fresh[n++] = i
        val cap = (cfg.tipMaxGrow * FrameGeometry.circleBlocks.size).toInt()
        if (n <= cap) return
        // stable insertion sort by variance (fresh is in index order); rare and at most ~600
        for (k in 1 until n) {
            val i = fresh[k]
            var j = k - 1
            while (j >= 0 && variance[fresh[j]] > variance[i]) { fresh[j + 1] = fresh[j]; j-- }
            fresh[j + 1] = i
        }
        for (k in cap until n) work[fresh[k]] = false
    }

    /** out = at least [need] of the 3×3 neighbourhood set, edges replicated (5: median, 1: max). */
    private fun rank3x3(src: BooleanArray, out: BooleanArray, need: Int) {
        for (y in 0 until GRID) for (x in 0 until GRID) {
            var n = 0
            for (dy in -1..1) for (dx in -1..1) {
                val yy = (y + dy).coerceIn(0, GRID - 1)
                val xx = (x + dx).coerceIn(0, GRID - 1)
                if (src[yy * GRID + xx]) n++
            }
            out[y * GRID + x] = n >= need
        }
    }
}
