// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import kotlin.math.exp
import kotlin.math.max
import kotlin.math.sqrt

/** What the estimator makes of the current frame (§7), in priority order of the rules. */
enum class FocusState(val label: String) {
    /** Lying still (roll quantised to exactly 0 spread): not hand-held, indicators suppressed. */
    RESTING("resting"),
    /** Moving a lot: no scale, no indicator. */
    COARSE("coarse"),
    /** Sharp, careful and armed, but not yet for [FocusConfig.zoneHold]. */
    SETTLING("settling"),
    /** Sharp relative to the recent peak, careful, and an approach was seen. */
    IN_ZONE("in-zone"),
    APPROACHING("approaching"),
    RECEDING("receding"),
    /** Sharp and careful, but no approach seen: probably a far, room-lit scene. */
    SHARP_UNARMED("sharp-unarmed"),
    SEARCHING("searching"),
}

/** One frame's outcome, plus the numbers behind it (for a debug line or a log). */
data class FocusResult(
    val t: Double,
    val state: FocusState,
    /** The scale is claimed (overlay in lock colour): the state is [FocusState.IN_ZONE]. */
    val locked: Boolean,
    /** Something is probably at the tip end: in-zone with motion, held briefly. Best effort. */
    val close: Boolean,
    val armed: Boolean,
    val tipFraction: Double,
    val tipPresent: Boolean,
    val bright: Double,
    val sharpRaw: Double,
    val sharp: Double,
    val peak: Double,
    /** sharp / peak. */
    val relative: Double,
    val jitter: Double,
    /** Brightness slope relative to the level, per second. */
    val slope: Double,
    val motion: Double,
    val ambient: Double,
)

/**
 * The time-series half of the estimator (§4 to §7): from per-frame features (brightness and
 * sharpness under the tip mask, roll, motion outside the tip, tip fraction) to a state. Pure
 * arithmetic on timestamps, so recorded feature streams can be replayed through it.
 */
class FocusTracker(private val cfg: FocusConfig = FocusConfig()) {
    private val rolls = Window()
    private val brights = Window()
    private val ambientWin = Window()
    private val recent = Window()

    private var rollRaw = 0
    private var hasRoll = false
    private var rollUnwrapped = 0.0
    private var bsm = Double.NaN
    private var sharp = Double.NaN
    private var peak = 0.0
    private var tFirst = Double.NaN
    private var tLast = Double.NaN
    private var armed = false
    private var armBase = 0.0
    private var lowSince = Double.NaN
    private var coarse = false
    private var locked = false
    private var tZone = -1e9
    private var zoneSince = Double.NaN
    private var closeUntil = -1.0

    /**
     * The tip mask changed on this frame: brightness and sharpness over the new region aren't
     * comparable with the running levels, so scale those by new/old (both measured on this
     * frame). Call before [update]. Without it a mask change moves the peak and the lock drops.
     */
    fun rescale(sharpRatio: Double?, brightRatio: Double?) {
        if (sharpRatio != null && !sharp.isNaN()) {
            sharp *= sharpRatio
            peak *= sharpRatio
        }
        if (brightRatio != null && !bsm.isNaN()) {
            bsm *= brightRatio
            armBase *= brightRatio
            ambientWin.scale(brightRatio)
            recent.scale(brightRatio)
            brights.scale(brightRatio)
        }
    }

