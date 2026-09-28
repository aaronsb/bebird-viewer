// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.connection

import com.bockelie.bebird.devices.BookStore
import com.bockelie.bebird.devices.DeviceBook
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
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * ScopeConnection against a fake Wi-Fi, on a single "main" thread as in the app. No network
 * ever becomes available here, so no session starts.
 */
class ScopeConnectionTest {
    private class FakeWifi : WifiControl {
        override val state = MutableStateFlow<ScopeWifi.State>(ScopeWifi.State.Idle)
        override val identity = MutableStateFlow<ScopeWifi.Identity?>(null)
        val starts = CopyOnWriteArrayList<Target>()

        override fun start(target: Target, why: String?) {
            starts += target
            state.value = ScopeWifi.State.Requesting
        }

        // Leaves the state alone, so the connection's collector still sees a late Unavailable:
        // exactly the window the guard is for.
        override fun stop() {}

        override fun scopesInRange(): List<ScopeWifi.Identity>? = null
    }

    private val derived = "12:34:56:78:9A:9F"
    private val wifi = FakeWifi()
    private val store = object : BookStore {
        var book = DeviceBook().seen("bebird-ES-1", derived, 100)
        override fun load() = book
        override fun save(book: DeviceBook) { this.book = book }
    }
    private val mainThread = Executors.newSingleThreadExecutor()
    private val main = mainThread.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val conn = onMain { ScopeConnection(wifi, store, scope) { error("no session in these tests") } }

    @After fun tearDown() {
        scope.cancel()
        mainThread.shutdownNow()
    }

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(main) { block() } }

    private fun await(what: String, cond: () -> Boolean) {
        val until = System.nanoTime() + 2_000_000_000
        while (!cond()) {
            if (System.nanoTime() > until) throw AssertionError("timed out waiting for $what; starts ${wifi.starts}")
            Thread.sleep(2)
        }
    }

    private val exact = Target.Exact("bebird-ES-1", derived)
    private val fallback = Target.Exact("bebird-ES-1", null, suspect = derived)

    @Test fun connectAsksForTheLastDeviceExactly() {
        onMain { conn.connect() }
        await("the request") { wifi.starts.size == 1 }
        assertEquals(exact, wifi.starts.single())
    }

    @Test fun anUnavailableExactRequestFallsBackToTheSsidOnce() {
        onMain { conn.connect() }
        await("the request") { wifi.starts.size == 1 }
        onMain { wifi.state.value = ScopeWifi.State.Unavailable(exact) }
        await("the fallback") { wifi.starts.size == 2 }
        assertEquals(fallback, wifi.starts[1])
        onMain { wifi.state.value = ScopeWifi.State.Unavailable(fallback) }
        Thread.sleep(200)
        assertEquals(2, wifi.starts.size)  // no third try
    }

    @Test fun noFallbackAfterDisconnect() {
        // Unavailable arrives, then Disconnect (or onStop) runs before the collector gets to it
        onMain { conn.connect() }
        await("the request") { wifi.starts.size == 1 }
        onMain {
            wifi.state.value = ScopeWifi.State.Unavailable(exact)
            conn.disconnect()
        }
        Thread.sleep(300)
        assertEquals(listOf<Target>(exact), wifi.starts.toList())
    }

    @Test fun noStaleFallbackOverANewerRequest() {
        // the user picks a different device in the same gap: only that request is filed
        onMain { conn.connect() }
        await("the request") { wifi.starts.size == 1 }
        onMain {
            wifi.state.value = ScopeWifi.State.Unavailable(exact)
            conn.pickDifferent()
        }
        await("the picker request") { wifi.starts.size == 2 }
        Thread.sleep(300)
        assertEquals(listOf(exact, Target.AnyScope), wifi.starts.toList())
    }
}
