// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.scope

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Network
import android.os.SystemClock
import android.util.Log
import com.bockelie.bebird.proto.FrameAssembler
import com.bockelie.bebird.proto.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * One video session with the scope over [network], mirroring viewer.py's Scope: STOP then a
 * single START from local port 58081, the battery poll once a second as the keepalive, and
 * STOP from the same port when done. One-shot: after [stop], make a new session.
 */
class ScopeSession(private val network: Network, private val scope: CoroutineScope) {
    data class Stats(
        val frame: Bitmap? = null,
        val angle: Int = 0,
        val fps: Int = 0,
        val battery: Protocol.Battery? = null,
        val frames: Int = 0,
        val dropped: Int = 0,     // FrameAssembler dropped + superseded
        val undecodable: Int = 0, // reassembled but BitmapFactory refused it
        val status: String = "starting",
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    // Guards stopped and the sockets, so a START can never go out after our STOP.
    private val lock = Any()
    private var stopped = false
    private var video: DatagramSocket? = null
    private var ctrl: DatagramSocket? = null
    private var job: Job? = null

    private val camera = InetAddress.getByName(Protocol.CAMERA_HOST)  // a literal: no DNS lookup
    // Fed by the receive loop only; the 1 s tick reads its counters for the log, unsynchronised.
    private val assembler = FrameAssembler()
    private val frameCount = AtomicInteger()  // frames since the last fps tick

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            try {
                if (open()) stream()
            } catch (e: IOException) {
                Log.e(TAG, "session failed", e)
                _stats.update { it.copy(status = "error: ${e.message}") }
            }
        }
    }

    /** Send STOP from 58081 and close the sockets. Safe from any thread, including main. */
    fun stop() {
        val v: DatagramSocket?
        val c: DatagramSocket?
        synchronized(lock) {
            if (stopped) return
            stopped = true
            v = video
            c = ctrl
        }
        job?.cancel()
        // Sockets can't be used on the main thread (NetworkOnMainThreadException).
        thread(name = "bebird-stop") {
            v?.let {
                val sent = send(it, Protocol.STOP)
                Log.i(TAG, if (sent) "STOP sent" else "STOP could not be sent (network gone?)")
                it.close()
            }
            c?.close()
        }
        _stats.update { it.copy(status = "stopped", fps = 0) }
    }

    private fun open(): Boolean = synchronized(lock) {
        if (stopped) return false
        val v = udp(Protocol.CLIENT_VIDEO_PORT, Protocol.DATA_PORT).apply {
            receiveBufferSize = 5 shl 20
            soTimeout = 100
        }
        val c = try {
            udp(0, Protocol.COMMAND_PORT).apply { soTimeout = 200 }
        } catch (e: IOException) {
            v.close()
            throw e
        }
        video = v
        ctrl = c
        Log.i(TAG, "sockets bound to $network: video :${v.localPort}, command :${c.localPort}")
        true
    }

    /**
     * A UDP socket on [localPort] (0: any), pinned to the scope's network and connected to the
     * camera's [remotePort]. Reuse lets 58081 be bound while the last session's socket closes.
     */
    private fun udp(localPort: Int, remotePort: Int): DatagramSocket {
        val s = DatagramSocket(null)
        try {
            s.reuseAddress = true
            s.bind(InetSocketAddress(localPort))
            network.bindSocket(s)  // must come before connect
            s.connect(camera, remotePort)
        } catch (e: IOException) {
            s.close()
            throw e
        }
        return s
    }

    private suspend fun stream() = coroutineScope {
        val v = video!!
        val c = ctrl!!
        launch { receiveCommands(c) }

        send(v, Protocol.STOP)  // clear any session left on our port
        delay(500)              // viewer.py spends ~0.6 s here collecting board info
        val watchdog = VideoWatchdog(SystemClock.elapsedRealtime())  // guarded by itself
        launch { receiveVideo(v, watchdog) }
        if (!sendStart(v)) {
            _stats.update { it.copy(status = "START could not be sent") }
            return@coroutineScope
        }
        _stats.update { it.copy(status = "waiting for video") }

        var stalled = false
        while (isActive) {
            send(c, Protocol.BATTERY)  // the keepalive: without it video stops within ~1 s
            delay(1000)
            val now = SystemClock.elapsedRealtime()
            val fps = frameCount.getAndSet(0)
            val (retry, isStalled) = synchronized(watchdog) { watchdog.shouldRetryStart(now) to watchdog.stalled(now) }
            Log.i(TAG, "fps $fps, frames ${assembler.done}, dropped ${assembler.dropped}, superseded ${assembler.superseded}, packets ${assembler.packets}")
            _stats.update { it.copy(fps = fps) }
            if (retry) {
                // Only before the first frame, as viewer.py and the official app do.
                Log.w(TAG, "no video yet: STOP, then START again (retry ${watchdog.retries})")
                send(v, Protocol.STOP)
                delay(100)
                sendStart(v)
            }
            if (isStalled != stalled) {
                stalled = isStalled
                Log.w(TAG, if (stalled) "video stopped: scope off or wedged" else "video resumed")
                _stats.update { it.copy(status = if (stalled) "video stopped (power-cycle the scope?)" else "streaming") }
            }
        }
    }

    private fun sendStart(v: DatagramSocket): Boolean = synchronized(lock) {
        if (stopped) return false
        val sent = send(v, Protocol.START)
        Log.i(TAG, if (sent) "START sent" else "START could not be sent")
        sent
    }

    private fun CoroutineScope.receiveVideo(v: DatagramSocket, watchdog: VideoWatchdog) {
        val buf = ByteArray(65536)
        val packet = DatagramPacket(buf, buf.size)
        var first = true
        while (isActive && !v.isClosed) {
            packet.setData(buf)  // resets the length to the whole buffer
            try {
                v.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                break  // closed by stop(), or the network went away
            }
            val now = SystemClock.elapsedRealtime()
            synchronized(watchdog) { watchdog.onPacket(now) }
            val frame = assembler.accept(buf, packet.length) ?: continue
            val bitmap = BitmapFactory.decodeByteArray(frame.jpeg, 0, frame.jpeg.size)
            if (bitmap == null) {
                _stats.update { it.copy(undecodable = it.undecodable + 1) }
                continue
            }
            frameCount.incrementAndGet()
            synchronized(watchdog) { watchdog.onFrame(now) }
            if (first) Log.i(TAG, "first frame: ${bitmap.width}x${bitmap.height}, ${frame.jpeg.size} bytes, angle ${frame.angle}")
            _stats.update {
                it.copy(
                    frame = bitmap, angle = frame.angle, frames = assembler.done,
                    dropped = assembler.dropped + assembler.superseded,
                    status = if (first) "streaming" else it.status,
                )
            }
            first = false
        }
    }

    private fun CoroutineScope.receiveCommands(c: DatagramSocket) {
        val buf = ByteArray(4096)
        val packet = DatagramPacket(buf, buf.size)
        while (isActive && !c.isClosed) {
            packet.setData(buf)
            try {
                c.receive(packet)
            } catch (_: SocketTimeoutException) {
                continue
            } catch (_: IOException) {
                break
            }
            Protocol.decodeBattery(buf.copyOf(packet.length))?.let { b ->
                if (_stats.value.battery != b) Log.i(TAG, "battery ${b.percent}% (${b.stateName})")
                _stats.update { it.copy(battery = b) }
            }
        }
    }

    private fun send(s: DatagramSocket, data: ByteArray): Boolean =
        try {
            s.send(DatagramPacket(data, data.size))
            true
        } catch (_: IOException) {
            false
        }

    companion object {
        private const val TAG = "BebirdSpike"
    }
}
