// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityGateTest {
    /** Counts estimator constructions and frames; returns a canned result. */
    private class Counting {
        var created = 0
        var frames = 0
        val gate = ProximityGate {
            created++
            FrameEstimator { _, t, _ -> frames++; FocusTracker().update(t, 100, 30.0, 1.0, 0.0, 0.0) }
        }
    }

    @Test fun shouldNeitherFillNorBuildNorRunWhileDisabled() {
        val c = Counting()
        var fills = 0
        repeat(50) { k -> assertNull(c.gate.onFrame(k / 10.0, 100) { fills++ }) }
        assertFalse(c.gate.enabled)
        assertEquals(0, c.created)
        assertEquals(0, c.frames)
        assertEquals(0, fills)
    }

    @Test fun shouldFillAndRunEveryFrameWhileEnabled() {
        val c = Counting()
        c.gate.setEnabled(true)
        c.gate.setEnabled(true)  // already on: the same estimator
        var fills = 0
        repeat(5) { k -> assertNotNull(c.gate.onFrame(k / 10.0, 100) { fills++ }) }
        assertEquals(1, c.created)
        assertEquals(5, c.frames)
        assertEquals(5, fills)
    }

    @Test fun shouldReleaseTheEstimatorAndBuffersOnDisable() {
        val c = Counting()
        c.gate.setEnabled(true)
        var first: LumaBuffers? = null
        c.gate.onFrame(0.0, 100) { first = it }
        c.gate.onFrame(0.1, 100) { assertSame(first, it) }  // reused between frames
        c.gate.setEnabled(false)
        assertNull(c.gate.active)
        assertNull(c.gate.onFrame(0.2, 100) { error("asked for luma while disabled") })
        assertEquals(2, c.frames)
        c.gate.setEnabled(true)
        assertEquals(2, c.created)
        c.gate.onFrame(0.3, 100) { assertNotSame(first, it) }
    }

    @Test fun enablingMidStreamStartsFreshAfterALearnedRun() {
        val gate = ProximityGate()
        gate.setEnabled(true)
        val frames = SyntheticScope.approach(tip = true).toList()
        // a full run: tip learned, armed and in-zone by the end
        val learned = frames.map { f -> gate.onFrame(f.t, f.roll) { f.frame.copyInto(it.luma) }!! }
        assertTrue(learned.last().tipPresent)
        assertTrue(learned.last().armed && learned.last().locked)

        gate.setEnabled(false)
        gate.setEnabled(true)
        // the same lit, sharp, careful scene again, mid-stream: warm-up, no tip, not armed
        val again = frames.drop(150).take(30).mapIndexed { k, f ->
            gate.onFrame(40.0 + k / 10.0, f.roll) { f.frame.copyInto(it.luma) }!!
        }
        assertTrue(again.all { it.tipFraction == 0.0 && !it.armed })
        assertTrue("no lock during warm-up", again.filter { it.t < 42.5 }.none { it.locked })
    }

    @Test fun overlayIsEmptyWithEstimationOff() {
        assertTrue(ScaleOverlay.forFrame(null, ScaleStyle.RING, showClose = true).isEmpty())
        val r = FocusTracker().update(0.0, 100, 30.0, 1.0, 0.0, 0.0)
        assertEquals(ScaleOverlay.shapes(ScaleStyle.RING, false, false), ScaleOverlay.forFrame(r, ScaleStyle.RING, true))
    }
}
