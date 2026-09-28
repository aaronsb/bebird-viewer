// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.devices

/**
 * Which scope are we on? Sources, most trusted first:
 * 1. the exact request we made (Android joined only that SSID, and that BSSID if given);
 * 2. WifiInfo from the network callback, when Android doesn't redact it;
 * 3. the scope's own beacon ("ssid", "mac"), which needs no permission but is unauthenticated.
 *    Its "mac" is the chip's station MAC; on the ES the access point's BSSID was observed to
 *    be that + 1, so the BSSID taken from it is derived, and unconfirmed until a join proves it.
 *
 * Rules: an SSID not from our own request must look like a scope ("bebird*", at most 32
 * bytes). The beacon's MAC is used only when the beacon's SSID is the resolved SSID.
 */
object Identify {
    data class Result(val ssid: String, val bssid: String?, val ssidFrom: String, val bssidFrom: String?) {
        /** First-hand: Android joined or reported this BSSID; one derived from the beacon isn't. */
        val bssidConfirmed: Boolean get() = bssid != null && bssidFrom != DERIVED
    }

    const val REQUEST = "request"
    const val WIFI_INFO = "wifi info"
    const val BEACON = "beacon"
    const val DERIVED = "beacon mac + 1"

    fun resolve(
        exactSsid: String? = null, exactBssid: String? = null,
        wifiSsid: String? = null, wifiBssid: String? = null,
        beaconSsid: String? = null, beaconMac: String? = null,
    ): Result? {
        val fromWifi = WifiIds.ssid(wifiSsid)?.takeIf(WifiIds::isScope)
        val fromBeacon = WifiIds.ssid(beaconSsid)?.takeIf(WifiIds::isScope)
        val (ssid, ssidFrom) = when {
            WifiIds.ssid(exactSsid) != null -> WifiIds.ssid(exactSsid)!! to REQUEST
            fromWifi != null -> fromWifi to WIFI_INFO
            fromBeacon != null -> fromBeacon to BEACON
            else -> return null
        }
        val bssid = listOf(
            REQUEST to WifiIds.bssid(exactBssid),
            WIFI_INFO to WifiIds.bssid(wifiBssid).takeIf { fromWifi == null || fromWifi == ssid },
            DERIVED to WifiIds.apBssidFromStationMac(beaconMac).takeIf { fromBeacon == ssid },
        ).firstOrNull { it.second != null }
        return Result(ssid, bssid?.second, ssidFrom, bssid?.first)
    }
}
