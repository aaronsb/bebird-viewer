// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.band

import android.content.res.AssetManager
import android.graphics.Bitmap
import java.util.zip.GZIPInputStream

/** The band's fonts, from the app's assets: Terminus for the readout, GNU Unifont as fallback. */
object BandFonts {
    const val TERMINUS = "fonts/ter-u16n.bdf"
    const val UNIFONT = "fonts/unifont-16.0.02.hex.gz"

    /** Parses both fonts; takes a moment (Unifont has ~57 000 glyphs), so call it off the main thread. */
    fun load(assets: AssetManager): GlyphSource {
        val terminus = assets.open(TERMINUS).bufferedReader().use(PixelFont::parseBdf)
        val unifont = GZIPInputStream(assets.open(UNIFONT)).bufferedReader().use(PixelFont::parseHex)
        return GlyphSource(terminus, unifont)
    }
}

fun PixelImage.toBitmap(): Bitmap = Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)

fun Bitmap.toPixelImage(): PixelImage {
    val px = IntArray(width * height)
    getPixels(px, 0, width, 0, 0, width, height)
    return PixelImage(width, height, px)
}

/** [BandRenderer.compose] for a Bitmap frame, for saving stills (#15). */
fun BandRenderer.compose(frame: Bitmap, d: BandData, band: Boolean, circle: Boolean): Bitmap =
    if (!band && !circle) frame else compose(frame.toPixelImage(), d, band, circle).toBitmap()
