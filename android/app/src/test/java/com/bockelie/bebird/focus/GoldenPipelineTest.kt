// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The whole estimator, frames in, against the reference prototype run on the same synthetic
 * frames ([SyntheticScope]; logs from src/test/tools/focus_golden.py). The prototype's image
 * circle is Pillow's rasterised disc (40 edge pixels differ) and its first metrics mask is a
 * bicubic resize, so brightness and sharpness agree closely rather than exactly; motion, the
 * tip mask and the states agree exactly.
 */
class GoldenPipelineTest {
    private fun check(name: String, steps: Sequence<SyntheticScope.Step>): List<FocusResult> {
        val log = FeatureLog.load(name)
        val est = FocusEstimator()
        val out = steps.map { est.update(it.frame, it.t, it.roll) }.toList()
        assertEquals(log.rows.size, out.size)
        for ((r, o) in log.rows.zip(out)) {
            assertEquals("t", r.t, o.t, 1e-9)
            assertEquals("bright at ${r.t}", r.bright, o.bright, 0.1)
            assertTrue("sharp at ${r.t}: ${r.sharpRaw} vs ${o.sharpRaw}", abs(r.sharpRaw - o.sharpRaw) <= 0.01 * r.sharpRaw + 0.01)
            assertEquals("motion at ${r.t}", r.motion, o.motion, 0.0)
            assertEquals("tip at ${r.t}", r.tip, o.tipFraction, 1e-5)
            assertEquals("state at ${r.t}", r.state, o.state.label)
            assertEquals("CLOSE at ${r.t}", r.close, o.close)
            assertEquals("armed at ${r.t}", r.armed, o.armed)
        }
        return out
    }

    private fun tipAt(out: List<FocusResult>, t: Double) = out.last { it.t <= t }.tipFraction

    @Test fun withTipMatchesThePrototype() {
        check("synthetic-tip", SyntheticScope.approach(tip = true))
    }

    @Test fun withoutTipMatchesThePrototype() {
        check("synthetic-notip", SyntheticScope.approach(tip = false))
    }

    @Test fun capGrowsInByEvidenceKeepsThroughADipAndLeavesWhenGone() {
        val out = check("synthetic-cap", SyntheticScope.cap())
        val disc = tipAt(check("synthetic-removal", SyntheticScope.removal()), 9.5)  // the disc alone
        assertTrue("cap grown in by 4 s", tipAt(out, 4.0) > disc + 0.05)
        assertTrue("kept through the 8-11 s dip (hysteresis)", tipAt(out, 11.0) > disc + 0.05)
        assertEquals("gone once its evidence decays", disc, tipAt(out, 29.0), 0.01)
    }

    @Test fun removedTipLeavesOnlyAfterSustainedChange() {
        val out = check("synthetic-removal", SyntheticScope.removal())
        assertTrue(tipAt(out, 9.9) > 0.1)
        assertTrue("still masked 4 s after removal (sustained-off)", tipAt(out, 14.0) > 0.1)
        assertEquals(0.0, tipAt(out, 20.0), 0.0)
    }
}
