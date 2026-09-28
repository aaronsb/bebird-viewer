// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.devices

/**
 * A scope the app has joined before. [bssid] is what lets a later request name this exact
 * access point (SSID + BSSID), which Android approves without showing its picker again.
 */
data class KnownDevice(
    val ssid: String,
    val bssid: String?,       // upper-case "AA:BB:CC:DD:EE:FF", or null if we never learned it
    val nickname: String? = null,
    val lastSeen: Long,       // epoch milliseconds
) {
    val key: String get() = bssid ?: "ssid:$ssid"
    val label: String get() = nickname ?: ssid

    /** Same access point: by BSSID when both sides know it, otherwise by SSID. */
    fun matches(ssid: String?, bssid: String?): Boolean =
        if (this.bssid != null && bssid != null) this.bssid == bssid else this.ssid == ssid
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
     * Record that we are on [ssid] / [bssid] now, and make it the last device. A known BSSID
     * matches by BSSID (and upgrades an SSID-only entry of the same name); without one, the
     * most recent entry with that SSID is taken to be the same scope.
     */
    fun seen(ssid: String, bssid: String?, at: Long): DeviceBook {
        val match = if (bssid != null) {
            devices.firstOrNull { it.bssid == bssid } ?: devices.firstOrNull { it.bssid == null && it.ssid == ssid }
        } else {
            devices.filter { it.ssid == ssid }.maxByOrNull { it.lastSeen }
        }
        val updated = match?.copy(ssid = ssid, bssid = bssid ?: match.bssid, lastSeen = at)
            ?: KnownDevice(ssid, bssid, lastSeen = at)
        return DeviceBook(devices.filter { it !== match } + updated, updated.key)
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
            listOf(d.ssid, d.bssid.orEmpty(), d.nickname.orEmpty(), d.lastSeen.toString())
                .joinTo(this, "\t") { esc(it) }
            append('\n')
        }
    }

    companion object {
        private const val VERSION = "v1"

        /** The inverse of [encode]; anything unreadable yields an empty book or is skipped. */
        fun decode(text: String?): DeviceBook {
            val lines = text?.split('\n')?.filter { it.isNotEmpty() } ?: return DeviceBook()
            val header = lines.firstOrNull()?.split('\t') ?: return DeviceBook()
            if (header.firstOrNull() != VERSION) return DeviceBook()
            val devices = lines.drop(1).mapNotNull { line ->
                val f = line.split('\t').map(::unesc)
                val seen = f.getOrNull(3)?.toLongOrNull() ?: return@mapNotNull null
                if (f[0].isEmpty()) return@mapNotNull null
                KnownDevice(f[0], f[1].ifEmpty { null }, f[2].ifEmpty { null }, seen)
            }
            val last = header.getOrNull(1)?.let(::unesc)?.ifEmpty { null }
            return DeviceBook(devices, last.takeIf { k -> devices.any { it.key == k } })
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
