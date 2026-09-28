// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.proto

/**
 * The scope's status beacon (UDP broadcast to 58099, ~10/s), reduced to what identifies the
 * scope. Example: {"brand":"bebird","model":"ES","mac":"…","ssid":"bebird-ES-XXXXXX",…}.
 * Only read, never answered. Pure Kotlin (no org.json, which the JVM tests can't run).
 */
data class Beacon(val ssid: String?, val mac: String?, val model: String?) {
    companion object {
        private fun field(json: String, name: String): String? =
            Regex("\"$name\"\\s*:\\s*\"((?:[^\"\\\\]|\\\\.)*)\"").find(json)?.groupValues?.get(1)

        /** Parse one datagram, or null if it isn't a Bebird beacon. */
        fun parse(data: ByteArray, length: Int = data.size): Beacon? {
            val json = String(data, 0, length, Charsets.UTF_8).trim()
            if (!json.startsWith("{") || field(json, "brand")?.lowercase() != "bebird") return null
            return Beacon(field(json, "ssid"), field(json, "mac"), field(json, "model"))
        }
    }
}
