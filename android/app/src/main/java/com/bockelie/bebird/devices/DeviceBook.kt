// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.devices

/**
 * A scope the app has joined before. [bssid] is what lets a later request name this exact
 * access point (SSID + BSSID). [bssidConfirmed] says it came first-hand (Android joined or
 * reported it) rather than from the scope's beacon alone.
 */
data class KnownDevice(
    val ssid: String,
    val bssid: String?,       // upper-case "AA:BB:CC:DD:EE:FF", or null if we never learned it
    val nickname: String? = null,
    val lastSeen: Long,       // epoch milliseconds
    val bssidConfirmed: Boolean = false,
) {
    val key: String get() = bssid ?: "ssid:$ssid"
    val label: String get() = nickname ?: ssid

    /**
     * Same access point as a scan result: by BSSID when ours is confirmed and theirs is known,
     * otherwise by SSID (an unconfirmed BSSID may be the beacon's MAC, not the BSSID).
     */
    fun matches(ssid: String?, bssid: String?): Boolean =
        if (bssidConfirmed && this.bssid != null && bssid != null) this.bssid == bssid else this.ssid == ssid
}

/**
 * The remembered scopes and which one was used last, as ~/.config/bebird/last-device.json is
 * for the desktop viewer. Immutable and pure: every change returns a new book.
 */
data class DeviceBook(val devices: List<KnownDevice> = emptyList(), val lastKey: String? = null) {
    /** Most recently seen first. */
    val sorted: List<KnownDevice> get() = devices.sortedByDescending { it.lastSeen }
    val last: KnownDevice? get() = devices.firstOrNull { it.key == lastKey }

    /**
     * Record that we are on [ssid] / [bssid] now, and make it the last device.
     *
     * Identity rule: two entries may share an SSID only if both BSSIDs are confirmed and
     * differ; that is the one case where we know they are two access points. Otherwise one
     * source may simply report a different value than another (the beacon's MAC vs the real
     * BSSID), so the entry with that SSID is updated instead:
     * - the same BSSID: that entry;
     * - no BSSID now: the most recent entry with the SSID, keeping its BSSID;
     * - an entry with the SSID whose BSSID is missing or unconfirmed: it takes the new BSSID
     *   (a confirmed one always wins; between unconfirmed ones the newest wins);
     * - an entry with the SSID and a confirmed BSSID, and an unconfirmed new one: that entry,
     *   keeping its confirmed BSSID;
     * - a confirmed BSSID different from every confirmed one with the SSID: a new entry.
     */
    fun seen(ssid: String, bssid: String?, at: Long, confirmed: Boolean = false): DeviceBook {
        val sameSsid = devices.filter { it.ssid == ssid }.sortedByDescending { it.lastSeen }
        val match = when {
            bssid != null -> devices.firstOrNull { it.bssid == bssid }
                ?: sameSsid.firstOrNull { it.bssid == null || !it.bssidConfirmed }
                ?: sameSsid.firstOrNull().takeIf { !confirmed }
            else -> sameSsid.firstOrNull()
        }
        val updated = when {
            match == null -> KnownDevice(ssid, bssid, lastSeen = at, bssidConfirmed = confirmed && bssid != null)
            bssid == null -> match.copy(lastSeen = at)
            match.bssid == bssid -> match.copy(ssid = ssid, lastSeen = at, bssidConfirmed = match.bssidConfirmed || confirmed)
            match.bssidConfirmed && !confirmed -> match.copy(lastSeen = at)  // keep the first-hand BSSID
            else -> match.copy(bssid = bssid, lastSeen = at, bssidConfirmed = confirmed)
        }
        // A BSSID change can collide with another entry's key; that entry is the same scope.
        val rest = devices.filter { it !== match && it.key != updated.key }
        return DeviceBook(rest + updated, updated.key)
    }

    /** A blank nickname clears it. */
    fun rename(key: String, nickname: String?): DeviceBook =
        copy(devices = devices.map { if (it.key == key) it.copy(nickname = nickname?.trim()?.ifEmpty { null }) else it })

    fun forget(key: String): DeviceBook =
        DeviceBook(devices.filter { it.key != key }, lastKey.takeUnless { it == key })

    /** One line of header then one line per device, tab-separated, with \\ \t \n escaped. */
    fun encode(): String = buildString {
        append(VERSION).append('\t').append(esc(lastKey.orEmpty())).append('\n')
        for (d in devices) {
            listOf(d.ssid, d.bssid.orEmpty(), d.nickname.orEmpty(), d.lastSeen.toString(), if (d.bssidConfirmed) "1" else "0")
                .joinTo(this, "\t") { esc(it) }
            append('\n')
        }
    }

    companion object {
        private const val VERSION = "v2"  // v1 had no confirmed column

        /**
         * The inverse of [encode]. Reads v1 too (its BSSIDs count as unconfirmed). A row with
         * any invalid column is skipped; an unknown version or header yields an empty book.
         */
        fun decode(text: String?): DeviceBook {
            val lines = text?.split('\n')?.filter { it.isNotEmpty() } ?: return DeviceBook()
            val header = lines.firstOrNull()?.split('\t') ?: return DeviceBook()
            val columns = when (header.firstOrNull()) {
                "v1" -> 4
                VERSION -> 5
                else -> return DeviceBook()
            }
            val devices = lines.drop(1).mapNotNull { row(it, columns) }.distinctBy { it.key }
            val last = header.getOrNull(1)?.let(::unesc)?.ifEmpty { null }
            return DeviceBook(devices, last.takeIf { k -> devices.any { it.key == k } })
        }

        private fun row(line: String, columns: Int): KnownDevice? {
            val f = line.split('\t').map(::unesc)
            if (f.size != columns) return null
            val ssid = WifiIds.ssid(f[0])?.takeIf { it == f[0] } ?: return null
            val bssid = if (f[1].isEmpty()) null else WifiIds.bssid(f[1])?.takeIf { it == f[1] } ?: return null
            val seen = f[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val confirmed = when (f.getOrNull(4)) {
                null, "0" -> false
                "1" -> bssid != null
                else -> return null
            }
            return KnownDevice(ssid, bssid, f[2].ifEmpty { null }, seen, confirmed)
        }

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

        private fun unesc(s: String) = buildString {
            var i = 0
            while (i < s.length) {
                val c = s[i++]
                if (c != '\\' || i == s.length) { append(c); continue }
                when (val e = s[i++]) {
                    't' -> append('\t')
                    'n' -> append('\n')
                    else -> append(e)
                }
            }
        }
    }
}
