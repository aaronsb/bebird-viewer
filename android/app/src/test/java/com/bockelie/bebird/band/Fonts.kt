// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.band

import java.io.File
import java.util.zip.GZIPInputStream

/** The real bundled fonts, read from the app's assets (JVM tests run in the module directory). */
object Fonts {
    private val dir = File("src/main/assets")
    val terminus: PixelFont by lazy { File(dir, BandFonts.TERMINUS).bufferedReader().use(PixelFont::parseBdf) }
    val unifont: PixelFont by lazy { GZIPInputStream(File(dir, BandFonts.UNIFONT).inputStream()).bufferedReader().use(PixelFont::parseHex) }
    val source: GlyphSource by lazy { GlyphSource(terminus, unifont) }
}
