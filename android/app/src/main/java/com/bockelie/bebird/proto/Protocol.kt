// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.proto

/**
 * The Bebird "ES" scope's UDP protocol: ports, command bytes and reply decoders.
 * README.md's protocol section is the spec; this mirrors viewer.py. Pure Kotlin, no Android.
 *
 * Deliberately absent: `66 3F 01 00` (switches the ES camera off until a power cycle) and
 * `66 3E` ("reboot" in the vendor app, in practice powers the scope off). Never send either.
 */
object Protocol {
    const val CAMERA_HOST = "192.168.5.1"

    const val DATA_PORT = 58080     // video control and MJPEG data
    const val COMMAND_PORT = 58090  // commands and replies
    const val BEACON_PORT = 58099   // JSON status beacon, broadcast ~10x/s
    // The scope streams to every (ip, port) that ever sent START until that same port sends
    // STOP. A fixed local port means a restarted app reuses its slot instead of leaving a ghost.
    const val CLIENT_VIDEO_PORT = 58081

    // Commands are getters so callers can't mutate a shared array.
    /** Send once per session: a second START while streaming re-initialises the camera. */
    val START: ByteArray get() = bytes(0x20, 0x36)
    /** Send before START (clears a session left on our port) and on exit, from the same port. */
    val STOP: ByteArray get() = bytes(0x20, 0x37)
    /** Battery query; polled once a second it is also the video keepalive. */
    val BATTERY: ByteArray get() = bytes(0x66, 0x3A)
    /** Commit the light level: `66 3C nn` takes effect only after this. */
    val LIGHT_COMMIT: ByteArray get() = bytes(0x66, 0x3C, 0xFF)
    /** Query the light level; the reply is one byte. */
    val LIGHT_QUERY: ByteArray get() = bytes(0x66, 0x3C, 0xFE)
    /** Board info (model, firmware, ...): JSON reply that may span several datagrams. */
    val BOARD_INFO: ByteArray get() = bytes(0x66, 0x39, 0x01, 0x01)

    /** `66 3C nn`: set the raw light level (0-100). Follow it with [LIGHT_COMMIT]. */
    fun lightSet(raw: Int): ByteArray {
        require(raw in 0..100) { "raw light level $raw outside 0-100" }
        return bytes(0x66, 0x3C, raw)
    }

    /** Set + commit, in the order they must be sent. */
    fun lightCommands(raw: Int): List<ByteArray> = listOf(lightSet(raw), LIGHT_COMMIT)

    // The LED is invisible below ~20 and stops brightening near 48, so the UI's 1-100 % maps
    // onto raw LIGHT_MIN..LIGHT_MAX and 0 % means off.
    const val LIGHT_MIN = 22
    const val LIGHT_MAX = 50

    /**
     * UI percent -> raw scope level, as viewer.py's to_scope: round(22 + (pct - 1) * 28 / 99).
     * Done in integers; (pct - 1) * 28 / 99 is never exactly .5 for pct in 1..100, so Python's
     * round-half-to-even never comes into play and this matches it for every input.
     * Out-of-range percentages are clamped to 0..100.
     */
    fun lightPercentToRaw(percent: Int): Int {
        val p = percent.coerceIn(0, 100)
        if (p == 0) return 0
        val num = LIGHT_MIN * 99 + (p - 1) * (LIGHT_MAX - LIGHT_MIN)
        return (2 * num + 99) / 198  // round(num / 99)
    }

    /** Battery reply / beacon field: high 16 bits state, low 16 bits percent. */
    data class Battery(val state: Int, val percent: Int) {
        val stateName: String
            get() = when (state) {
                0, 1 -> "battery"
                2 -> "charging"
                3 -> "charged"
                4 -> "disconnect"
                else -> state.toString()
            }
    }

    /** Decode the 4-byte `66 3A` reply (big-endian), or null if it isn't one. */
    fun decodeBattery(reply: ByteArray): Battery? {
        if (reply.size != 4) return null
        return Battery(u16(reply, 0), u16(reply, 2))
    }

    /** The beacon's `battery` number uses the same packing, e.g. 65636 = on battery, 100 %. */
    fun unpackBattery(packed: Int): Battery = Battery(packed ushr 16 and 0xFFFF, packed and 0xFFFF)

    /** Decode the 1-byte `66 3C FE` reply (raw light level), or null if it isn't one. */
    fun decodeLightLevel(reply: ByteArray): Int? = if (reply.size == 1) reply[0].toInt() and 0xFF else null

    /**
     * Roll angle from a frame's last packet: header byte 3, plus 256 when the last flag
     * (byte 1) is 2, giving 0-359 degrees. Draw the frame rotated clockwise by this.
     */
    fun rollAngle(lastFlag: Int, angleLow: Int): Int = (angleLow and 0xFF) + if (lastFlag and 0xFF == 2) 256 else 0

    private fun bytes(vararg b: Int) = ByteArray(b.size) { b[it].toByte() }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF shl 8) or (b[at + 1].toInt() and 0xFF)
}
