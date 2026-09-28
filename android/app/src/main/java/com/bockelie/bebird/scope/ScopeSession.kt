// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.scope

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import com.bockelie.bebird.devices.WifiIds
import com.bockelie.bebird.proto.Beacon
import com.bockelie.bebird.proto.FrameAssembler
import com.bockelie.bebird.proto.Protocol
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * One video session with the scope, mirroring viewer.py's Scope: STOP then a single START
 * from local port 58081, the battery poll once a second as the keepalive, and STOP from the
 * same port when done. One-shot: after [stop], make a new session.
 *
 * Everything that opens, sends on or closes the :58081 link runs on [videoOps], one thread
 * shared by all sessions. Its FIFO order is what guarantees a STOP is never overtaken by a
 * START, within a session or from the next one.
 */
class ScopeSession(
    private val links: LinkFactory,
    private val scope: CoroutineScope,
    private val videoOps: ExecutorService = sharedVideoOps,
    private val timing: Timing = Timing(),
    private val clock: () -> Long = SystemClock::elapsedRealtime,
    private val decode: (ByteArray) -> Bitmap? = { BitmapFactory.decodeByteArray(it, 0, it.size) },
) {
    data class Timing(
        val preStartMs: Long = 500,   // viewer.py spends ~0.6 s here collecting board info
        val tickMs: Long = 1000,      // battery poll (the keepalive), fps, watchdog
        val retryGapMs: Long = 100,   // between STOP and START on a retry
        val retryMs: Long = VideoWatchdog.RETRY_MS,
    )

    data class Stats(
        val frame: Bitmap? = null,
        val angle: Int = 0,
        val fps: Int = 0,
        val battery: Protocol.Battery? = null,
        val frames: Int = 0,
        val dropped: Int = 0,     // FrameAssembler dropped + superseded
        val undecodable: Int = 0, // reassembled but the decoder refused it
        val status: String = "starting",
        val beacon: Beacon? = null,  // the first beacon heard: which scope this is
    )

    private val _stats = MutableStateFlow(Stats())
    val stats: StateFlow<Stats> = _stats.asStateFlow()

    // Every `66 3C FE` reply, including repeats of the same value (a StateFlow would merge them).
    private val _lightReports = MutableSharedFlow<Int>(extraBufferCapacity = 8)
    val lightReports: SharedFlow<Int> = _lightReports.asSharedFlow()

    private val stopped = AtomicBoolean(false)
    private val onVideoOps = videoOps.asCoroutineDispatcher()
    // Written on videoOps; read by the receive loops after the write (happens-before via withContext).
    @Volatile private var video: ScopeLink? = null
    @Volatile private var ctrl: ScopeLink? = null
    @Volatile private var beacon: ScopeLink? = null
    @Volatile private var job: Job? = null
    private var stopDone: Future<*>? = null

    // Fed by the receive loop only; the tick reads its counters for the log, unsynchronised.
    private val assembler = FrameAssembler()
    private val frameCount = AtomicInteger()  // frames since the last fps tick

    fun start() {
        job = scope.launch(Dispatchers.IO) {
            try {
                if (open()) stream()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // stop() is still the caller's job, and still sends STOP if the link opened.
                Log.e(TAG, "session failed", e)
                _stats.update { it.copy(status = "error: ${e.message ?: e.javaClass.simpleName}") }
            }
        }
    }

    /**
     * Send STOP from 58081 and close the links. Never blocks; the returned future completes
     * once STOP has gone out (or failed) and the links are closed. Release the network only
     * after that. Safe from any thread, and idempotent.
     */
    @Synchronized
    fun stop(): Future<*> {
        stopDone?.let { return it }
        stopped.set(true)
        job?.cancel()
        _stats.update { it.copy(status = "stopped", fps = 0) }
        return videoOps.submit {
            video?.let {
                val sent = send(it, Protocol.STOP)
                Log.i(TAG, if (sent) "STOP sent" else "STOP could not be sent (network gone?)")
                it.close()
            }
            ctrl?.close()
            beacon?.close()
        }.also { stopDone = it }
    }

    private suspend fun open(): Boolean = withContext(onVideoOps) {
        if (stopped.get()) return@withContext false
        val v = links.open(Protocol.CLIENT_VIDEO_PORT, Protocol.DATA_PORT, 100)
        video = v
        ctrl = links.open(0, Protocol.COMMAND_PORT, 200)  // if this throws, stop() still closes v
        // Optional: the beacon names the scope (SSID, MAC) without any location permission.
        beacon = try {
            links.listen(Protocol.BEACON_PORT, 200)
        } catch (e: Exception) {
            Log.w(TAG, "can't listen for the beacon on :${Protocol.BEACON_PORT}: $e")
            null
        }
        Log.i(TAG, "links open: video :${v.localPort}, command :${ctrl?.localPort}")
        true
    }

    private suspend fun stream() = coroutineScope {
        val v = video!!
        val c = ctrl!!
        launch { receiveCommands(c) }
        beacon?.let { b -> launch { receiveBeacon(b) } }

        onVideo(v, Protocol.STOP, "STOP (clear our port)")
        delay(timing.preStartMs)
        val watchdog = VideoWatchdog(clock(), retryMs = timing.retryMs)  // guarded by itself
        launch { receiveVideo(v, watchdog) }
        if (!onVideo(v, Protocol.START, "START")) {
            // Nothing more to do; the links stay open until stop(), which still sends STOP.
            _stats.update { it.copy(status = "START could not be sent") }
            cancel()
            return@coroutineScope
        }
        _stats.update { it.copy(status = "waiting for video") }

        var stalled = false
        while (isActive) {
            send(c, Protocol.BATTERY)  // the keepalive: without it video stops within ~1 s
            delay(timing.tickMs)
            val now = clock()
            val fps = frameCount.getAndSet(0)
            val (retry, isStalled) = synchronized(watchdog) { watchdog.shouldRetryStart(now) to watchdog.stalled(now) }
            Log.i(TAG, "fps $fps, frames ${assembler.done}, dropped ${assembler.dropped}, superseded ${assembler.superseded}, packets ${assembler.packets}")
            _stats.update { it.copy(fps = fps) }
            if (retry) {
                // Only before any video has arrived, as viewer.py and the official app do.
                Log.w(TAG, "no video yet: STOP, then START again (retry ${watchdog.retries})")
                onVideo(v, Protocol.STOP, "STOP (retry)")
                delay(timing.retryGapMs)
                onVideo(v, Protocol.START, "START (retry)")
            }
            if (isStalled != stalled) {
                stalled = isStalled
                Log.w(TAG, if (stalled) "video stopped: scope off or wedged" else "video resumed")
                _stats.update { it.copy(status = if (stalled) "video stopped (power-cycle the scope?)" else "streaming") }
            }
        }
    }

    /**
     * Set and commit the tip light's raw level (`66 3C raw`, `66 3C FF`) on the command link.
     * Like every command, it is queued on videoOps, so it can't slip in after STOP.
     */
    fun setLight(raw: Int) = command("light $raw", Protocol.lightCommands(raw))

    /** Ask for the light level (`66 3C FE`); the reply arrives on [lightReports]. */
    fun queryLight() = command("light query", listOf(Protocol.LIGHT_QUERY))

    private fun command(what: String, data: List<ByteArray>) {
        if (stopped.get()) return
        videoOps.execute {
            val c = ctrl ?: return@execute
            if (stopped.get()) return@execute
            val sent = data.all { send(c, it) }
            Log.i(TAG, if (sent) "$what sent" else "$what could not be sent")
        }
    }

    /** Send on the video link from videoOps, unless stopped by the time it runs. */
    private suspend fun onVideo(v: ScopeLink, data: ByteArray, what: String): Boolean = withContext(onVideoOps) {
        if (stopped.get()) return@withContext false
        send(v, data).also { Log.i(TAG, if (it) "$what sent" else "$what could not be sent") }
    }

    private fun CoroutineScope.receiveVideo(v: ScopeLink, watchdog: VideoWatchdog) {
        val buf = ByteArray(65536)
        var first = true
        while (isActive) {
            val n = receive(v, buf) ?: break
            if (n < 0) continue
            val now = clock()
            synchronized(watchdog) { watchdog.onPacket(now) }
            val frame = assembler.accept(buf, n) ?: continue
            val bitmap = decode(frame.jpeg)
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

    /**
     * Until the first usable beacon: that is all the session needs from it. The scope's network
     * is open, so anyone on it can broadcast to 58099; only the camera's own beacons count, and
     * only with a scope SSID and a valid MAC.
     */
    private fun CoroutineScope.receiveBeacon(b: ScopeLink) {
        val buf = ByteArray(2048)
        var ignored = 0
        while (isActive) {
            val n = receive(b, buf) ?: return
            if (n < 0) continue
            val beacon = Beacon.parse(buf, n)?.takeIf { b.lastSource == Protocol.CAMERA_HOST && usable(it) }
            if (beacon == null) {
                if (ignored++ < 3) Log.w(TAG, "ignoring datagram on :${Protocol.BEACON_PORT} from ${b.lastSource}")
                continue
            }
            Log.i(TAG, "beacon: ssid=${beacon.ssid} mac=${beacon.mac} model=${beacon.model}")
            _stats.update { it.copy(beacon = beacon) }
            b.close()
            return
        }
    }

    private fun usable(b: Beacon): Boolean {
        val ssid = WifiIds.ssid(b.ssid)
        return ssid != null && WifiIds.isScope(ssid) && WifiIds.bssid(b.mac) != null
    }

    private fun CoroutineScope.receiveCommands(c: ScopeLink) {
        val buf = ByteArray(4096)
        while (isActive) {
            val n = receive(c, buf) ?: break
            if (n < 0) continue
            val reply = buf.copyOf(n)
            Protocol.decodeBattery(reply)?.let { b ->
                if (_stats.value.battery != b) Log.i(TAG, "battery ${b.percent}% (${b.stateName})")
                _stats.update { it.copy(battery = b) }
            }
            Protocol.decodeLightLevel(reply)?.let { raw ->
                Log.i(TAG, "light reported: $raw")
                _lightReports.tryEmit(raw)
            }
        }
    }

    /**
     * One receive: the length, -1 for nothing yet, or null once the link is closed. Other
     * errors (an ICMP port unreachable while the scope's port isn't listening, a network
     * hiccup) are transient, as in viewer.py: pause briefly and carry on.
     */
    private fun receive(link: ScopeLink, buf: ByteArray): Int? =
        try {
            link.receive(buf)
        } catch (e: IOException) {
            if (link.isClosed) null else { Thread.sleep(20); -1 }
        }

    private fun send(link: ScopeLink, data: ByteArray): Boolean =
        try {
            link.send(data)
            true
        } catch (_: IOException) {
            false
        }

    companion object {
        private const val TAG = "BebirdSpike"

        /** The one thread that touches the :58081 link, for all sessions. */
        val sharedVideoOps: ExecutorService = Executors.newSingleThreadExecutor { r ->
            Thread(r, "bebird-58081").apply { isDaemon = true }
        }
    }
}
