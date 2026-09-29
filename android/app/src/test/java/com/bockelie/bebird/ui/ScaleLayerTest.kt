// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class ScaleLayerTest {
    @Test fun theScaleStaysUprightWhateverTheImageDoes() {
        for (r in listOf(0, 15, 90, 179, 180, 270, 359)) assertEquals(0f, scaleLayerRotation(r))
    }
}
