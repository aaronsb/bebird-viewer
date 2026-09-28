// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.proto

/**
 * Reassembles JPEG frames from video datagrams, as viewer.py's Scope._run does.
 *
 * Each datagram: [frame id][last flag][index from 1][angle low byte] then JPEG data.
 * Payloads are joined in index order when the last packet arrives; a missing index drops
 * the frame, and a new frame id before the last packet discards the partial one.
 * Not thread-safe: feed it from the one receive loop.
 */
class FrameAssembler {
    /** A complete frame and the roll angle (0-359 degrees) from its last packet. */
    class Frame(val jpeg: ByteArray, val angle: Int)

    /** Counters for debugging, named as in viewer.py's stats. */
    var packets = 0; private set
    var done = 0; private set
    var dropped = 0; private set     // last packet arrived but an index was missing
    var superseded = 0; private set  // frame never got its last packet

    private val parts = HashMap<Int, ByteArray>()
    private var frameId: Int? = null

    /** Feed one datagram (the first [length] bytes of [packet]); returns a frame when one completes. */
    fun accept(packet: ByteArray, length: Int = packet.size): Frame? {
        require(length in 0..packet.size) { "length $length outside 0..${packet.size}" }
        if (length < HEADER + 1) return null  // header only, or runt
        packets++
        val id = packet[0].toInt() and 0xFF
        val last = packet[1].toInt() and 0xFF
        val index = packet[2].toInt() and 0xFF
        if (id != frameId) {
            if (parts.isNotEmpty()) superseded++
            parts.clear()
            frameId = id
        }
        parts[index] = packet.copyOfRange(HEADER, length)  // a repeated index replaces the earlier one
        if (last == 0) return null

        val n = parts.keys.max()
        var frame: Frame? = null
        if ((1..n).any { it !in parts }) {
            dropped++
        } else {
            val size = (1..n).sumOf { parts.getValue(it).size }
            val joined = ByteArray(size)
            var at = 0
            for (i in 1..n) parts.getValue(i).let { it.copyInto(joined, at); at += it.size }
            val jpg = closeJpeg(joined)
            if (jpg.size >= 2 && jpg[0] == FF && jpg[1] == SOI) {
                done++
                frame = Frame(jpg, Protocol.rollAngle(last, packet[3].toInt()))
            }
        }
        parts.clear()
        return frame
    }

    companion object {
        const val HEADER = 4
        private const val FF = 0xFF.toByte()
        private const val SOI = 0xD8.toByte()
        private const val EOI = 0xD9.toByte()

        /**
         * Trim a reassembled frame at its last end-of-image marker, or repair a missing one
         * (grab.py's close_jpeg). When the last packet is exactly full the scope can drop the
         * marker's second byte, leaving a trailing FF; then only D9 is appended, as the
         * official app does, since a whole FF D9 would leave the decoder a stray byte.
         */
        fun closeJpeg(jpg: ByteArray): ByteArray {
            var end = jpg.size - 2
            while (end >= 0 && !(jpg[end] == FF && jpg[end + 1] == EOI)) end--
            if (end > 0) return jpg.copyOf(end + 2)  // > 0, not >= 0, as in grab.py
            return if (jpg.isNotEmpty() && jpg.last() == FF) jpg + EOI else jpg + byteArrayOf(FF, EOI)
        }
    }
}
