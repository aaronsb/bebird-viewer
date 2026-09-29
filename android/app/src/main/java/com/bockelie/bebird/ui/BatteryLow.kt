// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import com.bockelie.bebird.band.PixelText
import com.bockelie.bebird.proto.Protocol
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.scan

/**
 * BATTERY LOW in the viewport's upper left, under CLOSE's slot (#48): when it shows, and where.
 * Pure; [ZoomableCircle] draws it.
 */
object BatteryLow {
    /** On at this percentage or below... */
    const val ON_AT = 20
    /** ...and off again only at this or above, so it doesn't flicker round the threshold. */
    const val OFF_AT = 25
    /** From the viewport's left edge, in font pixels: CLOSE's triangle starts here. */
    const val LEFT = 8
    /** From its top: the height of CLOSE's slot, whether or not CLOSE is showing. */
    const val TOP = 58

    /**
     * Whether the warning shows for a reading of [percent] (null: none yet), given whether it
     * showed before ([was]). Never while [charging] or without a reading.
     */
    fun next(was: Boolean, percent: Int?, charging: Boolean): Boolean = when {
        percent == null || charging -> false
        percent <= ON_AT -> true
        percent >= OFF_AT -> false
        else -> was
    }

    /** The latch's state: whether the warning is on, for which scope, after which reading. */
    data class Latch(val scope: String?, val low: Boolean, val battery: Protocol.Battery? = null)

    /**
     * [state] after a [battery] reading from [scope]. The battery belongs to the scope, so only
     * a different scope starts afresh; with the same one, a gap with no reading (Reconnect, a
     * lost and rejoined session) keeps the state. When the scope changes, the reading still
     * showing is the old scope's, so it doesn't count.
     */
    fun step(state: Latch, scope: String?, battery: Protocol.Battery?): Latch {
        val same = scope == state.scope
        val was = state.low && same
        val reading = if (same) battery else battery?.takeUnless { it == state.battery }
        return Latch(scope, if (reading == null) was else next(was, reading.percent, reading.isCharging), battery)
    }

    /**
     * The warning for a stream of (scope, battery reading) pairs, carrying the hysteresis from
     * one to the next ([step]). Held by the ViewModel, so rotating the screen or annotating
     * doesn't reset it either.
     */
    fun latch(readings: Flow<Pair<String?, Protocol.Battery?>>): Flow<Boolean> =
        readings.distinctUntilChanged()
            .scan(Latch(null, false)) { state, (scope, battery) -> step(state, scope, battery) }
            .map { it.low }
            .distinctUntilChanged()

    /** The label's box in viewport pixels at CLOSE's whole scale [k]: its lines and outline. */
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int)

    /** Where [label] goes at scale [k]: one line, a pixel of outline round it. */
    fun box(text: PixelText, label: String, k: Int): Box =
        Box(LEFT * k, TOP * k, (text.width(label) + 2) * k, (text.height(1) + 2) * k)
}
