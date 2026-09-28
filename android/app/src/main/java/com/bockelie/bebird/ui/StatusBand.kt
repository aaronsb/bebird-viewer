// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import com.bockelie.bebird.R
import com.bockelie.bebird.band.BandData
import com.bockelie.bebird.band.BandRenderer

/**
 * The space under the image for the status band or, when it is off (or its fonts are still
 * loading), the plain readouts. Its height is fixed, the larger of the two, and known from the
 * width alone, so switching between them never moves or rescales the image above.
 */
@Composable
fun StatusBandSlot(renderer: BandRenderer?, showBand: Boolean, data: BandData, readouts: @Composable () -> Unit) {
    val readoutPx = rememberTextMeasurer().measure("0", MaterialTheme.typography.labelLarge).size.height
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val width = constraints.maxWidth
        val height = with(LocalDensity.current) { maxOf(BandRenderer.height(width), readoutPx).toDp() }
        val band = showBand && renderer != null
        // With the band, the whole slot is the band's black, so it joins the viewport above
        // without a strip of theme colour even when the slot is taller than the band.
        val background = if (band) Modifier.background(Color(BandRenderer.BACKGROUND)) else Modifier
        Box(Modifier.fillMaxWidth().height(height).then(background), contentAlignment = Alignment.Center) {
            if (band) StatusBand(renderer!!, data, width) else readouts()
        }
    }
}

/** The band, drawn pixel for pixel (integer scale, no smoothing) at [width] px. */
@Composable
private fun StatusBand(renderer: BandRenderer, data: BandData, width: Int) {
    // One pixel buffer and two bitmaps per width, reused: each redraw fills the other bitmap,
    // so Compose sees a new image without a new allocation.
    val buffers = remember(width) {
        val h = BandRenderer.height(width)
        BandBuffers(IntArray(width * h), Array(2) { Bitmap.createBitmap(width, h, Bitmap.Config.ARGB_8888) })
    }
    val image = remember(data, buffers) { buffers.draw(renderer, data, width).asImageBitmap() }
    val description = bandDescription(data)
    with(LocalDensity.current) {
        Image(
            bitmap = image,
            contentDescription = description,
            contentScale = ContentScale.None,
            filterQuality = FilterQuality.None,
            modifier = Modifier.size(image.width.toDp(), image.height.toDp()),
        )
    }
}

private class BandBuffers(val pixels: IntArray, val bitmaps: Array<Bitmap>) {
    private var next = 0

    fun draw(renderer: BandRenderer, data: BandData, width: Int): Bitmap {
        val out = renderer.renderInto(data, width, pixels)
        val bitmap = bitmaps[next]
        next = 1 - next
        bitmap.setPixels(out.pixels, 0, out.width, 0, 0, out.width, out.height)
        return bitmap
    }
}

/** What TalkBack reads for the band: the same values, spelled out. */
@Composable
private fun bandDescription(d: BandData): String {
    val res = LocalContext.current.resources
    return remember(d, res) {
        listOfNotNull(
            d.batteryPercent?.let {
                res.getString(R.string.band_battery, it) + if (d.charging) res.getString(R.string.band_charging) else ""
            },
            d.lightPercent?.let { if (it == 0) res.getString(R.string.band_light_off) else res.getString(R.string.band_light, it) },
            d.roll?.let { res.getString(R.string.band_roll, it) },
            res.getString(R.string.band_trim, d.trim),
            d.fps?.let {
                res.getString(R.string.band_fps, it) +
                    if (d.droppedPerSecond > 0) res.getString(R.string.band_dropped, d.droppedPerSecond) else ""
            },
            d.device?.let { res.getString(R.string.band_device, it) },
            d.time?.let { res.getString(R.string.band_time, it.hour, it.minute) },
            d.label?.let { res.getString(R.string.band_label, it) },
        ).joinToString("; ")
    }
}
