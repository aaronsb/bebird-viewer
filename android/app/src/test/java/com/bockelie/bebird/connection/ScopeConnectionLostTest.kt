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
import com.bockelie.bebird.wifi.WifiControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
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
 * A lost network (#37), with real sessions over fake links: ScopeWifi has released the request
 * itself, so the connection ends the session without STOP, doesn't release again, wants nothing
 * more, and one Connect files the remembered exact request afresh.
 */
class ScopeConnectionLostTest {
    private val links = FakeLinks()

    private inner class FakeWifi : WifiControl {
        override val state = MutableStateFlow<ScopeWifi.State>(ScopeWifi.State.Idle)
        override val identity = MutableStateFlow<ScopeWifi.Identity?>(null)
        val starts = CopyOnWriteArrayList<Target>()
        val stops = AtomicInteger()
        @Volatile var releaseMs = 0L  // how long the network takes to go
        override fun start(target: Target, why: String?) {
            state.value = ScopeWifi.State.Requesting  // before the test sees the request and makes it Available
            starts += target
        }
        override fun stop() {
            Thread.sleep(releaseMs)
            stops.incrementAndGet()
            state.value = ScopeWifi.State.Idle
        }
        override fun scopesInRange(): List<ScopeWifi.Identity>? = null
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

    init {
        onMain { conn.onLetGo = { letGo.incrementAndGet() } }
    }

    @After fun tearDown() {
        onMain { conn.disconnect() }
        Thread.sleep(50)
        scope.cancel()
        mainThread.shutdownNow()
        videoOps.shutdownNow()
        links.sends().forEach { assertTrue("sent ${it.bytes}", FakeLinks.allowed(it.bytes)) }
    }

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(main) { block() } }

    private fun join() {
        val before = wifi.starts.size
        onMain { conn.connect() }
        await("the request") { wifi.starts.size > before }  // after connect()'s own release
        onMain { wifi.state.value = ScopeWifi.State.Available(blank<Network>(), exact) }
        await("START") { links.starts() > before }
    }

    private fun stream() {
        links.last(Protocol.DATA_PORT).incoming.put(frame())
        await("streaming") { conn.isStreaming.value }
        await("a picture") { conn.stats.value.frame != null }
    }

    /** What ScopeWifi reports once it has released the request after onLost. */
    private fun lose() = onMain { wifi.state.value = ScopeWifi.State.Lost }

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
        assertEquals("stopped", conn.stats.value.status)
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
        onMain { conn.disconnect() }
        lose()
        await("the release", 3000) { wifi.state.value == ScopeWifi.State.Idle }
        Thread.sleep(100)
        assertEquals(ScopeWifi.State.Idle, wifi.state.value)  // NOT CONNECTED, not CONNECTION LOST
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
        await("the new request", 3000) { wifi.starts.size > requests }
        assertTrue(conn.isWanted)
        assertEquals(0, letGo.get())
        onMain { wifi.state.value = ScopeWifi.State.Available(blank<Network>(), exact) }
        await("START") { links.starts() == 2 }
    }

    @Test fun aLossBeforeAnySessionChangesNothing() {
        lose()
        Thread.sleep(100)
        assertEquals(0, letGo.get())
        assertEquals(0, wifi.stops.get())
        assertTrue(links.sends().isEmpty())
    }
}
