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
        // the beacon's MAC is the station MAC; the access point is that + 1
        assertEquals(Identify.Result("bebird-C", "CC:CC:CC:CC:CC:CD", Identify.BEACON, Identify.DERIVED), r)
        assertEquals(false, r?.bssidConfirmed)
    }

    @Test fun anExactRequestWithoutBssidTakesItFromWifiInfo() {
        val r = Identify.resolve(exactSsid = "bebird-A", wifiSsid = "\"bebird-A\"", wifiBssid = "aa:aa:aa:aa:aa:01")
        assertEquals(Identify.Result("bebird-A", "AA:AA:AA:AA:AA:01", "request", "wifi info"), r)
    }

    @Test fun ssidAloneIsEnough() {
        assertEquals(Identify.Result("bebird-A", null, "wifi info", null), Identify.resolve(wifiSsid = "bebird-A"))
    }

    @Test fun beaconSsidMustBeAScope() {
        assertNull(Identify.resolve(beaconSsid = "HomeWifi", beaconMac = "cc:cc:cc:cc:cc:cc"))
        assertNull(Identify.resolve(beaconSsid = "bebird-" + "x".repeat(30), beaconMac = "cc:cc:cc:cc:cc:cc"))
    }

    @Test fun wifiInfoSsidMustBeAScope() {
        // the picker only offered bebird*, so anything else is not what we joined
        assertNull(Identify.resolve(wifiSsid = "\"HomeWifi\""))
    }

    @Test fun beaconMacOnlyWhenItsSsidIsTheResolvedOne() {
        val r = Identify.resolve(exactSsid = "bebird-A", beaconSsid = "bebird-B", beaconMac = "cc:cc:cc:cc:cc:cc")
        assertEquals(Identify.Result("bebird-A", null, "request", null), r)
        val same = Identify.resolve(exactSsid = "bebird-A", beaconSsid = "bebird-A", beaconMac = "cc:cc:cc:cc:cc:cc")
        assertEquals("CC:CC:CC:CC:CC:CD", same?.bssid)
        assertEquals(false, same?.bssidConfirmed)
    }

    @Test fun confirmedUnlessFromTheBeacon() {
        assertEquals(true, Identify.resolve(exactSsid = "bebird-A", exactBssid = "aa:aa:aa:aa:aa:aa")?.bssidConfirmed)
        assertEquals(true, Identify.resolve(wifiSsid = "bebird-A", wifiBssid = "aa:aa:aa:aa:aa:aa")?.bssidConfirmed)
        assertEquals(false, Identify.resolve(exactSsid = "bebird-A")?.bssidConfirmed)
    }

    @Test fun nothingKnownIsNull() {
        assertNull(Identify.resolve(wifiSsid = WifiIds.REDACTED_SSID, beaconMac = "cc:cc:cc:cc:cc:cc"))
    }
}
