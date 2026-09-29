// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.annotate

import com.bockelie.bebird.band.BandData
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.PixelImage
import com.bockelie.bebird.capture.Frames
import com.bockelie.bebird.capture.ScaleStamp

/**
 * The two pictures an annotated save writes, from the [upright] (already rotated) frame: the
 * raw still (overlay and all, but no scale and no marks), and the same picture with the
 * proximity [scale] layer (if one was shown when pausing, see [ScaleStamp.layer]) and then the
 * [marks] over the frame part only, never over the band. Pure.
 */
fun annotatedStills(
    upright: PixelImage, band: BandRenderer?, data: BandData, overlay: Boolean,
    marks: List<Mark>, renderer: AnnotationRenderer, scale: PixelImage? = null,
): Pair<PixelImage, PixelImage> {
    val plain = Frames.composed(upright, band, data, overlay)
    val base = scale?.let { ScaleStamp.over(plain, it) } ?: plain
    return plain to renderer.paint(base, marks, upright.width, upright.height)
}
