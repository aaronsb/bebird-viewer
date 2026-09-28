// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.connection

import com.bockelie.bebird.devices.DeviceBook
import com.bockelie.bebird.devices.KnownDevice
import com.bockelie.bebird.wifi.ScopeWifi.Target
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Connect paths after a derived BSSID, walked through as ScopeConnection runs them. */
class BssidFallbackTest {
    private val derived = "12:34:56:78:9A:9F"
    private val book = DeviceBook().seen("bebird-ES-1", derived, 100)  // unconfirmed, as from the beacon

    private fun connectTarget(b: DeviceBook) = b.last!!.let { Target.Exact(it.ssid, it.bssid) }

    @Test fun exactFailsThenFallbackJoins_derivedBssidRejected() {
        val exact = connectTarget(book)
        assertEquals(Target.Exact("bebird-ES-1", derived), exact)
        val fallback = BssidFallback.afterUnavailable(exact)!!
        assertEquals(Target.Exact("bebird-ES-1", null, suspect = derived), fallback)

        val after = BssidFallback.afterJoin(book, fallback)
        assertEquals(KnownDevice("bebird-ES-1", null, lastSeen = 100, rejectedBssid = derived), after.devices.single())
        assertEquals(Target.Exact("bebird-ES-1", null), connectTarget(after))  // next Connect: SSID only
    }

    @Test fun exactAndFallbackBothFail_derivedBssidKept() {
        // scope off, or the user cancelled both prompts: nothing proven
        val fallback = BssidFallback.afterUnavailable(connectTarget(book))!!
        assertNull(BssidFallback.afterUnavailable(fallback))  // no third try
        assertEquals(Target.Exact("bebird-ES-1", derived), connectTarget(book))  // next Connect tries it again
    }

    @Test fun exactJoins_derivedBssidConfirmedAndNothingRejected() {
        val exact = connectTarget(book)
        assertEquals(book, BssidFallback.afterJoin(book, exact))
        // Identify reports the joined exact request's BSSID as confirmed
        val confirmed = book.seen("bebird-ES-1", derived, 200, confirmed = true)
        assertEquals(KnownDevice("bebird-ES-1", derived, lastSeen = 200, bssidConfirmed = true), confirmed.devices.single())
    }

    @Test fun aConfirmedBssidSurvivesAFallbackJoin() {
        val sure = DeviceBook().seen("bebird-ES-1", derived, 100, confirmed = true)
        val fallback = BssidFallback.afterUnavailable(connectTarget(sure))!!
        assertEquals(sure, BssidFallback.afterJoin(sure, fallback))
    }

    @Test fun thePickerAndSsidOnlyRequestsHaveNoFallback() {
        assertNull(BssidFallback.afterUnavailable(Target.AnyScope))
        assertNull(BssidFallback.afterUnavailable(Target.Exact("bebird-ES-1", null)))
    }
}
