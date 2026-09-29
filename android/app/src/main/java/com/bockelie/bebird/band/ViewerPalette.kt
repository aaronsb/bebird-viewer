// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.band

/**
 * The viewer's colours around the picture: the field outside the image circle, the status band,
 * and the text drawn on the field. [DARK] is the band's own colours, and the only palette saved
 * files use; [LIGHT] is for the screen in the light theme (#51), for reading in bright light.
 * The picture, the empty circle and the scale inside the circle don't change. Pure.
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
        val LIGHT = ViewerPalette(
            field = 0xFFF0F0F0.toInt(), tag = 0xFF5C5C5C.toInt(), value = 0xFF1A1A1A.toInt(),
            circle = 0xFF7A7A7A.toInt(), warning = 0xFF7A4F00.toInt(), note = 0xFF4A4A4A.toInt(),
            batteryLow = 0xFFB00020.toInt(), halo = 0xFFF0F0F0.toInt(),
        )

        /** WCAG contrast ratio between two opaque ARGB colours: 1 to 21. */
        fun contrast(a: Int, b: Int): Double {
            val la = luminance(a)
            val lb = luminance(b)
            return (maxOf(la, lb) + 0.05) / (minOf(la, lb) + 0.05)
        }

        private fun luminance(argb: Int): Double {
            fun channel(v: Int): Double {
                val c = v / 255.0
                return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
            }
            return 0.2126 * channel(argb shr 16 and 0xFF) + 0.7152 * channel(argb shr 8 and 0xFF) + 0.0722 * channel(argb and 0xFF)
        }
    }
}
