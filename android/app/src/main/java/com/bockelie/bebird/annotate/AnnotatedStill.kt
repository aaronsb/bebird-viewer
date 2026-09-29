// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.annotate

import com.bockelie.bebird.band.BandData
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.PixelImage
import com.bockelie.bebird.capture.Frames

/**
 * The two pictures an annotated save writes, from the [upright] (already rotated) frame: the
 * still exactly as a snapshot saves it (overlay and all), and the same picture with [marks]
 * drawn over the frame part only, never over the band. Pure.
 */
fun annotatedStills(
    upright: PixelImage, band: BandRenderer?, data: BandData, overlay: Boolean,
    marks: List<Mark>, renderer: AnnotationRenderer,
): Pair<PixelImage, PixelImage> {
    val plain = Frames.composed(upright, band, data, overlay)
    return plain to renderer.paint(plain, marks, upright.width, upright.height)
}
