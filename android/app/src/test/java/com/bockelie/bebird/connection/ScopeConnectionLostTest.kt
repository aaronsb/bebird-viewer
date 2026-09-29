// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.connection

import android.graphics.Bitmap
import android.net.Network
import com.bockelie.bebird.devices.BookStore
import com.bockelie.bebird.devices.DeviceBook
import com.bockelie.bebird.proto.Protocol
import com.bockelie.bebird.scope.FakeLinks
import com.bockelie.bebird.scope.FakeLinks.Companion.await
import com.bockelie.bebird.scope.FakeLinks.Companion.frame
import com.bockelie.bebird.scope.ScopeSession
import com.bockelie.bebird.wifi.ScopeWifi
import com.bockelie.bebird.wifi.ScopeWifi.Target
import com.bockelie.bebird.wifi.RequestSlot
import com.bockelie.bebird.wifi.WifiControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * A lost network (#37), with real sessions over fake links and ScopeWifi's own bookkeeping
 * ([RequestSlot]): the request is released on the loss, so the connection ends the session
 * without STOP, doesn't release again, wants nothing more, and one Connect files the remembered
 * exact request afresh. A loss during a deliberate ending reads as Idle.
 */
class ScopeConnectionLostTest {
    private val links = FakeLinks()

    /** ScopeWifi without Android: the same [RequestSlot], with Android's callbacks played by the test. */
    private inner class FakeWifi : WifiControl {
        private val slot = RequestSlot<Any>()
        override val state = slot.state
        override val identity = slot.identity
        val starts = CopyOnWriteArrayList<Target>()
        val stops = AtomicInteger()
        @Volatile var releaseMs = 0L  // how long the network takes to go
        @Volatile private var cb: Any? = null  // the latest request's callback
        override fun start(target: Target, why: String?) {
            val c = Any()
            if (!slot.file(c) {}) return
            cb = c
            starts += target
        }
        override fun markEnding() = slot.markEnding()
        override fun stop() {
            Thread.sleep(releaseMs)
            stops.incrementAndGet()
            slot.release()
        }
        override fun scopesInRange(): List<ScopeWifi.Identity>? = null

        fun available(target: Target) = slot.update(cb!!, ScopeWifi.State.Available(blank<Network>(), target))
        /** Android's onLost for the latest request; true if it was current (ScopeWifi then unregisters). */
        fun lose(): Boolean = slot.lost(cb!!)
    }

    /** An instance of a framework class without running its (stubbed) constructor. */
    private inline fun <reified T> blank(): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
    }

    private val wifi = FakeWifi()
    private val store = object : BookStore {
        var book = DeviceBook().seen("bebird-ES-1", "12:34:56:78:9A:9F", 100)
        override fun load() = book
        override fun save(book: DeviceBook) { this.book = book }
    }
    private val exact = Target.Exact("bebird-ES-1", "12:34:56:78:9A:9F")
    private val videoOps = Executors.newSingleThreadExecutor()
    private val mainThread = Executors.newSingleThreadExecutor()
    private val main = mainThread.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val clock = { System.nanoTime() / 1_000_000 }
    private val bitmap = blank<Bitmap>()
    private val conn = onMain {
        ScopeConnection(wifi, store, scope, clock = clock) {
            ScopeSession(links, scope, videoOps, ScopeSession.Timing(preStartMs = 10, tickMs = 20, retryMs = 60_000), clock, decode = { bitmap })
        }
    }
    private val letGo = AtomicInteger()
    private var poweringOff = false  // the only test that may send 66 3E

    init {
        onMain { conn.onLetGo = { letGo.incrementAndGet() } }
    }

    @After fun tearDown() {
        onMain { conn.disconnect() }
        Thread.sleep(50)
        scope.cancel()
        mainThread.shutdownNow()
        videoOps.shutdownNow()
        links.sends().forEach { assertTrue("sent ${it.bytes}", FakeLinks.allowed(it.bytes) || poweringOff && it.bytes == listOf<Byte>(0x66, 0x3E)) }
    }

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(main) { block() } }

    private fun join() {
        val before = wifi.starts.size
        onMain { conn.connect() }
        await("the request") { wifi.starts.size > before }  // after connect()'s own release
        wifi.available(exact)
        await("START") { links.starts() > before }
    }

    private fun stream() {
        links.last(Protocol.DATA_PORT).incoming.put(frame())
        await("streaming") { conn.isStreaming.value }
        await("a picture") { conn.stats.value.frame != null }
    }

    private fun lose() = assertTrue(wifi.lose())

    private fun videoSendsSince(mark: Int) = links.sends().drop(mark).filter { it.remotePort == Protocol.DATA_PORT }

    @Test fun aLossEndsTheSessionWithoutStopOrASecondRelease() {
        join()
        stream()
        val stops = wifi.stops.get()
        val mark = links.sends().size
        lose()
        await("the session ended") { !conn.isStreaming.value }
        await("the links closed") { synchronized(links.opened) { links.opened.all { it.isClosed } } }
        Thread.sleep(150)
        assertTrue(videoSendsSince(mark).isEmpty())  // no STOP over a network that's gone
        assertEquals(stops, wifi.stops.get())         // ScopeWifi released it already
        assertEquals(ScopeWifi.State.Lost, wifi.state.value)  // the marker stays for the circle
        assertNull(conn.stats.value.frame)  // what ends a recording in progress
        assertEquals("connection lost", conn.stats.value.status)
        assertFalse(conn.isWanted)
        assertEquals(1, letGo.get())
        assertEquals("bebird-ES-1", conn.book.value.last?.ssid)  // still remembered
    }

    @Test fun oneConnectAfterALossRequestsTheRememberedDeviceAgain() {
        join()
        stream()
        lose()
        await("the session ended") { !conn.isStreaming.value }
        val requests = wifi.starts.size
        join()
        assertEquals(requests + 1, wifi.starts.size)
        assertEquals(exact, wifi.starts.last())
        stream()
        assertEquals(2, links.starts())
    }

    @Test fun reconnectAndSelectDoNothingAfterALoss() {
        join()
        stream()
        lose()
        await("the session ended") { !conn.isStreaming.value }
        val requests = wifi.starts.size
        onMain {
            conn.reconnect()
            conn.select(conn.book.value.last!!)
        }
        Thread.sleep(150)
        assertEquals(requests, wifi.starts.size)
        assertEquals(1, links.starts())
        assertEquals(ScopeWifi.State.Lost, wifi.state.value)
    }

    @Test fun aLossAfterADeliberateDisconnectIsIgnored() {
        join()
        stream()
        wifi.releaseMs = 300  // the network goes before the release does
        val stops = wifi.stops.get()
        onMain { conn.disconnect() }
        lose()
        assertEquals(ScopeWifi.State.Idle, wifi.state.value)  // at once: no CONNECTION LOST flash
        assertEquals(stops, wifi.stops.get())                // before the gate's release
        await("the release", 3000) { wifi.stops.get() == stops + 1 }
        Thread.sleep(100)
        assertEquals(ScopeWifi.State.Idle, wifi.state.value)  // NOT CONNECTED
        assertEquals(0, letGo.get())
        assertEquals(Protocol.STOP.toList(), links.videoSends().last().bytes)  // disconnect's STOP
    }

    @Test fun aLossOfTheOldNetworkDoesNotCancelANewConnect() {
        // Connect again just as the scope goes: the old request's loss arrives while the new
        // request waits on the gate, and must not cancel it.
        join()
        stream()
        wifi.releaseMs = 300
        val requests = wifi.starts.size
        onMain { conn.connect() }
        lose()
        assertEquals(ScopeWifi.State.Idle, wifi.state.value)  // the old request was ending
        await("the new request", 3000) { wifi.starts.size > requests }
        assertTrue(conn.isWanted)
        assertEquals(0, letGo.get())
        wifi.available(exact)
        await("START") { links.starts() == 2 }
    }

    @Test fun aLossJustBeforeConnectDoesNotCancelIt() {
        // Lost is published, and Connect runs before the connection has seen it: that loss is
        // the old request's, and the new one waiting on the gate goes ahead.
        join()
        stream()
        wifi.releaseMs = 300
        val requests = wifi.starts.size
        onMain {
            lose()
            conn.connect()
        }
        await("the new request", 3000) { wifi.starts.size > requests }
        assertTrue(conn.isWanted)
        assertEquals(0, letGo.get())
        wifi.available(exact)
        await("START") { links.starts() == 2 }
    }

    @Test fun aLossBeforeThePictureStillCounts() {
        // Requesting straight to Lost, Available never seen (the state flow is conflated): no
        // session to end, but the connection still lets go and the circle says so.
        onMain { conn.connect() }
        await("the request") { wifi.starts.isNotEmpty() }
        lose()
        await("let go") { letGo.get() == 1 }
        assertFalse(conn.isWanted)
        assertEquals(ScopeWifi.State.Lost, wifi.state.value)
        assertEquals(1, wifi.stops.get())  // connect()'s own release only
        assertTrue(links.sends().isEmpty())
        assertEquals("connection lost", conn.stats.value.status)  // the status line says so too
    }

    @Test fun aLossAfterPowerOffEndsAsNotConnected() {
        // After 66 3E the scope drops its network, maybe before the release
        poweringOff = true
        join()
        stream()
        wifi.releaseMs = 300
        val stops = wifi.stops.get()
        assertTrue(onMain { conn.powerOff() })
        lose()
        assertEquals(ScopeWifi.State.Idle, wifi.state.value)
        await("the release", 3000) { wifi.stops.get() == stops + 1 }
        assertEquals(ScopeWifi.State.Idle, wifi.state.value)
        assertEquals(0, letGo.get())
    }
}
