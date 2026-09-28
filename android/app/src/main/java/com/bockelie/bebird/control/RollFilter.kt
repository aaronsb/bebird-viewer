// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.control

/**
 * The roll angle to draw by, as viewer.py and the official app do it: follow the sensor's
 * angle but ignore changes under [deadbandDeg] (measured around the circle), so sensor noise
 * doesn't make the picture twitch.
 */
class RollFilter(private val deadbandDeg: Int = 3) {
    var shown = 0; private set

    /** Feed a frame's roll angle (0-359); returns the angle to show. */
    fun update(angle: Int): Int {
        val diff = Math.floorMod(angle - shown, 360)
        if (minOf(diff, 360 - diff) >= deadbandDeg) shown = Math.floorMod(angle, 360)
        return shown
    }

    companion object {
        const val TRIM_STEP = 15
        const val TRIM_MAX = 180

        /** The clockwise rotation to draw with: trim, plus the roll when auto-rotate is on. */
        fun rotation(shown: Int, autoRotate: Boolean, trim: Int): Int =
            Math.floorMod(trim + if (autoRotate) shown else 0, 360)

        /** Trim after one step of [steps] (±1), kept within ±[TRIM_MAX] like the desktop's spin box. */
        fun stepTrim(trim: Int, steps: Int): Int = (trim + steps * TRIM_STEP).coerceIn(-TRIM_MAX, TRIM_MAX)
    }
}
