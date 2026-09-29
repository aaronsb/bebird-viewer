// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Bitmap adapter's path (Bitmap.lumaInto is one line over [frameToLuma]): on the device it
 * once recursed into itself because the pure converter had the same name. This drives the same
 * path with a fake pixel source, so a call that doesn't reach [argbToLuma] fails here.
 */
class LumaAdapterTest {
    private val n = FrameGeometry.SIZE

    @Test fun theFramePathReadsPixelsThenConverts() {
        val argb = IntArray(n * n)
        val out = ByteArray(n * n)
        var reads = 0
        frameToLuma(n, n, true, { px -> reads++; px.fill(0xFFFFFFFF.toInt()); px[0] = 0xFF000000.toInt() }, argb, out)
        assertEquals(1, reads)
        assertEquals(0, out[0].toInt() and 0xFF)        // black
        assertEquals(255, out[1].toInt() and 0xFF)      // white
    }

    @Test fun matchesTheConverterPixelForPixel() {
        val src = IntArray(n * n) { (0xFF shl 24) or (it * 2654435761L).toInt() and 0xFFFFFF }
        val viaFrame = ByteArray(n * n)
        frameToLuma(n, n, true, { src.copyInto(it) }, IntArray(n * n), viaFrame)
        val direct = ByteArray(n * n)
        argbToLuma(src, direct)
        assertArrayEquals(direct, viaFrame)
    }

    @Test fun rejectsTheWrongFrame() {
        for ((w, h, ok) in listOf(Triple(960, 960, true), Triple(n, n, false))) {
            val e = runCatching { frameToLuma(w, h, ok, {}, IntArray(n * n), ByteArray(n * n)) }.exceptionOrNull()
            assertTrue(e is IllegalArgumentException)
        }
    }
}
