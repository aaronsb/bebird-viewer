// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.Settings
import android.util.Log
import kotlin.math.ceil
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** On for [durationMs] at [amplitude] (1-255), starting [atMs] into the pattern. */
data class HoldPulse(val atMs: Long, val durationMs: Long, val amplitude: Int)

/** How a hold button feels (#44): each button says which it is. */
enum class HoldFeel {
    /** Light ticks speeding up, one firm pulse on completion (Disconnect). */
    LIGHT,
    /** A double beat each second, stronger each time, a long buzz on completion (Quit). */
    HEAVY,
}

/**
 * What a hold feels like (#44): [fill] plays from the press while the button fills, [done] once
 * the hold completes. A finger covers the button, so this is how the fill is felt.
 */
data class HoldPattern(val fill: List<HoldPulse>, val done: List<HoldPulse>) {
    companion object {
        /** The pattern for a hold of [durationMs] that feels [feel]. */
        fun of(feel: HoldFeel, durationMs: Long): HoldPattern = when (feel) {
            HoldFeel.LIGHT -> light(durationMs)
            HoldFeel.HEAVY -> heavy(durationMs)
        }

        /**
         * Ticks whose spacing shrinks evenly to exactly [TICK_GAP_END_MS], starting from about
         * [TICK_GAP_START_MS] (as close as fits), the last one just before the completion, and
         * getting a little stronger; one firm pulse on completion.
         */
        fun light(durationMs: Long): HoldPattern {
            val last = durationMs - END_GUARD_MS - TICK_MS  // the last tick's start
            if (last <= 0) return HoldPattern(listOf(HoldPulse(0, TICK_MS, TICK_AMP_END)), listOf(HoldPulse(0, FIRM_MS, MAX_AMP)))
            // As many gaps as fit when they run from TICK_GAP_START_MS down to TICK_GAP_END_MS;
            // the first then shrinks a little so that together they end at `last`.
            val n = maxOf(1, ceil(2.0 * last / (TICK_GAP_START_MS + TICK_GAP_END_MS)).toInt())
            val first = if (n == 1) last.toDouble() else 2.0 * last / n - TICK_GAP_END_MS
            val gaps = (0 until n).map { i ->
                if (n == 1) last else (first + (TICK_GAP_END_MS - first) * i / (n - 1)).roundToLong()
            }.toMutableList()
            gaps[0] += last - gaps.sum()  // rounding: the largest gap takes it, the last stays exact
            var t = 0L
            val ticks = mutableListOf(tick(0, durationMs))
            for (g in gaps) {
                t += g
                ticks += tick(t, durationMs)
            }
            return HoldPattern(ticks, listOf(HoldPulse(0, FIRM_MS, MAX_AMP)))
        }

        private fun tick(at: Long, durationMs: Long) =
            HoldPulse(at, TICK_MS, lerp(TICK_AMP_START, TICK_AMP_END, at.toFloat() / durationMs))

        /** A double pulse about once a second, stronger each time; one long, strong buzz on completion. */
        fun heavy(durationMs: Long): HoldPattern {
            val beats = maxOf(1, (durationMs.toFloat() / BEAT_PERIOD_MS).roundToInt())
            val period = durationMs / beats
            val pulses = (0 until beats).flatMap { i ->
                val amp = lerp(BEAT_AMP_START, BEAT_AMP_END, if (beats == 1) 1f else i.toFloat() / (beats - 1))
                val at = i * period
                listOf(HoldPulse(at, BEAT_PULSE_MS, amp), HoldPulse(at + BEAT_PULSE_MS + BEAT_GAP_MS, BEAT_PULSE_MS, amp))
            }.filter { it.atMs + it.durationMs <= durationMs - END_GUARD_MS }
            return HoldPattern(pulses, listOf(HoldPulse(0, BUZZ_MS, MAX_AMP)))
        }

        /**
         * [pulses] for a vibrator without amplitude control, which only switches on and off:
         * strength becomes length instead, from [ON_OFF_MIN_MS] (weakest) to [ON_OFF_MAX_MS]
         * (strongest), never shorter than the pulse was. A short tick wouldn't spin up a plain
         * motor at all. Never runs into the next pulse.
         */
        fun onOff(pulses: List<HoldPulse>): List<HoldPulse> = pulses.mapIndexed { i, p ->
            val wanted = maxOf(p.durationMs, ON_OFF_MIN_MS + (ON_OFF_MAX_MS - ON_OFF_MIN_MS) * p.amplitude / MAX_AMP)
            val room = pulses.getOrNull(i + 1)?.let { it.atMs - p.atMs - ON_OFF_MIN_GAP_MS } ?: wanted
            p.copy(durationMs = maxOf(p.durationMs, minOf(wanted, room)), amplitude = MAX_AMP)
        }

        const val MAX_AMP = 255
        private const val END_GUARD_MS = 50L  // nothing of the fill runs into the completion

        const val TICK_MS = 12L
        const val TICK_GAP_START_MS = 320L
        const val TICK_GAP_END_MS = 70L
        private const val TICK_AMP_START = 60
        private const val TICK_AMP_END = 150
        const val FIRM_MS = 60L

        const val BEAT_PERIOD_MS = 1000L
        const val BEAT_PULSE_MS = 45L
        const val BEAT_GAP_MS = 90L
        private const val BEAT_AMP_START = 170  // above the strongest light tick
        private const val BEAT_AMP_END = 240
        const val BUZZ_MS = 450L

        const val ON_OFF_MIN_MS = 25L
        const val ON_OFF_MAX_MS = 45L
        const val ON_OFF_MIN_GAP_MS = 20L

        private fun lerp(a: Int, b: Int, p: Float) = (a + (b - a) * p).roundToInt()
    }
}

