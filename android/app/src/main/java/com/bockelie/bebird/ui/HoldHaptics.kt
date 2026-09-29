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
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** On for [durationMs] at [amplitude] (1-255), starting [atMs] into the pattern. */
data class Pulse(val atMs: Long, val durationMs: Long, val amplitude: Int)

/**
 * What a hold feels like (#44): [fill] plays from the press while the button fills, [done] once
 * the hold completes. A finger covers the button, so this is how the fill is felt.
 */
data class HoldPattern(val fill: List<Pulse>, val done: List<Pulse>) {
    companion object {
        /**
         * The pattern for a hold of [durationMs]: light ticks that speed up for a short hold
         * (Disconnect), a heavier double beat that grows each second for a long, consequential
         * one (Quit, from [HEAVY_FROM_MS]).
         */
        fun of(durationMs: Long): HoldPattern = if (durationMs >= HEAVY_FROM_MS) heavy(durationMs) else light(durationMs)

        /**
         * Ticks whose spacing shrinks from [TICK_GAP_START_MS] to [TICK_GAP_END_MS] as the fill
         * grows, getting a little stronger; one firm pulse on completion.
         */
        fun light(durationMs: Long): HoldPattern {
            val ticks = mutableListOf<Pulse>()
            var t = 0L
            while (t + TICK_MS <= durationMs - END_GUARD_MS) {
                val p = t.toFloat() / durationMs
                ticks += Pulse(t, TICK_MS, lerp(TICK_AMP_START, TICK_AMP_END, p))
                t += lerp(TICK_GAP_START_MS.toFloat(), TICK_GAP_END_MS.toFloat(), p).roundToLong()
            }
            return HoldPattern(ticks, listOf(Pulse(0, FIRM_MS, MAX_AMP)))
        }

        /** A double pulse about once a second, stronger each time; one long, strong buzz on completion. */
        fun heavy(durationMs: Long): HoldPattern {
            val beats = maxOf(1, (durationMs.toFloat() / BEAT_PERIOD_MS).roundToInt())
            val period = durationMs / beats
            val pulses = (0 until beats).flatMap { i ->
                val amp = lerp(BEAT_AMP_START, BEAT_AMP_END, if (beats == 1) 1f else i.toFloat() / (beats - 1))
                val at = i * period
                listOf(Pulse(at, BEAT_PULSE_MS, amp), Pulse(at + BEAT_PULSE_MS + BEAT_GAP_MS, BEAT_PULSE_MS, amp))
            }.filter { it.atMs + it.durationMs <= durationMs - END_GUARD_MS }
            return HoldPattern(pulses, listOf(Pulse(0, BUZZ_MS, MAX_AMP)))
        }

        const val HEAVY_FROM_MS = 4000L
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
        private const val BEAT_AMP_START = 170  // above the strongest Disconnect tick
        private const val BEAT_AMP_END = 240
        const val BUZZ_MS = 450L

        private fun lerp(a: Int, b: Int, p: Float) = (a + (b - a) * p).roundToInt()
        private fun lerp(a: Float, b: Float, p: Float) = a + (b - a) * p
    }
}

/**
 * Pulses as a one-shot waveform for VibrationEffect.createWaveform: alternating off and on
 * segments from 0, amplitude 0 while off. Pulses must be in order and not overlap.
 */
class Waveform(val timings: LongArray, val amplitudes: IntArray) {
    companion object {
        fun of(pulses: List<Pulse>): Waveform {
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
            return Waveform(timings.toLongArray(), amplitudes.toIntArray())
        }
    }
}

/**
 * Plays hold vibrations on the device vibrator: the thin Android side of #44. Nothing if the
 * phone has no vibrator, or touch feedback is off in the system settings. On API 33+ it counts
 * as touch feedback, so the system's touch-vibration strength applies. A vibrator without
 * amplitude control plays the same rhythm, on or off.
 */
class HoldVibrator(context: Context) {
    private val resolver = context.contentResolver
    private val vibrator: Vibrator? =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            context.getSystemService(Vibrator::class.java)
        }

    fun play(pulses: List<Pulse>) {
        val v = vibrator?.takeIf { it.hasVibrator() } ?: return
        if (pulses.isEmpty() || !touchFeedbackOn()) return
        val w = Waveform.of(pulses)
        try {
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
