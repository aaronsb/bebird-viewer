// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.scope

import com.bockelie.bebird.proto.Protocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.PortUnreachableException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** The session's hard rules, against fake links and real threads with shortened timings. */
class ScopeSessionTest {
    private val links = FakeLinks()
    private val log = links.log
    private val opened = links.opened
    private val videoOps = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val realClock = { System.nanoTime() / 1_000_000 }

    // Set by the tests that call powerOff(): the only ones that may send 66 3E.
    private var poweringOff = false

    @After fun tearDown() {
        scope.cancel()
        videoOps.shutdownNow()
        // Whatever a test did, the session only ever sent documented commands: never 66 3F, and
        // 66 3E only when the test called powerOff().
        synchronized(log) {
            log.forEach { assertTrue("sent ${it.bytes}", allowed(it.bytes) || poweringOff && it.bytes == POWER_OFF) }
        }
    }

    private fun allowed(b: List<Byte>) = FakeLinks.allowed(b)

    private fun powerOff(s: ScopeSession): Future<*> {
        poweringOff = true
        return s.powerOff()
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
    private fun frame() = FakeLinks.frame()

    private fun await(what: String, timeoutMs: Long = 2000, cond: () -> Boolean) = FakeLinks.await(what, timeoutMs, cond)

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

    @Test fun onlyDocumentedCommandsAreSent() {
        // a busy session: retries, video, light, then stop (tearDown checks every test the same way)
        val s = session(ScopeSession.Timing(preStartMs = 10, tickMs = 10, retryGapMs = 5, retryMs = 30))
        s.start()
        await("a retry") { starts() == 2 }
        video().incoming.put(frame())
        await("the frame") { s.stats.value.undecodable == 1 }
        s.setLight(0)
        s.setLight(50)
        s.queryLight()
        await("light sent") { commandSends().size >= 5 }
        s.stop().get(1, TimeUnit.SECONDS)
        val sent = synchronized(log) { log.map { it.bytes } }
        assertTrue(sent.isNotEmpty())
        assertEquals(emptyList<List<Byte>>(), sent.filter { !allowed(it) })
    }

    private fun commandSends() = synchronized(log) {
        log.filter { it.remotePort == Protocol.COMMAND_PORT && it.bytes != Protocol.BATTERY.toList() }.map { it.bytes }
    }

    private fun command() = synchronized(opened) { opened.last { it.remotePort == Protocol.COMMAND_PORT } }

    @Test fun lightIsSetThenCommittedAndReadBack() {
        val s = session()
        val reports = java.util.concurrent.CopyOnWriteArrayList<Int>()
        scope.launch { s.lightReports.collect { reports += it } }
        s.start()
        await("START") { starts() == 1 }
        s.setLight(36)
        s.queryLight()
        await("light commands") { commandSends().size == 3 }
        assertEquals(listOf(listOf<Byte>(0x66, 0x3C, 36), listOf<Byte>(0x66, 0x3C, 0xFF.toByte()), listOf<Byte>(0x66, 0x3C, 0xFE.toByte())), commandSends())
        // a 1-byte reply is a light level; a repeat of the same value still arrives
        command().incoming.put(byteArrayOf(36))
        command().incoming.put(byteArrayOf(36))
        await("the replies") { reports.size == 2 }
        assertEquals(listOf(36, 36), reports.toList())
        s.stop().get(1, TimeUnit.SECONDS)
    }

    @Test fun commandsQueuedBeforeStopDoNotGoOutAfterIt() {
        // Light and keepalive commands already queued when stop() runs must be dropped on the
        // executor, not sent after STOP.
        val s = session(ScopeSession.Timing(preStartMs = 10, tickMs = 10, retryMs = 60_000))
        s.start()
        await("START") { starts() == 1 }
        val gate = CountDownLatch(1)
        videoOps.execute { gate.await() }  // hold the executor
        s.setLight(40)
        s.queryLight()
        Thread.sleep(100)                  // several keepalive ticks queue up behind it
        s.stop()
        val before = synchronized(log) { log.size }
        gate.countDown()
        s.stop().get(1, TimeUnit.SECONDS)
        Thread.sleep(100)
        val after = synchronized(log) { log.drop(before) }
        assertEquals(listOf(Protocol.STOP.toList()), after.map { it.bytes })
        assertEquals(Protocol.DATA_PORT, after.single().remotePort)
    }

    @Test fun noLightCommandAfterStop() {
        val s = session()
        s.start()
        await("START") { starts() == 1 }
        s.stop()        // STOP is queued on the executor...
        s.setLight(40)  // ...and a light command after it never goes out
        s.queryLight()
        Thread.sleep(200)
        assertEquals(emptyList<List<Byte>>(), commandSends())
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

    private fun beaconJson(ssid: String?, mac: String?) = buildString {
        append("""{"brand":"bebird","model":"ES"""")
        mac?.let { append(""","mac":"$it"""") }
        ssid?.let { append(""","ssid":"$it"""") }
        append("}")
    }.toByteArray()

    @Test fun theFirstGoodBeaconNamesTheScope() {
        val s = session()
        s.start()
        await("START") { starts() == 1 }
        val beacon = synchronized(opened) { opened.single { it.localPort == Protocol.BEACON_PORT } }
        beacon.incoming.put(beaconJson("bebird-ES-123456", "aa:bb:cc:00:11:22"))
        await("beacon") { s.stats.value.beacon != null }
        assertEquals("bebird-ES-123456", s.stats.value.beacon?.ssid)
        assertEquals("aa:bb:cc:00:11:22", s.stats.value.beacon?.mac)
        await("beacon link closed after the first one") { beacon.isClosed }
        s.stop().get(1, TimeUnit.SECONDS)
    }

    @Test fun badBeaconsAreIgnored() {
        val s = session()
        s.start()
        await("START") { starts() == 1 }
        val beacon = synchronized(opened) { opened.single { it.localPort == Protocol.BEACON_PORT } }
        val good = beaconJson("bebird-ES-123456", "aa:bb:cc:00:11:22")
        beacon.incoming.put(good to "192.168.5.100")                           // another phone on the open AP
        beacon.incoming.put(beaconJson("bebird-" + "x".repeat(30), "aa:bb:cc:00:11:22"))  // SSID over 32 bytes
        beacon.incoming.put(beaconJson("HomeWifi", "aa:bb:cc:00:11:22"))      // not a scope
        beacon.incoming.put(beaconJson("bebird-ES-123456", null))             // no MAC
        beacon.incoming.put(beaconJson("bebird-ES-123456", "…"))              // invalid MAC
        beacon.incoming.put(beaconJson(null, "aa:bb:cc:00:11:22"))            // no SSID
        Thread.sleep(200)
        assertEquals(null, s.stats.value.beacon)
        assertTrue(!beacon.isClosed)  // still listening
        beacon.incoming.put(good)
        await("the good beacon") { s.stats.value.beacon != null }
        assertEquals("bebird-ES-123456", s.stats.value.beacon?.ssid)
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

    @Test fun powerOffSendsStopThen663EAndNothingAfter() {
        val s = session()
        s.start()
        await("START") { starts() == 1 }
        Thread.sleep(100)  // several keepalive ticks
        powerOff(s).get(1, TimeUnit.SECONDS)
        s.setLight(40)     // too late: the session is stopped
        s.queryLight()
        s.stop()
        Thread.sleep(200)  // any tick still in flight would show up now

        val sends = synchronized(log) { log.toList() }
        assertEquals(1, sends.count { it.bytes == POWER_OFF })
        val tail = sends.takeLast(2)
        assertEquals(listOf(Protocol.STOP.toList(), POWER_OFF), tail.map { it.bytes })
        assertEquals(Protocol.CLIENT_VIDEO_PORT, tail[0].localPort)
        assertEquals(Protocol.DATA_PORT, tail[0].remotePort)
        assertEquals(Protocol.COMMAND_PORT, tail[1].remotePort)
        assertTrue(video().isClosed && command().isClosed)
        assertEquals(listOf(Protocol.STOP, Protocol.START, Protocol.STOP).map { it.toList() }, videoSends().map { it.bytes })
        assertEquals("powered off", s.stats.value.status)
    }

    @Test fun powerOffBeforeTheLinksOpenSendsNothing() {
        val s = session()
        powerOff(s).get(1, TimeUnit.SECONDS)
        s.start()
        Thread.sleep(200)
        assertEquals(emptyList<Sent>(), links.sends())
    }

    @Test fun powerOffAfterStopSendsNothing() {
        val s = session()
        s.start()
        await("START") { starts() == 1 }
        val stopped = s.stop()
        stopped.get(1, TimeUnit.SECONDS)
        val before = links.sends().size
        assertTrue(powerOff(s) === stopped)
        Thread.sleep(100)
        assertEquals(before, links.sends().size)
        assertTrue(links.sends().none { it.bytes == POWER_OFF })
    }

    @Test fun commandsQueuedBeforePowerOffDoNotGoOutAfterIt() {
        // Light and keepalive commands already queued when powerOff() runs are dropped on the
        // executor: STOP and 66 3E are the last two datagrams.
        val s = session(ScopeSession.Timing(preStartMs = 10, tickMs = 10, retryMs = 60_000))
        s.start()
        await("START") { starts() == 1 }
        val gate = CountDownLatch(1)
        videoOps.execute { gate.await() }  // hold the executor
        s.setLight(40)
        s.queryLight()
        Thread.sleep(100)                  // several keepalive ticks queue up behind it
        val done = powerOff(s)
        s.setLight(60)                     // and one more after it
        val before = synchronized(log) { log.size }
        gate.countDown()
        done.get(1, TimeUnit.SECONDS)
        Thread.sleep(100)
        val after = synchronized(log) { log.drop(before) }
        assertEquals(listOf(Protocol.STOP.toList(), POWER_OFF), after.map { it.bytes })
        assertEquals(listOf(Protocol.DATA_PORT, Protocol.COMMAND_PORT), after.map { it.remotePort })
    }

    @Test fun releaseWaitsForThePowerOffSends() {
        val s = session()
        s.start()
        await("START") { starts() == 1 }
        video().sendDelayMs = 100    // a slow STOP...
        command().sendDelayMs = 200  // ...and a slow 66 3E
        var seenAtRelease: List<Byte>? = null
        var closedAtRelease = false
        afterStop(powerOff(s), timeoutMs = 2000) {
            seenAtRelease = links.sends().last().bytes
            closedAtRelease = video().isClosed && command().isClosed
        }.join(3000)
        assertEquals(POWER_OFF, seenAtRelease)
        assertTrue(closedAtRelease)
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

    private companion object {
        val POWER_OFF = listOf<Byte>(0x66, 0x3E)
    }
}
