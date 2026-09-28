// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.scope

import com.bockelie.bebird.proto.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.PortUnreachableException
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The session's hard rules, against fake links and real threads with shortened timings. */
class ScopeSessionTest {
    private data class Sent(val link: Int, val localPort: Int, val remotePort: Int, val bytes: List<Byte>)

    private class FakeLink(val id: Int, override val localPort: Int, val remotePort: Int, private val log: MutableList<Sent>) : ScopeLink {
        val incoming = LinkedBlockingQueue<Any>()  // ByteArray, or an IOException to throw
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
                else -> (x as ByteArray).let { it.copyInto(buf); it.size }
            }
        }

        override fun close() { isClosed = true }
    }

    private val log = mutableListOf<Sent>()
    private val opened = mutableListOf<FakeLink>()
    private val links = object : LinkFactory {
        override fun open(localPort: Int, remotePort: Int, timeoutMs: Int) = synchronized(opened) {
            FakeLink(opened.size, if (localPort == 0) 40000 else localPort, remotePort, log).also { opened += it }
        }

        override fun listen(localPort: Int, timeoutMs: Int) = open(localPort, -1, timeoutMs)
    }
    private val videoOps = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val realClock = { System.nanoTime() / 1_000_000 }

    @After fun tearDown() {
        scope.cancel()
        videoOps.shutdownNow()
        // Whatever a test did, the session only ever said START, STOP or battery; never 66 3F / 66 3E.
        val allowed = listOf(Protocol.START, Protocol.STOP, Protocol.BATTERY).map { it.toList() }
        synchronized(log) { log.forEach { assertTrue("sent ${it.bytes}", it.bytes in allowed) } }
    }

    private fun session(
        timing: ScopeSession.Timing = ScopeSession.Timing(preStartMs = 20, tickMs = 20, retryMs = 60_000),
        clock: () -> Long = realClock,
    ) = ScopeSession(links, scope, videoOps, timing, clock, decode = { null })

    private fun videoSends() = synchronized(log) { log.filter { it.remotePort == Protocol.DATA_PORT } }
    private fun starts() = videoSends().count { it.bytes == Protocol.START.toList() }
    /** The most recently opened video link. */
    private fun video() = synchronized(opened) { opened.last { it.remotePort == Protocol.DATA_PORT } }

    /** A one-packet frame; the stubbed decoder refuses it, so it shows up as undecodable. */
    private fun frame(): ByteArray =
        byteArrayOf(7, 1, 1, 0, 0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())

    private fun await(what: String, timeoutMs: Long = 2000, cond: () -> Boolean) {
        val until = System.nanoTime() + timeoutMs * 1_000_000
        while (!cond()) {
            if (System.nanoTime() > until) throw AssertionError("timed out waiting for $what")
            Thread.sleep(2)
        }
    }

    @Test fun stopThenExactlyOneStartThenStop_allFrom58081() {
        val s = session()
        s.start()
        await("START") { starts() == 1 }
        Thread.sleep(200)  // several keepalive ticks
        s.stop().get(1, TimeUnit.SECONDS)

        val sends = videoSends()
        assertEquals(listOf(Protocol.STOP, Protocol.START, Protocol.STOP).map { it.toList() }, sends.map { it.bytes })
        assertTrue(sends.all { it.localPort == Protocol.CLIENT_VIDEO_PORT })
        // the keepalive goes to the command port, never to 58080
        assertTrue(synchronized(log) { log.any { it.remotePort == Protocol.COMMAND_PORT && it.bytes == Protocol.BATTERY.toList() } })
    }

    @Test fun noStartAfterStop_evenDuringThePreStartDelay() {
        val s = session(ScopeSession.Timing(preStartMs = 300, tickMs = 20, retryMs = 60_000))
        s.start()
        await("first STOP") { videoSends().isNotEmpty() }
        s.stop().get(1, TimeUnit.SECONDS)
        Thread.sleep(500)
        assertEquals(0, starts())
        assertEquals(listOf(Protocol.STOP, Protocol.STOP).map { it.toList() }, videoSends().map { it.bytes })
    }

    @Test fun stopBeforeStartSendsNothing() {
        val s = session()
        s.stop().get(1, TimeUnit.SECONDS)
        s.start()
        Thread.sleep(200)
        assertTrue(videoSends().isEmpty())
    }

    @Test fun noRetryOncePacketsArrive() {
        // A hand-driven clock: the retry deadline passes only when the test says so.
        val now = AtomicLong(0)
        val s = session(ScopeSession.Timing(preStartMs = 10, tickMs = 10, retryMs = 1000), clock = now::get)
        s.start()
        await("START") { starts() == 1 }
        video().incoming.put(frame())
        await("the packet") { s.stats.value.undecodable == 1 }
        now.set(60_000)  // far past the retry deadline
        Thread.sleep(100)  // many ticks
        assertEquals(1, starts())
        s.stop().get(1, TimeUnit.SECONDS)
    }

    @Test fun retriesWithoutPackets_onTheSameClock() {
        // the positive control for noRetryOncePacketsArrive: same setup, no packet
        val now = AtomicLong(0)
        val s = session(ScopeSession.Timing(preStartMs = 10, tickMs = 10, retryMs = 1000), clock = now::get)
        s.start()
        await("START") { starts() == 1 }
        Thread.sleep(50)
        assertEquals(1, starts())  // clock hasn't moved: no retry yet
        now.set(60_000)
        await("a retry") { starts() == 2 }
        s.stop().get(1, TimeUnit.SECONDS)
    }

    @Test fun oldStopGoesOutBeforeTheNextSessionsStart() {
        val a = session()
        a.start()
        await("A's START") { starts() == 1 }
        val linkA = video()
        linkA.sendDelayMs = 100  // a slow STOP, so B's work would overtake it if it could
        a.stop()
        val b = session()
        b.start()
        await("B's START") { starts() == 2 }
        b.stop().get(1, TimeUnit.SECONDS)

        val sends = videoSends()
        val aStop = sends.indexOfLast { it.link == linkA.id && it.bytes == Protocol.STOP.toList() }
        val firstB = sends.indexOfFirst { it.link != linkA.id }
        assertTrue("A's STOP at $aStop, B's first send at $firstB", aStop in 0 until firstB)
        assertEquals(listOf(Protocol.STOP, Protocol.START, Protocol.STOP).map { it.toList() },
            sends.filter { it.link != linkA.id }.map { it.bytes })
    }

    @Test fun onlyStartStopAndBatteryAreSent() {
        // a busy session: retries, then video, then stop (tearDown checks every test the same way)
        val s = session(ScopeSession.Timing(preStartMs = 10, tickMs = 10, retryGapMs = 5, retryMs = 30))
        s.start()
        await("a retry") { starts() == 2 }
        video().incoming.put(frame())
        await("the frame") { s.stats.value.undecodable == 1 }
        s.stop().get(1, TimeUnit.SECONDS)
        val allowed = listOf(Protocol.START, Protocol.STOP, Protocol.BATTERY).map { it.toList() }
        val sent = synchronized(log) { log.map { it.bytes } }
        assertTrue(sent.isNotEmpty())
        assertEquals(emptyList<List<Byte>>(), sent.filter { it !in allowed })
    }

    @Test fun retriesStopThenStartWhileNoVideo_capped() {
        val s = session(ScopeSession.Timing(preStartMs = 10, tickMs = 10, retryGapMs = 5, retryMs = 30))
        s.start()
        await("retries") { starts() == 1 + VideoWatchdog.MAX_RETRIES }
        Thread.sleep(200)
        s.stop().get(1, TimeUnit.SECONDS)
        val bytes = videoSends().map { it.bytes }
        assertEquals(1 + VideoWatchdog.MAX_RETRIES, bytes.count { it == Protocol.START.toList() })
        // every START directly follows a STOP, and the last word is STOP
        bytes.forEachIndexed { i, b -> if (b == Protocol.START.toList()) assertEquals(Protocol.STOP.toList(), bytes[i - 1]) }
        assertEquals(Protocol.STOP.toList(), bytes.last())
    }

    @Test fun transientReceiveErrorsDoNotEndTheLoop() {
        val s = session()
        s.start()
        await("START") { starts() == 1 }
        video().incoming.put(PortUnreachableException("ICMP port unreachable"))
        video().incoming.put(frame())
        // decode is stubbed to refuse, so a received frame shows up as undecodable
        await("frame after the error") { s.stats.value.undecodable == 1 }
        s.stop().get(1, TimeUnit.SECONDS)
    }

    @Test fun theFirstBeaconNamesTheScope() {
        val s = session()
        s.start()
        await("START") { starts() == 1 }
        val beacon = synchronized(opened) { opened.single { it.localPort == Protocol.BEACON_PORT } }
        beacon.incoming.put("""{"brand":"bebird","model":"ES","mac":"aa:bb:cc:00:11:22","ssid":"bebird-ES-123456"}""".toByteArray())
        await("beacon") { s.stats.value.beacon != null }
        assertEquals("bebird-ES-123456", s.stats.value.beacon?.ssid)
        assertEquals("aa:bb:cc:00:11:22", s.stats.value.beacon?.mac)
        await("beacon link closed after the first one") { beacon.isClosed }
        s.stop().get(1, TimeUnit.SECONDS)
    }

    @Test fun releaseWaitsForStop() {
        val s = session()
        s.start()
        await("START") { starts() == 1 }
        video().sendDelayMs = 200  // a slow STOP
        var stopSeenAtRelease = false
        afterStop(s.stop(), timeoutMs = 2000) {
            stopSeenAtRelease = videoSends().last().bytes == Protocol.STOP.toList() && video().isClosed
        }.join(3000)
        assertTrue(stopSeenAtRelease)
    }

    @Test fun releaseHappensEvenIfStopHangs() {
        val s = session()
        s.start()
        await("START") { starts() == 1 }
        video().sendDelayMs = 5000  // a dead link
        var released = false
        val t0 = System.nanoTime()
        afterStop(s.stop(), timeoutMs = 100) { released = true }.join(2000)
        assertTrue(released)
        assertTrue((System.nanoTime() - t0) / 1_000_000 < 1000)
    }
}
