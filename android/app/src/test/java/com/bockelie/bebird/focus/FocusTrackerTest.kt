// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FocusTrackerTest {
    /** Frames at 10 fps from t = 0; the roll alternates 100/101 (careful, hand-held) by default. */
    private class Feed(val tracker: FocusTracker = FocusTracker()) {
        var n = 0
        val t get() = n / 10.0
        lateinit var last: FocusResult

        fun frames(
            count: Int,
            bright: (Int) -> Double = { 30.0 },
            sharp: Double = 100.0,
            roll: (Int) -> Int = { 100 + it % 2 },
            motion: Double = 0.0,
            tip: Double = 0.0,
        ): List<FocusResult> = List(count) { k ->
            tracker.update(t, roll(n), bright(k), sharp, motion, tip).also { last = it; n++ }
        }

        fun seconds(s: Double, bright: Double = 30.0, sharp: Double = 100.0, roll: (Int) -> Int = { 100 + it % 2 },
                    motion: Double = 0.0, tip: Double = 0.0) =
            frames(Math.round(s * 10).toInt(), { bright }, sharp, roll, motion, tip)

        /** Dim for 4 s, then an LED-lit step: armed, sharp and careful. */
        fun approach(lit: Double = 130.0, tip: Double = 0.0): List<FocusResult> {
            seconds(4.0, bright = 30.0, tip = tip)
            return seconds(2.0, bright = lit, tip = tip)
        }
    }

    @Test fun shouldNotLockDuringWarmUp() {
        val f = Feed()
        val out = f.seconds(3.0)
        assertTrue(out.filter { it.t < 2.5 }.all { it.state == FocusState.SEARCHING })
        assertEquals(FocusState.SHARP_UNARMED, out.last().state)
    }

    @Test fun shouldHoldLockBetweenTheThresholds() {
        val f = Feed()
        f.seconds(3.0, sharp = 100.0)
        assertEquals(FocusState.SHARP_UNARMED, f.last.state)
        f.seconds(1.0, sharp = 86.0)  // rel ~0.89: below lock-on, above lock-off
        assertEquals(FocusState.SHARP_UNARMED, f.last.state)
        f.seconds(1.0, sharp = 75.0)  // rel ~0.80: unlocks
        assertEquals(FocusState.SEARCHING, f.last.state)
        f.seconds(1.0, sharp = 80.0)  // rel ~0.88: not enough to lock again
        assertEquals(FocusState.SEARCHING, f.last.state)
        f.seconds(1.0, sharp = 90.0)  // a new peak
        assertEquals(FocusState.SHARP_UNARMED, f.last.state)
    }

    @Test fun shouldKeepTheLockWhenTheMaskRescalesSharpness() {
        val rescaled = Feed().apply { seconds(3.0) }
        rescaled.tracker.rescale(0.5, null)
        rescaled.seconds(0.5, sharp = 50.0)
        assertEquals(FocusState.SHARP_UNARMED, rescaled.last.state)

        val notRescaled = Feed().apply { seconds(3.0) }
        notRescaled.seconds(0.5, sharp = 50.0)
        assertEquals(FocusState.SEARCHING, notRescaled.last.state)
    }

    @Test fun shouldArmOnABrightnessStepToAnLedLitLevel() {
        val f = Feed()
        assertFalse(f.seconds(4.0, bright = 30.0).any { it.armed })
        f.seconds(1.0, bright = 130.0)
        assertTrue(f.last.armed)
    }

    @Test fun shouldNeverArmOnASteadyBrightScene() {
        // deep depth of field: a room-lit ruler across the desk is sharp, but no approach is seen
        val out = Feed().seconds(10.0, bright = 130.0)
        assertFalse(out.any { it.armed })
        assertEquals(FocusState.SHARP_UNARMED, out.last().state)
    }

    @Test fun shouldUseTheLowerArmingFloorWithATip() {
        val bare = Feed().apply { seconds(4.0, bright = 30.0); seconds(1.0, bright = 60.0) }
        assertFalse(bare.last.armed)
        val tipped = Feed().apply { seconds(4.0, bright = 30.0, tip = 0.2); seconds(1.0, bright = 60.0, tip = 0.2) }
        assertTrue(tipped.last.armed)
        assertTrue(tipped.last.tipPresent)
    }

    @Test fun shouldDisarmOnlyAfterASustainedFall() {
        val f = Feed()
        f.approach()
        f.seconds(3.0, bright = 130.0)
        f.seconds(0.5, bright = 30.0)  // a momentary shadow
        f.seconds(3.0, bright = 130.0)
        assertTrue(f.last.armed)
        f.seconds(1.5, bright = 30.0)
        assertTrue("still armed 1.5 s into the fall", f.last.armed)
        f.seconds(1.5, bright = 30.0)
        assertFalse(f.last.armed)
    }

    @Test fun shouldSettleBeforeInZoneAndThenHold() {
        val out = Feed().approach()
        val first = out.indexOfFirst { it.state == FocusState.SETTLING || it.state == FocusState.IN_ZONE }
        assertTrue(first >= 0)
        assertEquals(FocusState.SETTLING, out[first].state)
        assertFalse(out[first].locked)
        val zone = out.indexOfFirst { it.state == FocusState.IN_ZONE }
        assertTrue("in-zone within 0.4-0.6 s of settling", zone - first in 4..6)
        assertTrue(out[zone].locked)
        assertTrue(out.drop(zone).all { it.state == FocusState.IN_ZONE })
    }

    @Test fun shouldShowCloseOnlyInZoneWithMotionAndHoldIt() {
        val f = Feed()
        f.seconds(4.0, bright = 30.0, motion = 5.0)
        assertTrue("not in zone: no CLOSE", f.frames(20, { 130.0 }, motion = 5.0).takeWhile { it.state != FocusState.IN_ZONE }.none { it.close })
        assertTrue(f.last.close)
        val after = f.seconds(1.0, bright = 130.0, motion = 0.0)
        assertTrue(after.first().close)
        assertTrue(after.filter { it.t > after.first().t + 0.55 }.none { it.close })
    }

    @Test fun shouldRestWhenTheRollIsPerfectlyStill() {
        val out = Feed().seconds(4.0, roll = { 100 })
        assertTrue(out.filter { it.t < 2.6 }.none { it.state == FocusState.RESTING })
        assertEquals(FocusState.RESTING, out.last().state)
    }

    @Test fun shouldStayInZoneWhenArmedAndSharpWhileStill() {
        val f = Feed()
        f.approach()
        assertEquals(FocusState.IN_ZONE, f.last.state)
        val still = f.seconds(5.0, bright = 130.0, roll = { 100 })
        assertTrue(still.all { it.state == FocusState.IN_ZONE })
    }

    @Test fun shouldGoCoarseWithHysteresis() {
        val f = Feed()
        f.seconds(2.0, roll = { if (it % 2 == 0) 94 else 106 })  // jitter 6
        assertEquals(FocusState.COARSE, f.last.state)
        f.seconds(2.0, roll = { if (it % 2 == 0) 96 else 104 })  // jitter 4: stays coarse
        assertEquals(FocusState.COARSE, f.last.state)
        f.seconds(2.0)                                           // jitter 0.5
        assertEquals(FocusState.SHARP_UNARMED, f.last.state)
    }

    @Test fun shouldUnwrapTheRollAcrossZero() {
        val out = Feed().seconds(3.0, roll = { if (it % 2 == 0) 359 else 1 })
        assertEquals(1.0, out.last().jitter, 0.01)  // 359/1, not 359/1 around a 179° spread
        assertEquals(FocusState.SHARP_UNARMED, out.last().state)
    }

    @Test fun shouldReportApproachingAndRecedingFromTheBrightnessTrend() {
        val up = Feed()
        up.seconds(3.0)
        up.frames(20, bright = { 30.0 + it })  // +10 luma/s at ~40: +25 %/s
        assertEquals(FocusState.APPROACHING, up.last.state)
        val down = Feed()
        down.seconds(3.0, bright = 60.0)
        down.frames(20, bright = { 60.0 - it })
        assertEquals(FocusState.RECEDING, down.last.state)
    }
}