    fun update(
        t: Double,
        roll: Int,
        bright: Double,
        sharpRaw: Double,
        motion: Double,
        tipFraction: Double,
    ): FocusResult {
        val dt = if (tLast.isNaN()) 0.1 else max(1e-3, t - tLast)
        tLast = t
        sharp = if (sharp.isNaN()) sharpRaw else sharp + cfg.sharpAlpha * (sharpRaw - sharp)

        // roll: unwrap to the nearest turn, jitter over the short window, resting over the long one
        rollUnwrapped = if (!hasRoll) roll.toDouble() else rollUnwrapped + (Math.floorMod(roll - rollRaw + 180, 360) - 180)
        rollRaw = roll
        hasRoll = true
        rolls.add(t, rollUnwrapped)
        rolls.dropBefore(t - cfg.restWindow)
        val jitter = rolls.stdevSince(t - cfg.jitterWindow)
        val restSd = rolls.stdevSince(Double.NEGATIVE_INFINITY)
        var resting = rolls.span() >= cfg.restWindow * 0.9 && restSd < cfg.restSd

        // brightness slope, relative to the level, per second
        brights.add(t, bright)
        brights.dropBefore(t - cfg.slopeWindow)
        val slope = brights.slope() / max(10.0, brights.mean())

        // armed: the smoothed brightness stepped up to an LED-lit level from its recent minimum.
        // Nothing is collected during warm-up (the stream starts with an auto-exposure ramp).
        if (tFirst.isNaN()) tFirst = t
        val warm = t - tFirst >= cfg.warmup
        bsm = if (bsm.isNaN()) bright else bsm + cfg.brightAlpha * (bright - bsm)
        if (warm) {
            ambientWin.add(t, bsm)
            recent.add(t, bsm)
        }
        ambientWin.dropBefore(t - cfg.ambientWindow)
        recent.dropBefore(t - cfg.armWindow)
        val ambient = if (ambientWin.size > 0) ambientWin.min() else bsm
        val base = if (recent.size > 0) recent.min() else bsm
        val tipPresent = tipFraction >= cfg.tipPresent
        val armMin = if (tipPresent) cfg.armMinTip else cfg.armMin
        val low = bsm < cfg.disarmRatio * armBase || bsm < cfg.disarmFloor * armMin
        if (!armed) {
            if (warm && bsm >= armMin && bsm >= cfg.armRatio * base) {
                armed = true
                armBase = base
            }
        } else if (low) {
            // only a sustained fall disarms: a wall shading the LED for a moment must not
            if (lowSince.isNaN()) lowSince = t
            else if (t - lowSince >= cfg.disarmHold) armed = false
        }
        if (!armed || bsm >= cfg.disarmRatio * armBase && bsm >= cfg.disarmFloor * armMin) lowSince = Double.NaN

        if (coarse && jitter < cfg.coarseOff) coarse = false
        else if (!coarse && jitter > cfg.coarseOn) coarse = true

        // relative sharpness against a slowly decaying peak, not fed by coarse frames, nor by
        // resting ones unless armed (the tip resting on a surface is a valid focus reference)
        peak *= exp(-dt / cfg.peakTau)
        if (!coarse && (!resting || armed)) peak = max(peak, sharp)
        val rel = if (peak > 0) sharp / peak else 0.0
        if (locked && rel < cfg.lockOff) locked = false
        else if (!locked && rel >= cfg.lockOn && warm) locked = true

        val careful = jitter < cfg.careful
        // still + armed + sharp is the tip resting on a surface: that stays in-zone
        if (resting && armed) resting = !locked && t - tZone >= cfg.restArmed
        var state = when {
            resting -> FocusState.RESTING
            coarse -> FocusState.COARSE
            locked && careful && armed -> FocusState.IN_ZONE
            slope > cfg.slopeThreshold -> FocusState.APPROACHING
            slope < -cfg.slopeThreshold -> FocusState.RECEDING
            locked && careful -> FocusState.SHARP_UNARMED
            else -> FocusState.SEARCHING
        }
        if (state == FocusState.IN_ZONE) {
            if (zoneSince.isNaN()) zoneSince = t
            if (t - zoneSince < cfg.zoneHold) state = FocusState.SETTLING
            tZone = t
        } else {
            zoneSince = Double.NaN
        }
        if (state == FocusState.IN_ZONE && motion >= cfg.closeMotion) closeUntil = t + cfg.closeHold
        val inZone = state == FocusState.IN_ZONE
        return FocusResult(
            t = t, state = state, locked = inZone, close = inZone && t <= closeUntil,
            armed = armed, tipFraction = tipFraction, tipPresent = tipPresent,
            bright = bright, sharpRaw = sharpRaw, sharp = sharp, peak = peak, relative = rel,
            jitter = jitter, slope = slope, motion = motion, ambient = ambient,
        )
    }

    /** (time, value) samples in arrival order, oldest dropped by time. */
    private class Window {
        private var ts = DoubleArray(64)
        private var vs = DoubleArray(64)
        private var start = 0
        var size = 0; private set

        private fun at(k: Int) = (start + k) % ts.size

        fun add(t: Double, v: Double) {
            if (size == ts.size) {
                val nt = DoubleArray(ts.size * 2)
                val nv = DoubleArray(ts.size * 2)
                for (k in 0 until size) { nt[k] = ts[at(k)]; nv[k] = vs[at(k)] }
                ts = nt; vs = nv; start = 0
            }
            ts[at(size)] = t
            vs[at(size)] = v
            size++
        }

        fun dropBefore(t: Double) {
            while (size > 0 && ts[start] < t) {
                start = (start + 1) % ts.size
                size--
            }
        }

        fun scale(k: Double) {
            for (j in 0 until size) vs[at(j)] *= k
        }

        fun min(): Double {
            var m = Double.POSITIVE_INFINITY
            for (j in 0 until size) m = minOf(m, vs[at(j)])
            return m
        }

        fun mean(): Double {
            var s = 0.0
            for (j in 0 until size) s += vs[at(j)]
            return s / size
        }

        fun span() = if (size == 0) 0.0 else ts[at(size - 1)] - ts[start]

        /** Population standard deviation of the samples at or after [t0]; 0 for fewer than two. */
        fun stdevSince(t0: Double): Double {
            var n = 0
            var s = 0.0
            for (j in 0 until size) if (ts[at(j)] >= t0) { s += vs[at(j)]; n++ }
            if (n < 2) return 0.0
            val m = s / n
            var q = 0.0
            for (j in 0 until size) if (ts[at(j)] >= t0) { val d = vs[at(j)] - m; q += d * d }
            return sqrt(q / n)
        }

        /** Least-squares slope of value over time; 0 for fewer than three samples. */
        fun slope(): Double {
            if (size < 3) return 0.0
            var st = 0.0
            var sv = 0.0
            for (j in 0 until size) { st += ts[at(j)]; sv += vs[at(j)] }
            val mt = st / size
            val mv = sv / size
            var den = 0.0
            var num = 0.0
            for (j in 0 until size) den += (ts[at(j)] - mt) * (ts[at(j)] - mt)
            for (j in 0 until size) num += (ts[at(j)] - mt) * (vs[at(j)] - mv)
            return if (den > 1e-9) num / den else 0.0
        }
    }
}
