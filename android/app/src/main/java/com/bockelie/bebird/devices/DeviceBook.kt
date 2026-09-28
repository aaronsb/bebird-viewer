// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.devices

/**
 * A scope the app has joined before. [bssid] is what lets a later request name this exact
 * access point (SSID + BSSID). [bssidConfirmed] says it came first-hand (Android joined or
 * reported it) rather than derived from the scope's beacon. [rejectedBssid] is a derived one
 * that an exact request failed to find, so it isn't adopted again.
 */
data class KnownDevice(
    val ssid: String,
    val bssid: String?,       // upper-case "AA:BB:CC:DD:EE:FF", or null if we never learned it
    val nickname: String? = null,
    val lastSeen: Long,       // epoch milliseconds
    val bssidConfirmed: Boolean = false,
    val rejectedBssid: String? = null,
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
    fun seen(ssid: String, seenBssid: String?, at: Long, confirmed: Boolean = false): DeviceBook {
        // A derived BSSID that already failed an exact request for this SSID is not a BSSID.
        val bssid = seenBssid?.takeUnless { !confirmed && devices.any { d -> d.ssid == ssid && d.rejectedBssid == it } }
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
        }.let { if (it.bssidConfirmed && it.rejectedBssid == it.bssid) it.copy(rejectedBssid = null) else it }  // proven after all
        // A BSSID change can collide with another entry's key; that entry is the same scope.
        val rest = devices.filter { it !== match && it.key != updated.key }
        return DeviceBook(rest + updated, updated.key)
    }

    /**
     * The scope was joined by SSID right after an exact request for [bssid] found nothing, so
     * that BSSID is wrong. If it is only derived (unconfirmed), drop it and remember it as
     * rejected, so it isn't adopted again and the next request names the SSID only. A
     * confirmed BSSID is kept: Android itself reported it once.
     */
    fun disprove(bssid: String): DeviceBook {
        val d = devices.firstOrNull { it.bssid == bssid && !it.bssidConfirmed } ?: return this
        val key = d.key
        val updated = d.copy(bssid = null, rejectedBssid = bssid)
        val rest = devices.filter { it !== d && it.key != updated.key }
        return DeviceBook(rest + updated, if (lastKey == key) updated.key else lastKey)
    }

    /** Make [key] the device Connect goes to. */
    fun select(key: String): DeviceBook = if (devices.any { it.key == key }) copy(lastKey = key) else this

    /** A blank nickname clears it. */
    fun rename(key: String, nickname: String?): DeviceBook =
        copy(devices = devices.map { if (it.key == key) it.copy(nickname = nickname?.trim()?.ifEmpty { null }) else it })

    fun forget(key: String): DeviceBook =
        DeviceBook(devices.filter { it.key != key }, lastKey.takeUnless { it == key })

    /** One line of header then one line per device, tab-separated, with \\ \t \n escaped. */
    fun encode(): String = buildString {
        append(VERSION).append('\t').append(esc(lastKey.orEmpty())).append('\n')
        for (d in devices) {
            listOf(d.ssid, d.bssid.orEmpty(), d.nickname.orEmpty(), d.lastSeen.toString(), if (d.bssidConfirmed) "1" else "0",
                d.rejectedBssid.orEmpty())
                .joinTo(this, "\t") { esc(it) }
            append('\n')
        }
    }

    companion object {
        private const val VERSION = "v3"  // v1 had no confirmed column, v2 no rejected one

        /**
         * The inverse of [encode]. Reads v1 and v2 too (v1 BSSIDs count as unconfirmed). A row with
         * any invalid column is skipped; an unknown version or header yields an empty book.
         */
        fun decode(text: String?): DeviceBook {
            val lines = text?.split('\n')?.filter { it.isNotEmpty() } ?: return DeviceBook()
            val header = lines.firstOrNull()?.split('\t') ?: return DeviceBook()
            val columns = when (header.firstOrNull()) {
                "v1" -> 4
                "v2" -> 5
                VERSION -> 6
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
            val bssid = f[1].ifEmpty { null }
            val rejected = f.getOrNull(5)?.ifEmpty { null }
            if (!canonicalMac(bssid) || !canonicalMac(rejected)) return null
            val seen = f[3].toLongOrNull()?.takeIf { it >= 0 } ?: return null
            val confirmed = when (f.getOrNull(4)) {
                null, "0" -> false
                "1" -> bssid != null
                else -> return null
            }
            return KnownDevice(ssid, bssid, f[2].ifEmpty { null }, seen, confirmed, rejected)
        }

        /** An optional MAC column is either empty or exactly as [encode] writes it. */
        private fun canonicalMac(s: String?) = s == null || WifiIds.bssid(s) == s

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
