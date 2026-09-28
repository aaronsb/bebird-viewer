// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Frame-level stages: primitives, motion, the tip mask. */
class FocusEstimatorTest {
    private val n = FrameGeometry.SIZE

    /** [seconds] of 10-fps frames with the scene moving [step] px per frame. */
    private fun run(
        est: FocusEstimator, seconds: Double, tip: Boolean, step: Int = 9, stepY: Int = step / 2,
        t0: Double = 0.0, scene: (Int, Int, Int) -> Int = SyntheticScope.lit(10),
        extra: ((ByteArray) -> Unit)? = null,
    ): List<FocusResult> {
        val px = ByteArray(n * n)
        return List(Math.round(seconds * 10).toInt()) { k ->
            SyntheticScope.frame(px, k * step, k * stepY, tip, scene)
            extra?.invoke(px)
            est.update(px, t0 + k / 10.0, 100 + (k * 37) % 21 - 10)
        }
    }

    @Test fun circleBlocksMatchThePrototype() {
        assertEquals(2558, FrameGeometry.circleBlocks.size)
        assertTrue(FrameGeometry.inCircle(240, 10) && !FrameGeometry.inCircle(5, 5))
    }

    @Test fun lumaRoundsLikePillow() {
        assertEquals(255, lumaOf(0xFFFFFFFF.toInt()))
        assertEquals(0, lumaOf(0xFF000000.toInt()))
        assertEquals(76, lumaOf(0xFFFF0000.toInt()))
        assertEquals(150, lumaOf(0xFF00FF00.toInt()))
        assertEquals(29, lumaOf(0xFF0000FF.toInt()))
    }

    @Test fun flatFrameHasNoSharpnessAndItsOwnBrightness() {
        val r = FocusEstimator().update(ByteArray(n * n) { 77 }, 0.0, 0)
        assertEquals(77.0, r.bright, 1e-9)
        assertEquals(0.0, r.sharpRaw, 1e-9)
    }

    @Test fun laplacianIsClampedAround128() {
        // a checkerboard of 2×2-px squares: ±4·255 at half resolution, clamped to 0 and 255,
        // so the variance is 127.5² rather than (4·255)²
        val px = ByteArray(n * n) { i -> if (((i % n) / 2 + (i / n) / 2) % 2 == 0) 0 else 255.toByte() }
        val r = FocusEstimator().update(px, 0.0, 0)
        assertEquals(127.5 * 127.5, r.sharpRaw, 1.0)
    }

    @Test fun motionIgnoresAGlobalExposureChange() {
        val est = FocusEstimator()
        val a = ByteArray(n * n)
        SyntheticScope.frame(a, 0, 0, false, SyntheticScope.lit(10))
        est.update(a, 0.0, 100)
        val brighter = ByteArray(n * n) { ((a[it].toInt() and 0xFF) + 20).coerceAtMost(255).toByte() }
        assertEquals(0.0, est.update(brighter, 0.1, 100).motion, 0.0)
        val moved = ByteArray(n * n)
        SyntheticScope.frame(moved, 9, 5, false, SyntheticScope.lit(10))
        assertTrue(est.update(moved, 0.2, 100).motion >= 5)
    }

    @Test fun shouldLearnTheTipWithinTwoSecondsOfCoarseMotion() {
        val est = FocusEstimator()
        val out = run(est, 4.0, tip = true)
        val first = out.first { it.tipPresent }
        assertTrue("tip present at ${first.t} s", first.t <= 2.0)
        assertTrue(out.last().tipFraction >= 0.15)
        // every circle block inside the tip disc and the tip region is masked, and nothing far
        // from it (one block of dilation margin)
        val mask = est.tipBlocks()
        for (b in FrameGeometry.circleBlocks) {
            val bx = b % FrameGeometry.GRID * 8
            val by = b / FrameGeometry.GRID * 8
            val inside = (0..7).all { dy -> (0..7).all { dx -> SyntheticScope.inTip(bx + dx, by + dy) } }
            val near = (-8..15).any { dy -> (-8..15).any { dx -> SyntheticScope.inTip(bx + dx, by + dy) } }
            if (inside && bx >= 192 && by >= 192) assertTrue("block ($bx, $by) masked", mask[b])
            if (!near) assertTrue("block ($bx, $by) not masked", !mask[b])
        }
    }

    @Test fun shouldExcludeTheTipFromBrightness() {
        val est = FocusEstimator()
        val out = run(est, 4.0, tip = true, scene = SyntheticScope.lit(6))
        // the dim scene is ~39 luma; the tip (205) inflates it until masked
        assertTrue(out[1].bright > 55)
        assertEquals(39.0, out.last().bright, 3.0)
    }

    @Test fun shouldFindNoMaskWithoutATip() {
        val out = run(FocusEstimator(), 10.0, tip = false)
        assertTrue(out.all { it.tipFraction == 0.0 })
    }

    @Test fun shouldRejectAStaticGlintNearTheCentre() {
        val glint: (ByteArray) -> Unit = { px ->
            for (y in 200 until 280) for (x in 200 until 280) px[y * n + x] = 220.toByte()
        }
        assertTrue(run(FocusEstimator(), 8.0, tip = false, extra = glint).all { it.tipFraction == 0.0 })
        val est = FocusEstimator()
        run(est, 8.0, tip = true, extra = glint)
        val mask = est.tipBlocks()
        assertTrue((25 until 35).all { by -> (25 until 35).none { bx -> mask[by * FrameGeometry.GRID + bx] } })
    }

    @Test fun shouldLearnNothingWhileMostOfTheCircleIsSaturated() {
        // 8-px steps keep blocks on texture cells: 60 % of cells saturated, the rest moving and dim
        fun scene(saturated: Int): (Int, Int, Int) -> Int = { tex, _, _ -> if (tex < saturated) 255 else tex / 2 }
        val washed = run(FocusEstimator(), 6.0, tip = true, step = 8, stepY = 8, scene = scene(77))
        assertTrue(washed.all { it.tipFraction == 0.0 })
        val control = run(FocusEstimator(), 6.0, tip = true, step = 8, stepY = 8, scene = scene(25))
        assertTrue(control.last().tipPresent)
    }
}
