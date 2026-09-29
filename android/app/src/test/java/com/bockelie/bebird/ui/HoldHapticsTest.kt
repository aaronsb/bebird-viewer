// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The vibration while Disconnect and Quit fill (#44), as data. */
class HoldHapticsTest {
    private val disconnect = HoldPattern.of(HoldFeel.LIGHT, Hold.DISCONNECT_MS)
    private val quit = HoldPattern.of(HoldFeel.HEAVY, Hold.QUIT_MS)

    private fun List<HoldPulse>.end() = maxOf { it.atMs + it.durationMs }

    @Test fun eachButtonSaysHowItFeels() {
        assertEquals(HoldPattern.light(Hold.DISCONNECT_MS), disconnect)
        assertEquals(HoldPattern.heavy(Hold.QUIT_MS), quit)
        assertNotEquals(disconnect, quit)
        // the feel, not the length, decides: a longer Disconnect would still tick
        assertEquals(HoldPattern.light(5_000), HoldPattern.of(HoldFeel.LIGHT, 5_000))
    }

    @Test fun disconnectTicksSpeedUpAsItFills() {
        val starts = disconnect.fill.map { it.atMs }
        val gaps = starts.zipWithNext { a, b -> b - a }
        assertEquals(0L, starts.first())  // felt at once, on the press
        // from about TICK_GAP_START_MS (as close as fits) down to exactly TICK_GAP_END_MS
        assertTrue("gaps $gaps", gaps.first() in HoldPattern.TICK_GAP_START_MS - 20..HoldPattern.TICK_GAP_START_MS)
        assertEquals(HoldPattern.TICK_GAP_END_MS, gaps.last())
        assertTrue("gaps $gaps", gaps.zipWithNext().all { (a, b) -> b < a })
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
        for (feel in HoldFeel.entries) for (ms in listOf(Hold.DISCONNECT_MS, Hold.QUIT_MS, 1_000L, 3_000L, 4_000L, 8_000L)) {
            val p = HoldPattern.of(feel, ms)
            assertTrue("$feel $ms ms", p.fill.isNotEmpty())
            assertTrue("$feel $ms ms: fill ends at ${p.fill.end()}", p.fill.end() <= ms)
            assertTrue("$feel $ms ms: out of order", p.fill.zipWithNext().all { (a, b) -> b.atMs >= a.atMs + a.durationMs })
            if (feel == HoldFeel.LIGHT) {
                val gaps = p.fill.zipWithNext { a, b -> b.atMs - a.atMs }
                assertEquals("$ms ms", HoldPattern.TICK_GAP_END_MS, gaps.last())
                assertTrue("$ms ms: gaps $gaps", gaps.zipWithNext().all { (a, b) -> b < a })
            }
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
        val w = HoldWaveform.of(listOf(HoldPulse(0, 12, 60), HoldPulse(320, 12, 80), HoldPulse(600, 40, 300)))
        assertArrayEquals(longArrayOf(0, 12, 308, 12, 268, 40), w.timings)
        assertArrayEquals(intArrayOf(0, 60, 0, 80, 0, 255), w.amplitudes)  // capped at 255
    }

    @Test fun everyPatternIsAValidWaveform() {
        for (p in listOf(disconnect, quit)) for (pulses in listOf(p.fill, p.done)) {
            val w = HoldWaveform.of(pulses)
            assertEquals(w.timings.size, w.amplitudes.size)
            assertTrue(w.timings.all { it >= 0 })
            assertEquals(pulses.end(), w.timings.sum())
            assertTrue(w.amplitudes.filterIndexed { i, _ -> i % 2 == 0 }.all { it == 0 })
            assertTrue(w.amplitudes.filterIndexed { i, _ -> i % 2 == 1 }.all { it in 1..255 })
        }
    }

    @Test fun withoutAmplitudeControlStrengthBecomesLength() {
        val ticks = HoldPattern.onOff(disconnect.fill)
        assertTrue(ticks.all { it.amplitude == HoldPattern.MAX_AMP })
        assertTrue("ticks ${ticks.map { it.durationMs }}", ticks.all { it.durationMs in HoldPattern.ON_OFF_MIN_MS..HoldPattern.ON_OFF_MAX_MS })
        assertTrue(ticks.zipWithNext().all { (a, b) -> b.durationMs >= a.durationMs })  // stronger is longer
        assertEquals(disconnect.fill.map { it.atMs }, ticks.map { it.atMs })  // the same rhythm
        for (p in listOf(disconnect, quit)) for (pulses in listOf(p.fill, p.done)) {
            val on = HoldPattern.onOff(pulses)
            assertTrue(on.zip(pulses).all { (a, b) -> a.durationMs >= b.durationMs })  // never shorter
            HoldWaveform.of(on)  // still in order, no overlap
            assertTrue(on.zipWithNext().all { (a, b) -> b.atMs - (a.atMs + a.durationMs) >= HoldPattern.ON_OFF_MIN_GAP_MS })
        }
    }

    private class Button : HoldTurn.Holder {
        override var isHolding = false
    }

    @Test fun oneHoldAtATime() {
        // two fingers on the top row: the second hold button can't start, stop or replace anything
        val turn = HoldTurn()
        val quitButton = Button()
        val disconnectButton = Button()
        assertTrue(turn.take(quitButton))
        quitButton.isHolding = true
        assertFalse(turn.take(disconnectButton))
        turn.give(disconnectButton)  // its release: not its turn to give
        assertTrue(turn.isHeldBy(quitButton))
        assertTrue(turn.take(quitButton))  // a key press on the same button: still its turn
        turn.give(quitButton)
        quitButton.isHolding = false
        assertTrue(turn.take(disconnectButton))
    }

    @Test fun aTurnNeverGivenBackIsReclaimed() {
        // a holder that stopped holding without giving the turn back can't lock the other buttons
        val turn = HoldTurn()
        val stuck = Button().apply { isHolding = true }
        val quitButton = Button()
        assertTrue(turn.take(stuck))
        assertFalse(turn.take(quitButton))
        stuck.isHolding = false  // its hold ended, but give() never came
        assertTrue(turn.take(quitButton))
        assertTrue(turn.isHeldBy(quitButton))
        turn.give(stuck)  // late: changes nothing
        assertTrue(turn.isHeldBy(quitButton))
    }

    @Test fun everyLengthOfHoldGivesAValidPattern() {
        for (feel in HoldFeel.entries) for (ms in 1L..10_000L) {
            val p = HoldPattern.of(feel, ms)
            for (pulses in listOf(p.fill, p.done, HoldPattern.onOff(p.fill), HoldPattern.onOff(p.done))) {
                val w = HoldWaveform.of(pulses)  // throws on overlap or disorder
                assertEquals("$feel $ms ms", w.timings.size, w.amplitudes.size)
                assertTrue("$feel $ms ms", w.timings.all { it >= 0 } && w.amplitudes.all { it in 0..255 })
            }
            if (p.fill.isNotEmpty()) assertTrue("$feel $ms ms: fill ends at ${p.fill.end()}", p.fill.end() <= ms)
            assertEquals("$feel $ms ms", 1, p.done.size)
        }
    }

    @Test fun aVeryShortLightHoldTicksOnceOrNotAtAll() {
        assertEquals(listOf(0L), HoldPattern.light(140).fill.map { it.atMs })
        assertTrue(HoldPattern.light(40).fill.isEmpty())
    }

    @Test fun overlappingPulsesAreRefused() {
        assertThrows(IllegalArgumentException::class.java) { HoldWaveform.of(listOf(HoldPulse(0, 50, 100), HoldPulse(40, 10, 100))) }
    }
}
