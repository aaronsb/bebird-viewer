// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.scope

import com.bockelie.bebird.proto.Protocol
import java.io.IOException
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

/** One datagram a fake link sent, in global order across all links. */
data class Sent(val link: Int, val localPort: Int, val remotePort: Int, val bytes: List<Byte>)

class FakeLink(val id: Int, override val localPort: Int, val remotePort: Int, private val log: MutableList<Sent>) : ScopeLink {
    // ByteArray (from the camera), Pair<ByteArray, String> (bytes, sender), or an IOException to throw
    val incoming = LinkedBlockingQueue<Any>()
    @Volatile override var lastSource: String? = null
    @Volatile var sendDelayMs = 0L
    @Volatile override var isClosed = false

    override fun send(data: ByteArray) {
        if (isClosed) throw IOException("closed")
        if (sendDelayMs > 0) Thread.sleep(sendDelayMs)
        synchronized(log) { log += Sent(id, localPort, remotePort, data.toList()) }
    }

    override fun receive(buf: ByteArray): Int {
        if (isClosed) throw IOException("closed")
        return when (val x = incoming.poll(20, TimeUnit.MILLISECONDS)) {
            null -> -1
            is IOException -> throw x
            is Pair<*, *> -> (x.first as ByteArray).let { lastSource = x.second as String; it.copyInto(buf); it.size }
            else -> (x as ByteArray).let { lastSource = Protocol.CAMERA_HOST; it.copyInto(buf); it.size }
        }
    }

    override fun close() { isClosed = true }
}

/** A [LinkFactory] of [FakeLink]s that records every send in [log]. */
class FakeLinks : LinkFactory {
    val log = mutableListOf<Sent>()
    val opened = mutableListOf<FakeLink>()

    override fun open(localPort: Int, remotePort: Int, timeoutMs: Int) = synchronized(opened) {
        FakeLink(opened.size, if (localPort == 0) 40000 else localPort, remotePort, log).also { opened += it }
    }

    override fun listen(localPort: Int, timeoutMs: Int) = open(localPort, -1, timeoutMs)

    fun sends(): List<Sent> = synchronized(log) { log.toList() }
    fun videoSends() = sends().filter { it.remotePort == Protocol.DATA_PORT }
    fun starts() = videoSends().count { it.bytes == Protocol.START.toList() }
    /** Light commands (66 3C ..) sent by any link. */
    fun lightSends() = sends().filter { it.bytes.size == 3 && it.bytes[0] == 0x66.toByte() && it.bytes[1] == 0x3C.toByte() }
    /** The most recently opened link to [remotePort]. */
    fun last(remotePort: Int) = synchronized(opened) { opened.last { it.remotePort == remotePort } }

    companion object {
        /** START, STOP, battery, and the light: `66 3C nn` (0-100), `66 3C FF` commit, `66 3C FE` query. */
        fun allowed(b: List<Byte>): Boolean {
            val u = b.map { it.toInt() and 0xFF }
            return b == Protocol.START.toList() || b == Protocol.STOP.toList() || b == Protocol.BATTERY.toList() ||
                (u.size == 3 && u[0] == 0x66 && u[1] == 0x3C && (u[2] <= 100 || u[2] == 0xFF || u[2] == 0xFE))
        }

        /** A one-packet frame (JPEG markers around two bytes). */
        fun frame(id: Int = 7): ByteArray =
            byteArrayOf(id.toByte(), 1, 1, 0, 0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())

        fun await(what: String, timeoutMs: Long = 2000, cond: () -> Boolean) {
            val until = System.nanoTime() + timeoutMs * 1_000_000
            while (!cond()) {
                if (System.nanoTime() > until) throw AssertionError("timed out waiting for $what")
                Thread.sleep(2)
            }
        }
    }
}
