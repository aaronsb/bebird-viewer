// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import com.bockelie.bebird.settings.MemoryKeyValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The UI wiring of #27 at the integration level: the frame-path hook ([ProximityFrames]) and
 * the settings state ([ProximityOptions]), with a counting estimator as in ProximityGateTest.
 */
class ProximityWiringTest {
    private class Counting {
        var created = 0
        var updates = 0
        fun gate() = ProximityGate {
            created++
            FrameEstimator { _, t, _ -> updates++; FocusTracker().update(t, 100, 30.0, 1.0, 0.0, 0.0) }
        }
    }

    private val results = mutableListOf<FocusResult?>()
    private val queued = ArrayDeque<Runnable>()

    // --- the frame-path hook ---

    @Test fun disabledTheHookNeverTouchesTheGate() {
        val c = Counting()
        val gate = c.gate()  // never enabled
        var fills = 0
        val hook = ProximityFrames(gate, { it.run() }) { results += it }
        repeat(50) { assertFalse(hook.offer(it / 10.0, 0) { fills++ }) }
        assertEquals(0, c.created)
        assertEquals(0, c.updates)
        assertEquals(0, fills)
        assertTrue(results.isEmpty())  // no work queued, nothing published
    }

    @Test fun enabledEveryFrameRunsOnTheWorker() {
        val c = Counting()
        val gate = c.gate().apply { setEnabled(true) }
        var fills = 0
        val hook = ProximityFrames(gate, { queued += it }) { results += it }
        assertTrue(hook.offer(0.0, 0) { fills++ })
        assertEquals(0, c.updates)  // not on the caller's thread: only queued
        queued.removeFirst().run()
        assertEquals(1, fills)
        assertEquals(1, c.updates)
        assertNotNull(results.single())
    }

    @Test fun framesAreDroppedWhileTheEstimatorIsBusy() {
        val c = Counting()
        val gate = c.gate().apply { setEnabled(true) }
        val hook = ProximityFrames(gate, { queued += it }) { results += it }
        assertTrue(hook.offer(0.0, 0) {})
        assertFalse(hook.offer(0.1, 0) {})  // the first is still pending: dropped, decode not held up
        assertFalse(hook.offer(0.2, 0) {})
        assertEquals(2, hook.dropped)
        assertEquals(1, queued.size)
        queued.removeFirst().run()
        assertTrue(hook.offer(0.3, 0) {})  // free again
    }

    @Test fun turningItOffStopsTheHook() {
        val c = Counting()
        val gate = c.gate().apply { setEnabled(true) }
        val hook = ProximityFrames(gate, { it.run() }) { results += it }
        hook.offer(0.0, 0) {}
        gate.setEnabled(false)
        repeat(10) { hook.offer(1.0 + it, 0) {} }
        assertEquals(1, c.updates)
    }

    @Test fun aThrowingEstimatorCostsOneResultNotTheApp() {
        var created = 0
        var calls = 0
        val gate = ProximityGate {
            created++
            FrameEstimator { _, t, _ ->
                calls++
                if (calls == 2) throw IllegalStateException("bad frame")
                FocusTracker().update(t, 100, 30.0, 1.0, 0.0, 0.0)
            }
        }.apply { setEnabled(true) }
        val errors = mutableListOf<Exception>()
        val hook = ProximityFrames(gate, { it.run() }, onError = { errors += it }) { results += it }
        hook.offer(0.0, 0) {}
        hook.offer(0.1, 0) {}  // throws inside the estimator
        hook.offer(0.2, 0) {}
        assertEquals(1, errors.size)
        assertTrue(errors.single() is IllegalStateException)
        assertEquals(1, hook.errors)
        assertEquals(listOf(true, false, true), results.map { it != null })  // null for the bad frame
        assertEquals(2, created)  // a fresh estimator after the error
        assertTrue(gate.enabled)
        assertTrue(hook.offer(0.3, 0) {})  // and the hook isn't stuck busy
    }

    @Test fun anErrorAfterSwitchingOffDoesntSwitchItBackOn() {
        val gate = ProximityGate { FrameEstimator { _, _, _ -> throw IllegalStateException() } }.apply { setEnabled(true) }
        val pending = ArrayDeque<Runnable>()
        val hook = ProximityFrames(gate, { pending += it }) { results += it }
        hook.offer(0.0, 0) {}
        gate.setEnabled(false)
        pending.removeFirst().run()  // the estimator isn't there any more: nothing runs, nothing is re-enabled
        assertFalse(gate.enabled)
    }

    @Test fun restartStartsTheEstimatorOverOnlyIfItRuns() {
        val c = Counting()
        val gate = c.gate()
        val hook = ProximityFrames(gate, { it.run() }) { results += it }
        hook.restart()
        assertFalse(gate.enabled)
        assertEquals(0, c.created)
        gate.setEnabled(true)
        hook.restart()  // the stream ended: fresh estimator, still on
        assertTrue(gate.enabled)
        assertEquals(2, c.created)
    }

    @Test fun onlyRawSoftwareArgbFramesAreAccepted() {
        assertTrue(ProximityFrames.accepts(480, 480, softwareArgb8888 = true))
        assertFalse(ProximityFrames.accepts(480, 480, softwareArgb8888 = false))  // HARDWARE, RGB_565
        assertFalse(ProximityFrames.accepts(960, 960, softwareArgb8888 = true))   // not the raw frame
        assertFalse(ProximityFrames.accepts(480, 528, softwareArgb8888 = true))   // a composited one
    }

    // --- settings state ---

    @Test fun optionsStartFromTheStoredSettings() {
        val gate = Counting().gate()
        val o = ProximityOptions(ProximitySettings(MemoryKeyValue()), gate)
        assertEquals(ProximityOptions.State(enabled = true, style = ScaleStyle.RING, close = true), o.state.value)
        assertTrue(o.state.value.subSettingsEnabled)
        assertTrue(gate.enabled)  // the gate follows the stored master switch
    }

    @Test fun masterOffGreysTheSubSettingsButKeepsThem() {
        val kv = MemoryKeyValue()
        val gate = Counting().gate()
        val o = ProximityOptions(ProximitySettings(kv), gate)
        o.setStyle(ScaleStyle.BAR)
        o.setClose(false)
        o.setEnabled(false)
        assertFalse(gate.enabled)
        assertFalse(o.state.value.subSettingsEnabled)
        assertEquals(ScaleStyle.BAR, o.state.value.style)
        assertFalse(o.state.value.close)
        // persisted: a new session of the app sees the same
        val again = ProximityOptions(ProximitySettings(kv), Counting().gate())
        assertEquals(ProximityOptions.State(enabled = false, style = ScaleStyle.BAR, close = false), again.state.value)
        o.setEnabled(true)
        assertTrue(gate.enabled)
        assertEquals(ScaleStyle.BAR, o.state.value.style)
    }

    @Test fun settingsPersistUnderTheirOwnKeys() {
        val kv = MemoryKeyValue()
        ProximitySettings(kv).apply { enabled = false; scaleStyle = ScaleStyle.BOWTIE; closeIndicator = false }
        assertFalse(kv.getBoolean("proximity", true))
        assertEquals("BOWTIE", kv.getString("proximity_scale", ""))
        assertFalse(kv.getBoolean("proximity_close", true))
        kv.putString("proximity_scale", "SPIRAL")  // unknown: back to the default
        assertEquals(ScaleStyle.RING, ProximitySettings(kv).scaleStyle)
    }

    @Test fun offMeansNoOverlay() {
        assertTrue(ScaleOverlay.forFrame(null, ScaleStyle.RING, true).isEmpty())
    }
}
