// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.connection

import android.graphics.Bitmap
import android.net.Network
import com.bockelie.bebird.control.LightControl
import com.bockelie.bebird.devices.BookStore
import com.bockelie.bebird.devices.DeviceBook
import com.bockelie.bebird.proto.Protocol
import com.bockelie.bebird.scope.FakeLinks
import com.bockelie.bebird.scope.FakeLinks.Companion.await
import com.bockelie.bebird.scope.FakeLinks.Companion.frame
import com.bockelie.bebird.scope.ScopeSession
import com.bockelie.bebird.settings.MemoryKeyValue
import com.bockelie.bebird.settings.Settings
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
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Executors

/**
 * The light path through ScopeConnection, with real sessions over fake links: when light
 * commands may go out, and to which session. Shortened light timings; a single "main" thread.
 */
class ScopeConnectionLightTest {
    private class FakeWifi : WifiControl {
        override val state = MutableStateFlow<ScopeWifi.State>(ScopeWifi.State.Idle)
        override val identity = MutableStateFlow<ScopeWifi.Identity?>(null)
        override fun start(target: Target, why: String?) {}
        override fun markEnding() {}
        override fun stop() {}
        override fun scopesInRange(): List<ScopeWifi.Identity>? = null
    }

    /** An instance of a framework class without running its (stubbed) constructor. */
    private inline fun <reified T> blank(): T {
        val unsafeClass = Class.forName("sun.misc.Unsafe")
        val unsafe = unsafeClass.getDeclaredField("theUnsafe").apply { isAccessible = true }.get(null)
        return unsafeClass.getMethod("allocateInstance", Class::class.java).invoke(unsafe, T::class.java) as T
    }

    private val wifi = FakeWifi()
    private val links = FakeLinks()
    private val settings = Settings(MemoryKeyValue())
    private val videoOps = Executors.newSingleThreadExecutor()
    private val mainThread = Executors.newSingleThreadExecutor()
    private val main = mainThread.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val clock = { System.nanoTime() / 1_000_000 }
    private val bitmap = blank<Bitmap>()
    private val timing = LightControl.Timing(quietMs = 30, queryAfterMs = 40, checkAfterMs = 120, assertAfterMs = 80)
    private val conn = onMain {
        ScopeConnection(
            wifi, object : BookStore {
                override fun load() = DeviceBook()
                override fun save(book: DeviceBook) {}
            },
            scope, settings, clock, timing,
        ) { ScopeSession(links, scope, videoOps, ScopeSession.Timing(preStartMs = 10, tickMs = 20, retryMs = 60_000), clock, decode = { bitmap }) }
    }

    @After fun tearDown() {
        onMain { conn.disconnect() }
        Thread.sleep(50)
        scope.cancel()
        mainThread.shutdownNow()
        videoOps.shutdownNow()
        synchronized(links.log) { links.log.forEach { assertTrue("sent ${it.bytes}", FakeLinks.allowed(it.bytes)) } }
    }

    private fun <T> onMain(block: () -> T): T = runBlocking { withContext(main) { block() } }

    private fun raw(pct: Int) = Protocol.lightPercentToRaw(pct).toByte()
    private fun lightSets() = links.lightSends().filter { it.bytes[2] != 0xFF.toByte() && it.bytes[2] != 0xFE.toByte() }

    /** Join, wait for START, and show a frame, so the session is streaming. */
    private fun joinAndStream(): Int {
        onMain {
            conn.connect()  // a session starts only for a network the user asked for
            wifi.state.value = ScopeWifi.State.Available(blank<Network>(), Target.AnyScope)
        }
        await("START") { links.starts() >= 1 }
        return stream()
    }

    /** Show a frame on the newest video link; returns the newest command link's id. */
    private fun stream(): Int {
        val video = links.last(Protocol.DATA_PORT)
        video.incoming.put(frame())
        await("streaming") { conn.stats.value.frames > 0 }
        return links.last(Protocol.COMMAND_PORT).id
    }

    @Test fun noLightBeforeTheFirstFrameThenReappliedOnce() {
        settings.light = 40
        onMain {
            conn.connect()  // a session starts only for a network the user asked for
            wifi.state.value = ScopeWifi.State.Available(blank<Network>(), Target.AnyScope)
        }
        await("START") { links.starts() == 1 }
        onMain { conn.setLight(40) }
        Thread.sleep(200)  // long past the quiet time: still no video, so nothing is sent
        assertEquals(emptyList<Any>(), links.lightSends())
        stream()
        await("re-apply") { lightSets().isNotEmpty() }
        Thread.sleep(300)
        assertEquals(listOf(listOf<Byte>(0x66, 0x3C, raw(40))), lightSets().map { it.bytes })  // once
        await("confirmed or not, the check ran") { conn.lightState.value.status !is LightControl.Status.Verifying }
    }

    @Test fun aSliderMoveThenDisconnectWithinTheQuietTimeSendsNothing() {
        joinAndStream()
        await("re-apply") { lightSets().size == 1 }
        val before = links.lightSends().size
        onMain {
            conn.setLight(60)
            conn.disconnect()
        }
        Thread.sleep(300)
        assertEquals(before, links.lightSends().size)
        assertEquals(LightControl.Status.Idle, conn.lightState.value.status)  // no stale mark offline
    }

    @Test fun eachSessionGetsItsOwnReapplyAndTheOldOneStaysQuiet() {
        val first = joinAndStream()
        await("re-apply on the first session") { lightSets().any { it.link == first } }
        onMain { conn.reconnect() }
        await("second START") { links.starts() == 2 }
        val second = stream()
        await("re-apply on the second session") { lightSets().any { it.link == second } }
        // the first session's command link saw no light command after that session's STOP
        val log = links.sends()
        val firstVideo = synchronized(links.opened) { links.opened.first { it.remotePort == Protocol.DATA_PORT }.id }
        val firstStop = log.indexOfLast { it.link == firstVideo && it.bytes == Protocol.STOP.toList() }
        val lastLightOnFirst = log.indexOfLast { it.link == first && it.bytes.size == 3 && it.bytes[1] == 0x3C.toByte() }
        assertTrue("first session's STOP at $firstStop", firstStop > 0)
        assertTrue("light on the old link at $lastLightOnFirst, its STOP at $firstStop", lastLightOnFirst < firstStop)
    }

    @Test fun aPendingMoveAcrossReconnectGoesOnlyToTheNewSession() {
        val first = joinAndStream()
        await("re-apply") { lightSets().size == 1 }
        onMain {
            conn.setLight(70)  // due in 30 ms...
            conn.reconnect()   // ...but the session is replaced first
        }
        await("second START") { links.starts() == 2 }
        Thread.sleep(150)
        assertTrue(lightSets().none { it.link == first && it.bytes[2] == raw(70) })
        val second = stream()
        await("70 % on the new session") { lightSets().any { it.link == second && it.bytes[2] == raw(70) } }
        assertEquals(70, settings.light)  // remembered once sent
    }
}
