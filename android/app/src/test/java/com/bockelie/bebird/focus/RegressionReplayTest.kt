// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recorded sessions of issue #27, replayed as features through [FocusTracker]: the per-frame
 * brightness, sharpness, roll, motion and tip fraction the reference prototype measured on the
 * (private) frames, and the mask-rebuild rescale ratios. The states must agree frame by frame
 * with the prototype's, and per 5 s window with the table in docs/focus-detection.md. (The tip
 * fraction here is an input; the tip mask itself is checked by [GoldenPipelineTest].)
 */
class RegressionReplayTest {
    private class Replayed(val row: FeatureLog.Row, val out: FocusResult)

    private fun replay(name: String): List<Replayed> {
        val log = FeatureLog.load(name)
        val tracker = FocusTracker()
        return log.rows.map { r ->
            if (r.kSharp != null || r.kBright != null) tracker.rescale(r.kSharp, r.kBright)
            Replayed(r, tracker.update(r.t, r.roll, r.bright, r.sharpRaw, r.motion, r.tip))
        }
    }

    private fun check(name: String, inZone: List<Int>, close: List<Int>) {
        val rs = replay(name)
        for (r in rs) {
            assertEquals("$name state at ${r.row.t}", r.row.state, r.out.state.label)
            assertEquals("$name CLOSE at ${r.row.t}", r.row.close, r.out.close)
            assertEquals("$name armed at ${r.row.t}", r.row.armed, r.out.armed)
        }

        val z = FeatureLog.perWindow(rs, { it.row.t }) { it.out.state == FocusState.IN_ZONE }
        val c = FeatureLog.perWindow(rs, { it.row.t }) { it.out.close }
        assertEquals("$name in-zone % per 5 s", inZone, z)
        assertEquals("$name CLOSE % per 5 s", close, c)
    }

    @Test fun earWithTip() = check(
        "ear",
        listOf(0, 0, 0, 0, 0, 32, 100, 100, 100, 100, 10, 0),
        listOf(0, 0, 0, 0, 0, 32, 100, 100, 100, 100, 10, 0)
    )

    @Test fun earWithoutTip() = check(
        "notip",
        listOf(0, 0, 0, 44, 62, 0, 0, 0, 0),
        listOf(0, 0, 0, 44, 48, 0, 0, 0, 0)
    )

    @Test fun rulerWithoutTip() = check(
        "live-173059",
        listOf(0, 0, 0, 8, 79, 100, 100, 83, 2, 0, 2, 73, 69, 94, 83, 44, 56, 0, 0, 0, 7, 18, 0),
        listOf(0, 0, 0, 8, 73, 100, 33, 15, 2, 0, 2, 73, 60, 41, 56, 37, 46, 0, 0, 0, 7, 18, 0)
    )

    @Test fun rulerWithLedDipsIgnored() = check(
        "live-174920",
        listOf(0, 0, 29, 56, 61, 0, 22, 35, 17, 28, 0, 0, 0, 0, 0),
        listOf(0, 0, 27, 49, 61, 0, 22, 35, 15, 26, 0, 0, 0, 0, 0)
    )

    @Test fun tipFittedMidSession() = check(
        "live-175352",
        listOf(0, 2, 67, 88, 65, 0, 0, 0, 56, 30, 0, 0, 0, 0, 0),
        listOf(0, 2, 65, 88, 65, 0, 0, 0, 56, 30, 0, 0, 0, 0, 0)
    )

    @Test fun earWithTipOperatorValidated() = check(
        "live-180731",
        listOf(0, 0, 8, 34, 18, 0, 0, 18, 0, 0, 0, 0, 29, 43, 69, 100, 69, 71, 0, 0, 0, 0, 0, 0, 0, 0, 0),
        listOf(0, 0, 8, 32, 11, 0, 0, 18, 0, 0, 0, 0, 29, 43, 69, 100, 67, 71, 0, 0, 0, 0, 0, 0, 0, 0, 0)
    )

    @Test fun noInZoneWhileFarOrInAir() {
        // ear 0-10 s (in air, coarse), live-173059 0-15 s (far ruler, room-lit and sharp)
        assertTrue(replay("ear").filter { it.row.t < 10 }.none { it.out.state == FocusState.IN_ZONE })
        assertTrue(replay("live-173059").filter { it.row.t < 15 }.none { it.out.state == FocusState.IN_ZONE })
    }

    @Test fun restingWhenSetDown() {
        val notip = replay("notip").filter { it.row.t >= 36 }
        assertTrue(notip.count { it.out.state == FocusState.RESTING } > notip.size / 2)
        val tipped = replay("live-175352").filter { it.row.t >= 60 }
        assertTrue(tipped.count { it.out.state == FocusState.RESTING } > tipped.size / 2)
    }
}
