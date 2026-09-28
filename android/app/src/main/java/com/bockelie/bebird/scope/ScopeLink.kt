// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.scope

import android.net.Network
import com.bockelie.bebird.proto.Protocol
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/** A UDP conversation between one local port and one scope port; the seam tests replace. */
interface ScopeLink {
    val localPort: Int
    val isClosed: Boolean

    /** @throws IOException if the datagram could not be sent. */
    fun send(data: ByteArray)

    /**
     * Receive one datagram into [buf]: its length, or -1 when nothing arrived within the
     * link's timeout. @throws IOException when closed, or on a transient error such as an
     * ICMP port unreachable; check [isClosed] to tell them apart.
     */
    fun receive(buf: ByteArray): Int

    /** The sender's IP address of the datagram the last [receive] returned. */
    val lastSource: String?

    fun close()
}

interface LinkFactory {
    /** A link from [localPort] (0: any) to the camera's [remotePort], with a receive timeout. */
    fun open(localPort: Int, remotePort: Int, timeoutMs: Int): ScopeLink

    /** Receive-only: datagrams from anyone to [localPort], broadcasts included (the beacon). */
    fun listen(localPort: Int, timeoutMs: Int): ScopeLink
}

/** Real links: sockets pinned to the scope's [network] with [Network.bindSocket]. */
class NetworkLinks(private val network: Network) : LinkFactory {
    private val camera = InetAddress.getByName(Protocol.CAMERA_HOST)  // a literal: no DNS lookup

    override fun open(localPort: Int, remotePort: Int, timeoutMs: Int): ScopeLink = socket(localPort, timeoutMs) {
        it.connect(camera, remotePort)  // after bindSocket, which refuses a connected socket
        if (localPort == Protocol.CLIENT_VIDEO_PORT) it.receiveBufferSize = 5 shl 20
    }

    override fun listen(localPort: Int, timeoutMs: Int): ScopeLink = socket(localPort, timeoutMs) {
        it.broadcast = true
    }

    private fun socket(localPort: Int, timeoutMs: Int, setup: (DatagramSocket) -> Unit): ScopeLink {
        val s = DatagramSocket(null)
        try {
            // Reuse lets 58081 be bound while the last session's socket is still closing.
            s.reuseAddress = true
            s.bind(InetSocketAddress(localPort))
            network.bindSocket(s)
            setup(s)
            s.soTimeout = timeoutMs
        } catch (e: Exception) {
            s.close()
            throw e
        }
        return SocketLink(s)
    }

    private class SocketLink(private val s: DatagramSocket) : ScopeLink {
        private val packet = DatagramPacket(ByteArray(0), 0)
        override val localPort get() = s.localPort
        override val isClosed get() = s.isClosed
        override val lastSource: String? get() = packet.address?.hostAddress

        override fun send(data: ByteArray) = s.send(DatagramPacket(data, data.size))

        override fun receive(buf: ByteArray): Int {
            packet.setData(buf)
            return try {
                s.receive(packet)
                packet.length
            } catch (_: SocketTimeoutException) {
                -1
            }
        }

        override fun close() = s.close()
    }
}
