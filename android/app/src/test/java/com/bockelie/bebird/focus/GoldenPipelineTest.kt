// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/**
 * The whole estimator, frames in, against the reference prototype run on the same synthetic
 * frames ([SyntheticScope.approach]). The prototype's image circle is Pillow's rasterised disc
 * (40 edge pixels differ) and its first metrics mask is a bicubic resize, so brightness and
 * sharpness agree closely rather than exactly; motion, the tip mask and the states agree.
 */
class GoldenPipelineTest {
    private fun check(tip: Boolean) {
        val log = FeatureLog.load(if (tip) "synthetic-tip" else "synthetic-notip")
        val est = FocusEstimator()
        val out = SyntheticScope.approach(tip).map { est.update(it.frame, it.t, it.roll) }.toList()
        assertEquals(log.rows.size, out.size)
        var agree = 0
        for ((r, o) in log.rows.zip(out)) {
            assertEquals("t", r.t, o.t, 1e-9)
            assertEquals("bright at ${r.t}", r.bright, o.bright, 0.1)
            assertTrue("sharp at ${r.t}: ${r.sharpRaw} vs ${o.sharpRaw}", abs(r.sharpRaw - o.sharpRaw) <= 0.01 * r.sharpRaw + 0.01)
            assertEquals("motion at ${r.t}", r.motion, o.motion, 0.0)
            assertEquals("tip at ${r.t}", r.tip, o.tipFraction, 1e-5)
            if (r.state == o.state.label) agree++
        }
        println("${log.name}: states agree on $agree of ${out.size} frames")
        assertTrue("states agree on $agree of ${out.size}", agree >= out.size * 0.98)
    }

    @Test fun withTipMatchesThePrototype() = check(tip = true)

    @Test fun withoutTipMatchesThePrototype() = check(tip = false)
}
