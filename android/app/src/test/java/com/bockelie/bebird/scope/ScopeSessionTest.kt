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

/** The session's hard rules, against fake links and real threads with shortened timings. */
class ScopeSessionTest {
    private data class Sent(val localPort: Int, val remotePort: Int, val bytes: List<Byte>)

    private class FakeLink(override val localPort: Int, val remotePort: Int, private val log: MutableList<Sent>) : ScopeLink {
        val incoming = LinkedBlockingQueue<Any>()  // ByteArray, or an IOException to throw
        @Volatile var sendDelayMs = 0L
        @Volatile override var isClosed = false

        override fun send(data: ByteArray) {
            if (isClosed) throw IOException("closed")
            if (sendDelayMs > 0) Thread.sleep(sendDelayMs)
            synchronized(log) { log += Sent(localPort, remotePort, data.toList()) }
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
    private val opened = mutableMapOf<Int, FakeLink>()  // by remote port
    private val links = LinkFactory { local, remote, _ ->
        FakeLink(if (local == 0) 40000 else local, remote, log).also { synchronized(opened) { opened[remote] = it } }
    }
    private val videoOps = Executors.newSingleThreadExecutor()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @After fun tearDown() {
        scope.cancel()
        videoOps.shutdownNow()
    }

    private fun session(timing: ScopeSession.Timing = ScopeSession.Timing(preStartMs = 20, tickMs = 20, retryMs = 60_000)) =
        ScopeSession(links, scope, videoOps, timing, clock = { System.nanoTime() / 1_000_000 }, decode = { null })

    private fun videoSends() = synchronized(log) { log.filter { it.remotePort == Protocol.DATA_PORT } }
    private fun starts() = videoSends().count { it.bytes == Protocol.START.toList() }
    private fun video() = synchronized(opened) { opened.getValue(Protocol.DATA_PORT) }

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
        val s = session(ScopeSession.Timing(preStartMs = 10, tickMs = 10, retryMs = 150))
        s.start()
        await("START") { starts() == 1 }
        video().incoming.put(byteArrayOf(1, 0, 1, 0, 0x55))  // a fragment: streaming, but no frame yet
        Thread.sleep(600)
        assertEquals(1, starts())
        s.stop().get(1, TimeUnit.SECONDS)
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
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())
        video().incoming.put(PortUnreachableException("ICMP port unreachable"))
        video().incoming.put(byteArrayOf(7, 1, 1, 0) + jpeg)  // one-packet frame
        // decode is stubbed to refuse, so a received frame shows up as undecodable
        await("frame after the error") { s.stats.value.undecodable == 1 }
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
