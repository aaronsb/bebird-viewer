// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.band

import com.bockelie.bebird.focus.ScaleOverlay
import org.junit.Assert.assertEquals
import org.junit.Test

class ViewerPaletteTest {
    @Test fun darkIsTheColoursTheViewerAlwaysHad() {
        with(ViewerPalette.DARK) {
            assertEquals(
                listOf(0xFF000000, 0xFF8C8C8C, 0xFFE6E6E6, 0xFFB4B4B4, 0xFFFFD700, 0xFF8C8C8C, 0xFFFF3B30, 0xFF000000).map { it.toInt() },
                listOf(field, tag, value, circle, warning, note, batteryLow, halo),
            )
            assertEquals(ScaleOverlay.WARNING, warning)  // CLOSE's own yellow
        }
    }
}
