// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vibration while Disconnect and Quit fill (#44), as data. */
class HoldHapticsTest {
    private val disconnect = HoldPattern.of(Hold.DISCONNECT_MS)
    private val quit = HoldPattern.of(Hold.QUIT_MS)

    private fun List<Pulse>.end() = maxOf { it.atMs + it.durationMs }

    @Test fun eachButtonGetsItsOwnPattern() {
        assertEquals(HoldPattern.light(Hold.DISCONNECT_MS), disconnect)
        assertEquals(HoldPattern.heavy(Hold.QUIT_MS), quit)
        assertNotEquals(disconnect, quit)
    }

    @Test fun disconnectTicksSpeedUpAsItFills() {
        val starts = disconnect.fill.map { it.atMs }
        val gaps = starts.zipWithNext { a, b -> b - a }
        assertEquals(0L, starts.first())  // felt at once, on the press
        assertEquals(HoldPattern.TICK_GAP_START_MS, gaps.first())
        assertTrue("gaps $gaps", gaps.zipWithNext().all { (a, b) -> b < a })
        assertTrue("gaps $gaps", gaps.last() < HoldPattern.TICK_GAP_START_MS / 2)
        assertTrue(disconnect.fill.all { it.durationMs == HoldPattern.TICK_MS })
        val amps = disconnect.fill.map { it.amplitude }
        assertTrue("amplitudes $amps", amps.zipWithNext().all { (a, b) -> b >= a })
    }

    @Test fun quitBeatsTwiceAboutOnceASecondGettingStronger() {
        val pairs = quit.fill.chunked(2)
        assertEquals(5, pairs.size)
        assertEquals(listOf(0L, 1000L, 2000L, 3000L, 4000L), pairs.map { it[0].atMs })
        for (p in pairs) {
            assertEquals(2, p.size)
            assertEquals(p[0].amplitude, p[1].amplitude)
            assertEquals(HoldPattern.BEAT_GAP_MS, p[1].atMs - (p[0].atMs + p[0].durationMs))
        }
        val amps = pairs.map { it[0].amplitude }
        assertTrue("amplitudes $amps", amps.zipWithNext().all { (a, b) -> b > a })
    }

    @Test fun quitFeelsHeavierThanDisconnect() {
        assertTrue(quit.fill.minOf { it.amplitude } > disconnect.fill.maxOf { it.amplitude })
        assertTrue(quit.fill.minOf { it.durationMs } > disconnect.fill.maxOf { it.durationMs })
    }

    @Test fun theFillEndsBeforeTheHoldCompletes() {
        for (ms in listOf(Hold.DISCONNECT_MS, Hold.QUIT_MS, 1_000L, 3_000L, 4_000L, 8_000L)) {
            val p = HoldPattern.of(ms)
            assertTrue("$ms ms", p.fill.isNotEmpty())
            assertTrue("$ms ms: fill ends at ${p.fill.end()}", p.fill.end() <= ms)
            assertTrue("$ms ms: out of order", p.fill.zipWithNext().all { (a, b) -> b.atMs >= a.atMs + a.durationMs })
        }
    }

    @Test fun theCompletionIsItsOwnPulseAndDiffersPerButton() {
        for (p in listOf(disconnect, quit)) {
            assertEquals(1, p.done.size)
            assertEquals(0L, p.done[0].atMs)  // played at completion, not part of the fill
            assertEquals(HoldPattern.MAX_AMP, p.done[0].amplitude)
            assertTrue(p.done[0].durationMs > p.fill.maxOf { it.durationMs })
        }
        assertTrue(quit.done[0].durationMs >= 5 * disconnect.done[0].durationMs)  // a long buzz vs one pulse
    }

    @Test fun aLongerHoldStretchesThePattern() {
        // derived from the duration: a 4 s light hold ticks longer, a 10 s heavy one beats more
        assertTrue(HoldPattern.light(4_000).fill.size > disconnect.fill.size)
        assertEquals(10, HoldPattern.heavy(10_000).fill.size / 2)
    }

    @Test fun pulsesBecomeAnOffOnWaveform() {
        val w = Waveform.of(listOf(Pulse(0, 12, 60), Pulse(320, 12, 80), Pulse(600, 40, 300)))
        assertArrayEquals(longArrayOf(0, 12, 308, 12, 268, 40), w.timings)
        assertArrayEquals(intArrayOf(0, 60, 0, 80, 0, 255), w.amplitudes)  // capped at 255
    }

    @Test fun everyPatternIsAValidWaveform() {
        for (p in listOf(disconnect, quit)) for (pulses in listOf(p.fill, p.done)) {
            val w = Waveform.of(pulses)
            assertEquals(w.timings.size, w.amplitudes.size)
            assertTrue(w.timings.all { it >= 0 })
            assertEquals(pulses.end(), w.timings.sum())
            assertTrue(w.amplitudes.filterIndexed { i, _ -> i % 2 == 0 }.all { it == 0 })
            assertTrue(w.amplitudes.filterIndexed { i, _ -> i % 2 == 1 }.all { it in 1..255 })
        }
    }

    @Test fun overlappingPulsesAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { Waveform.of(listOf(Pulse(0, 50, 100), Pulse(40, 10, 100))) }
    }
}
