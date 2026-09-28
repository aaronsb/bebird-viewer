// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.devices

/** Normalising the SSID and BSSID Android reports, which may be quoted or redacted. */
object WifiIds {
    /** What WifiInfo returns in place of an SSID the app may not see. */
    const val REDACTED_SSID = "<unknown ssid>"
    /** What WifiInfo and scan results return in place of a BSSID the app may not see. */
    const val REDACTED_BSSID = "02:00:00:00:00:00"

    private val MAC = Regex("^[0-9A-Fa-f]{2}([:-]?[0-9A-Fa-f]{2}){5}$")

    /** 802.11 limit; WifiNetworkSpecifier.Builder.setSsid throws beyond it. */
    const val MAX_SSID_BYTES = 32

    /** A usable SSID: quotes stripped; null if blank, redacted or longer than 32 UTF-8 bytes. */
    fun ssid(raw: String?): String? {
        val s = raw?.trim()?.removeSurrounding("\"") ?: return null
        return s.takeUnless { it.isEmpty() || it == REDACTED_SSID || it.toByteArray(Charsets.UTF_8).size > MAX_SSID_BYTES }
    }

    /** A usable BSSID as upper-case "AA:BB:CC:DD:EE:FF"; null if missing, malformed, redacted or all zero. */
    fun bssid(raw: String?): String? {
        val s = raw?.trim() ?: return null
        if (!MAC.matches(s)) return null
        val hex = s.filter { it != ':' && it != '-' }.uppercase()
        val mac = hex.chunked(2).joinToString(":")
        return mac.takeUnless { it == REDACTED_BSSID || it == "00:00:00:00:00:00" || it == "FF:FF:FF:FF:FF:FF" }
    }

    /** Scope networks are "bebird-<model>-<number>"; like wifi.py, case doesn't matter here. */
    fun isScope(ssid: String?): Boolean = ssid?.lowercase()?.startsWith("bebird") == true
}
