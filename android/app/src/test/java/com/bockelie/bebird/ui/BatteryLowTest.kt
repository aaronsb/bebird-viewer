// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import com.bockelie.bebird.R
import com.bockelie.bebird.band.Fonts
import com.bockelie.bebird.band.PixelText
import com.bockelie.bebird.focus.ScaleDisclaimer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import com.bockelie.bebird.proto.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

class BatteryLowTest {
    private val text = PixelText(Fonts.source)
    private val label = AppStrings.text(R.string.battery_low)

    @Test fun onAtTwentyOffAtTwentyFive() {
        var shown = false
        val seen = listOf(30, 21, 20, 22, 24, 25, 23, 20, 19).map { p -> BatteryLow.next(shown, p, charging = false).also { shown = it } }
        assertEquals(listOf(false, false, true, true, true, false, false, true, true), seen)
    }

    @Test fun neverWhileChargingOrWithoutAReading() {
        assertEquals(false, BatteryLow.next(was = true, percent = 10, charging = true))
        assertEquals(false, BatteryLow.next(was = true, percent = null, charging = false))
        // unplugged at 22 %: stays off until it drops to 20
        assertEquals(false, BatteryLow.next(was = false, percent = 22, charging = false))
    }

    @Test fun theWordingAndItsGlyphs() {
        assertEquals("BATTERY LOW", label)
        assertEquals("Scope battery low", AppStrings.text(R.string.battery_low_description))
        for (cp in label.codePoints()) assertNotNull("no Terminus glyph for U+%04X".format(cp), Fonts.terminus.glyph(cp))
    }

    @Test fun itSitsUnderCloseSlot() {
        // 11 cells and a pixel of outline each side; CLOSE's slot is 58 px high at scale 1
        assertEquals(BatteryLow.Box(8, 58, 90, 18), BatteryLow.box(text, label, 1))
        assertEquals(BatteryLow.Box(16, 116, 180, 36), BatteryLow.box(text, label, 2))
    }

    @Test fun clearOfTheDisclaimerAndInsideTheViewport() {
        val note = listOf(R.string.scale_note_title, R.string.scale_note_focus, R.string.scale_note_sensor).map(AppStrings::text)
        for ((w, h) in listOf(1080 to 1080, 1080 to 1500, 1440 to 2000, 2400 to 900, 2000 to 700, 720 to 720, 480 to 480, 320 to 300)) {
            val k = maxOf(1, minOf(w, h) / 480)
            val b = BatteryLow.box(text, label, k)
            assertTrue("${w}x$h: outside the viewport", b.left + b.width <= w && b.top + b.height <= h)
            // within CLOSE's width, which the disclaimer keeps clear of
            assertTrue("${w}x$h: wider than CLOSE's slot", b.left + b.width <= 112 * k)
            val p = ScaleDisclaimer.place(text, note, w, h, k, 112 * k) ?: continue
            val noteLeft = w - ScaleDisclaimer.INSET * p.scale - p.width * p.scale
            assertTrue("${w}x$h: overlaps the disclaimer", b.left + b.width <= noteLeft)
        }
    }

    @Test fun theLatchKeepsItsStateAcrossCollectorsAndResetsWithTheSession() = runBlocking {
        val readings = MutableStateFlow<Protocol.Battery?>(null)
        val scope = CoroutineScope(Dispatchers.Unconfined)
        try {
            // as the ViewModel holds it
            val low = BatteryLow.latch(readings).stateIn(scope, SharingStarted.Eagerly, false)
            val seen = listOf(30, 20, 22).map { readings.value = Protocol.Battery(1, it); low.value }
            assertEquals(listOf(false, true, true), seen)
            // the screen recreated (rotation, annotate): a new collector sees it still on at 22-24 %
            assertEquals(true, low.first())
            readings.value = Protocol.Battery(1, 24)
            assertEquals(true, low.value)
            // charging hides it
            readings.value = Protocol.Battery(2, 24)
            assertEquals(false, low.value)
            readings.value = Protocol.Battery(1, 20)
            assertEquals(true, low.value)
            // a new session starts with no reading: reset, and 22 % doesn't bring it back
            readings.value = null
            assertEquals(false, low.value)
            readings.value = Protocol.Battery(1, 22)
            assertEquals(false, low.value)
        } finally {
            scope.cancel()
        }
    }
}