/**
 * Pulses as a one-shot waveform for VibrationEffect.createWaveform: alternating off and on
 * segments from 0, amplitude 0 while off. Pulses must be in order and not overlap.
 */
class HoldWaveform(val timings: LongArray, val amplitudes: IntArray) {
    companion object {
        fun of(pulses: List<HoldPulse>): HoldWaveform {
            val timings = mutableListOf<Long>()
            val amplitudes = mutableListOf<Int>()
            var t = 0L
            for (p in pulses) {
                require(p.atMs >= t) { "pulses overlap or are out of order at ${p.atMs} ms" }
                timings += p.atMs - t
                amplitudes += 0
                timings += p.durationMs
                amplitudes += p.amplitude.coerceIn(1, HoldPattern.MAX_AMP)
                t = p.atMs + p.durationMs
            }
            return HoldWaveform(timings.toLongArray(), amplitudes.toIntArray())
        }
    }
}

/**
 * Whose hold it is: one at a time across the app, so a second finger on the other hold button
 * can neither start a hold nor silence or replace the first one's vibration (#44). The first
 * hold keeps its turn until it ends. Main thread only.
 */
class HoldTurn {
    private var holder: Any? = null

    /** [who] takes the turn; false if another hold has it. Taking it again is fine. */
    fun take(who: Any): Boolean {
        if (holder != null && holder !== who) return false
        holder = who
        return true
    }

    /** [who] is done; nothing if it didn't have the turn. */
    fun give(who: Any) {
        if (holder === who) holder = null
    }

    fun isHeldBy(who: Any): Boolean = holder === who

    companion object {
        /** The app's one turn, shared by every hold button. */
        val shared = HoldTurn()
    }
}

/**
 * Plays hold vibrations on the device vibrator: the thin Android side of #44. Nothing if the
 * phone has no vibrator, or touch feedback is off in the system settings. On API 33+ it counts
 * as touch feedback, so the system's touch-vibration strength applies. A vibrator without
 * amplitude control gets the pattern as lengths instead ([HoldPattern.onOff]). Errors are
 * logged, never thrown.
 */
class HoldVibrator(context: Context) {
    private val resolver = context.contentResolver
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }

    fun play(pulses: List<HoldPulse>) {
        try {
            val v = vibrator?.takeIf { it.hasVibrator() } ?: return
            if (pulses.isEmpty() || !touchFeedbackOn()) return
            val w = HoldWaveform.of(if (v.hasAmplitudeControl()) pulses else HoldPattern.onOff(pulses))
            val effect = VibrationEffect.createWaveform(w.timings, w.amplitudes, -1)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                v.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_TOUCH))
            } else {
                @Suppress("DEPRECATION")  // the AudioAttributes overload: VibrationAttributes is API 33+
                v.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION).build())
            }
        } catch (e: RuntimeException) {  // a bad pattern, or the service refused
            Log.w(TAG, "vibration failed", e)
        }
    }

    /** Stop at once: let go early, or the button went away. */
    fun cancel() {
        try {
            vibrator?.cancel()
        } catch (e: RuntimeException) {
            Log.w(TAG, "vibration cancel failed", e)
        }
    }

    private fun touchFeedbackOn(): Boolean =
        Settings.System.getInt(resolver, Settings.System.HAPTIC_FEEDBACK_ENABLED, 1) != 0

    private companion object {
        const val TAG = "BebirdSpike"
    }
}
