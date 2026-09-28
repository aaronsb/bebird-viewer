// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.devices

/**
 * Which scope are we on? Sources, most trusted first:
 * 1. the exact request we made (Android joined only that SSID/BSSID);
 * 2. WifiInfo from the network callback, when Android doesn't redact it;
 * 3. the scope's own beacon ("ssid", "mac"), which needs no permission. Whether its "mac" is
 *    the access point's BSSID is not yet confirmed on hardware; the caller logs both to check.
 */
object Identify {
    data class Result(val ssid: String, val bssid: String?, val ssidFrom: String, val bssidFrom: String?)

    fun resolve(
        exactSsid: String? = null, exactBssid: String? = null,
        wifiSsid: String? = null, wifiBssid: String? = null,
        beaconSsid: String? = null, beaconMac: String? = null,
    ): Result? {
        val ssid = listOf("request" to exactSsid, "wifi info" to WifiIds.ssid(wifiSsid), "beacon" to WifiIds.ssid(beaconSsid))
            .firstOrNull { it.second != null } ?: return null
        val bssid = listOf("request" to WifiIds.bssid(exactBssid), "wifi info" to WifiIds.bssid(wifiBssid), "beacon" to WifiIds.bssid(beaconMac))
            .firstOrNull { it.second != null }
        return Result(ssid.second!!, bssid?.second, ssid.first, bssid?.first)
    }
}
