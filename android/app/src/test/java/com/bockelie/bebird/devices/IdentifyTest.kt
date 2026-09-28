// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class IdentifyTest {
    @Test fun theExactRequestWins() {
        val r = Identify.resolve(
            exactSsid = "bebird-A", exactBssid = "aa:aa:aa:aa:aa:aa",
            wifiSsid = "\"bebird-B\"", wifiBssid = "bb:bb:bb:bb:bb:bb", beaconSsid = "bebird-C", beaconMac = "cc:cc:cc:cc:cc:cc",
        )
        assertEquals(Identify.Result("bebird-A", "AA:AA:AA:AA:AA:AA", "request", "request"), r)
    }

    @Test fun redactedWifiInfoFallsBackToTheBeacon() {
        val r = Identify.resolve(
            wifiSsid = WifiIds.REDACTED_SSID, wifiBssid = WifiIds.REDACTED_BSSID,
            beaconSsid = "bebird-C", beaconMac = "cc:cc:cc:cc:cc:cc",
        )
        assertEquals(Identify.Result("bebird-C", "CC:CC:CC:CC:CC:CC", "beacon", "beacon"), r)
    }

    @Test fun anExactRequestWithoutBssidTakesItFromWifiInfo() {
        val r = Identify.resolve(exactSsid = "bebird-A", wifiSsid = "\"bebird-A\"", wifiBssid = "aa:aa:aa:aa:aa:01")
        assertEquals(Identify.Result("bebird-A", "AA:AA:AA:AA:AA:01", "request", "wifi info"), r)
    }

    @Test fun ssidAloneIsEnough() {
        assertEquals(Identify.Result("bebird-A", null, "wifi info", null), Identify.resolve(wifiSsid = "bebird-A"))
    }

    @Test fun nothingKnownIsNull() {
        assertNull(Identify.resolve(wifiSsid = WifiIds.REDACTED_SSID, beaconMac = "cc:cc:cc:cc:cc:cc"))
    }
}
