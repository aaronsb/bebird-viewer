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
import com.bockelie.bebird.scope.Sent
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Power off through ScopeConnection, with real sessions over fake links: only while streaming,
 * the network released only after STOP and 66 3E, and the device still remembered.
 */
class ScopeConnectionPowerOffTest {
    private val links = FakeLinks()

    private inner class FakeWifi : WifiControl {
        override val state = MutableStateFlow<ScopeWifi.State>(ScopeWifi.State.Idle)
        override val identity = MutableStateFlow<ScopeWifi.Identity?>(null)
        /** What the fake links had sent at each release. */
        val releases = CopyOnWriteArrayList<List<Sent>>()
        val starts = CopyOnWriteArrayList<Target>()
        override fun start(target: Target, why: String?) { starts += target }
        @Volatile var releaseMs = 0L  // how long the network takes to go
        override fun stop() {
            releases += links.sends()
            Thread.sleep(releaseMs)
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
    private var poweringOff = false  // the only tests that may send 66 3E

    @After fun tearDown() {
        onMain { conn.disconnect() }
        Thread.sleep(50)
        scope.cancel()
        mainThread.shutdownNow()
        videoOps.shutdownNow()
        links.sends().forEach { assertTrue("sent ${it.bytes}", FakeLinks.allowed(it.bytes) || poweringOff && it.bytes == POWER_OFF) }
    }

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(main) { block() } }

    private fun join() {
        onMain { conn.connect() }
        await("the request") { wifi.starts.isNotEmpty() }  // after connect()'s own release
        wifi.releases.clear()
        onMain { wifi.state.value = ScopeWifi.State.Available(blank<Network>(), Target.AnyScope) }
        await("START") { links.starts() >= 1 }
    }

    private fun stream() {
        links.last(Protocol.DATA_PORT).incoming.put(frame())
        await("streaming") { conn.isStreaming.value }
    }

    private fun powerOff(): Boolean {
        poweringOff = true
        return onMain { conn.powerOff() }
    }

    @Test fun powerOffWhileStreaming_stopThen663EThenRelease() {
        join()
        stream()
        links.last(Protocol.COMMAND_PORT).sendDelayMs = 200  // a slow 66 3E: the release must wait for it
        assertTrue(powerOff())
        await("the release") { wifi.releases.isNotEmpty() }
        Thread.sleep(100)

        val atRelease = wifi.releases.first()
        assertEquals(listOf(Protocol.STOP.toList(), POWER_OFF), atRelease.takeLast(2).map { it.bytes })
        assertEquals(listOf(Protocol.DATA_PORT, Protocol.COMMAND_PORT), atRelease.takeLast(2).map { it.remotePort })
        assertEquals(atRelease, links.sends())  // nothing after 66 3E
        assertEquals(ScopeWifi.State.Idle, wifi.state.value)
        assertFalse(conn.isStreaming.value)
        assertEquals("powered off", conn.stats.value.status)
        assertEquals("bebird-ES-1", conn.book.value.last?.ssid)  // still remembered

        // relaunched straight away: no connecting to a scope that is shutting down
        val requests = wifi.starts.size
        onMain { conn.connectOnLaunch() }
        Thread.sleep(100)
        assertEquals(requests, wifi.starts.size)
    }

    @Test fun nothingStartsBetween663EAndTheRelease() {
        // Reconnect, or the network coming back, while the release is still waiting for 66 3E.
        join()
        stream()
        links.last(Protocol.COMMAND_PORT).sendDelayMs = 300
        wifi.releaseMs = 300  // the network is still Available while this runs
        assertTrue(powerOff())
        onMain {
            conn.reconnect()
            wifi.state.value = ScopeWifi.State.Available(blank<Network>(), Target.AnyScope)
        }
        await("the release") { wifi.releases.isNotEmpty() }
        await("Idle") { wifi.state.value == ScopeWifi.State.Idle }
        Thread.sleep(100)
        assertEquals(1, links.starts())
        assertEquals(POWER_OFF, links.sends().last().bytes)
        assertEquals(wifi.releases.first(), links.sends())
    }

    @Test fun powerOffBeforeVideoSendsNothingAndKeepsTheConnection() {
        join()  // START sent, but no frame yet
        assertFalse(powerOff())
        Thread.sleep(150)
        assertTrue(links.sends().none { it.bytes == POWER_OFF })
        assertTrue(wifi.releases.isEmpty())
        assertTrue(wifi.state.value is ScopeWifi.State.Available)
        stream()  // the session carries on
    }

    @Test fun powerOffWhenNotConnectedSendsNothing() {
        assertFalse(powerOff())
        Thread.sleep(100)
        assertEquals(emptyList<Sent>(), links.sends())
        assertTrue(wifi.releases.isEmpty())
    }

    @Test fun aLightMoveJustBeforePowerOffDoesNotFollowIt() {
        join()
        stream()
        onMain {
            conn.setLight(60)
            conn.toggleLight()
            conn.powerOff().also { poweringOff = true }
        }
        await("the release") { wifi.releases.isNotEmpty() }
        Thread.sleep(200)
        assertEquals(POWER_OFF, links.sends().last().bytes)
    }

    private companion object {
        val POWER_OFF = listOf<Byte>(0x66, 0x3E)
    }
}
