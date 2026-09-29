// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import com.bockelie.bebird.settings.MemoryKeyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ProximityGateTest {
    /** Counts estimator creations and frames; returns a canned result. */
    private class Counting {
        var created = 0
        var frames = 0
        val gate = ProximityGate {
            created++
            FrameEstimator { _, t, _ -> frames++; FocusTracker().update(t, 100, 30.0, 1.0, 0.0, 0.0) }
        }
    }

    @Test fun shouldNotRunAnythingWhileDisabled() {
        val c = Counting()
        var lumaCalls = 0
        repeat(50) { k -> assertNull(c.gate.onFrame(k / 10.0, 100) { lumaCalls++ }) }
        assertFalse(c.gate.enabled)
        assertEquals(0, c.created)
        assertEquals(0, c.frames)
        assertEquals(0, lumaCalls)
    }

    @Test fun shouldRunEveryFrameWhileEnabled() {
        val c = Counting()
        c.gate.setEnabled(true)
        var lumaCalls = 0
        repeat(5) { k -> assertNotNull(c.gate.onFrame(k / 10.0, 100) { lumaCalls++ }) }
        assertEquals(1, c.created)
        assertEquals(5, c.frames)
        assertEquals(5, lumaCalls)
    }

    @Test fun shouldStopOnDisableAndStartFreshOnReEnable() {
        val c = Counting()
        c.gate.setEnabled(true)
        c.gate.setEnabled(true)  // already on: keeps the same estimator
        c.gate.onFrame(0.0, 100) {}
        c.gate.setEnabled(false)
        assertNull(c.gate.onFrame(0.1, 100) { error("luma requested while disabled") })
        assertEquals(1, c.frames)
        c.gate.setEnabled(true)
        assertEquals(2, c.created)
    }

    @Test fun reEnablingForgetsTheLearnedTipMask() {
        val gate = ProximityGate()
        gate.setEnabled(true)
        val frames = SyntheticScope.approach(tip = true, frames = 60).toList()
        val learned = frames.map { f -> gate.onFrame(f.t, f.roll) { f.frame.copyInto(it) }!! }
        assertTrue(learned.last().tipPresent)
        gate.setEnabled(false)
        gate.setEnabled(true)
        val f = frames.last()
        val fresh = gate.onFrame(f.t + 0.1, f.roll) { f.frame.copyInto(it) }!!
        assertEquals(0.0, fresh.tipFraction, 0.0)
        assertEquals(FocusState.SEARCHING, fresh.state)  // warm-up again: no lock
    }

    @Test fun shouldNotAllocateWhileDisabled() {
        // java.lang.management isn't on the Android compile classpath, but the test JVM has it
        val bean = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val bytes = Class.forName("com.sun.management.ThreadMXBean").getMethod("getThreadAllocatedBytes", Long::class.javaPrimitiveType)
        val id = Thread.currentThread().id
        fun allocated() = bytes.invoke(bean, id) as Long
        val gate = ProximityGate()
        var n = 0
        fun burst() { repeat(100_000) { k -> if (gate.onFrame(k / 10.0, 100) { n++ } != null) n++ } }
        burst()  // warm up
        val before = allocated()
        burst()
        val used = allocated() - before
        assertEquals(0, n)
        assertTrue("allocated $used bytes over 100 000 disabled frames", used < 4096)
    }

    @Test fun overlayIsEmptyWithEstimationOff() {
        assertTrue(ScaleOverlay.forFrame(null, ScaleStyle.RING, showClose = true).isEmpty())
        val r = FocusTracker().update(0.0, 100, 30.0, 1.0, 0.0, 0.0)
        assertEquals(ScaleOverlay.shapes(ScaleStyle.RING, false, false), ScaleOverlay.forFrame(r, ScaleStyle.RING, true))
    }

    @Test fun settingsDefaultOnAndSurviveTheMasterToggle() {
        val kv = MemoryKeyValue()
        val s = ProximitySettings(kv)
        assertTrue(s.enabled)
        assertEquals(ScaleStyle.RING, s.scaleStyle)
        assertTrue(s.closeIndicator)
        s.scaleStyle = ScaleStyle.BAR
        s.closeIndicator = false
        s.enabled = false
        s.enabled = true
        val again = ProximitySettings(kv)
        assertEquals(ScaleStyle.BAR, again.scaleStyle)
        assertFalse(again.closeIndicator)
        kv.putString("proximity_scale", "SPIRAL")
        assertEquals(ScaleStyle.RING, again.scaleStyle)
    }
}
