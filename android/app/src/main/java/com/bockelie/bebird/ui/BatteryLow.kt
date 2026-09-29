// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import com.bockelie.bebird.band.PixelText

/**
 * BATTERY LOW in the viewport's upper left, under CLOSE's slot (#48): when it shows, and where.
 * Pure; [ZoomableCircle] draws it.
 */
object BatteryLow {
    /** On at this percentage or below... */
    const val ON_AT = 20
    /** ...and off again only at this or above, so it doesn't flicker round the threshold. */
    const val OFF_AT = 25
    /** In the band's font, outlined in black like CLOSE's label. */
    const val RED = 0xFFFF3B30.toInt()

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

    /** The label's box in viewport pixels at CLOSE's whole scale [k]: its lines and outline. */
    data class Box(val left: Int, val top: Int, val width: Int, val height: Int)

    /** Where [label] goes at scale [k]: one line, a pixel of outline round it. */
    fun box(text: PixelText, label: String, k: Int): Box =
        Box(LEFT * k, TOP * k, (text.width(label) + 2) * k, (text.height(1) + 2) * k)
}
