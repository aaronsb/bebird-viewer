// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.band

/**
 * The viewer's colours around the picture: the field outside the image circle, the status band,
 * and the text drawn on the field. [DARK] is the band's own colours, and the only palette saved
 * files use. Pure.
 */
data class ViewerPalette(
    /** The field round the circle, and the band's background. */
    val field: Int,
    /** The band's dim text (its tags). */
    val tag: Int,
    /** The band's bright text (its values). */
    val value: Int,
    /** The hair-thin ring at the circle's edge. */
    val circle: Int,
    /** CLOSE's label (its triangle keeps its own colours). */
    val warning: Int,
    /** The scale's disclaimer. */
    val note: Int,
    /** BATTERY LOW. */
    val batteryLow: Int,
    /** The one-pixel outline round text on the field, so it reads over the picture when zoomed. */
    val halo: Int,
) {
    companion object {
        val DARK = ViewerPalette(
            field = BandRenderer.BACKGROUND, tag = BandRenderer.TAG, value = BandRenderer.VALUE,
            circle = BandRenderer.CIRCLE, warning = 0xFFFFD700.toInt(), note = BandRenderer.TAG,
            batteryLow = 0xFFFF3B30.toInt(), halo = BandRenderer.BACKGROUND,
        )
    }
}
