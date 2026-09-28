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
import com.bockelie.bebird.scope.Sent
import com.bockelie.bebird.settings.MemoryKeyValue
import com.bockelie.bebird.settings.Settings
import com.bockelie.bebird.wifi.ScopeWifi
import com.bockelie.bebird.wifi.ScopeWifi.Target
import com.bockelie.bebird.wifi.WifiControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Leaving the app (#18) and ending the connection at the end of the grace period (#19), with real
 * sessions over fake links and the grace period on a test clock: what goes out, in which order,
 * and when the network is released.
 */
class GraceKeeperTest {
    private val links = FakeLinks()

    private inner class FakeWifi : WifiControl {
        override val state = MutableStateFlow<ScopeWifi.State>(ScopeWifi.State.Idle)
        override val identity = MutableStateFlow<ScopeWifi.Identity?>(null)
        /** What the fake links had sent at each release. */
        val releases = CopyOnWriteArrayList<List<Sent>>()
        val starts = CopyOnWriteArrayList<Target>()
        override fun start(target: Target, why: String?) { starts += target }
        override fun stop() {
            releases += links.sends()
            state.value = ScopeWifi.State.Idle
        }
        override fun scopesInRange(): List<ScopeWifi.Identity>? = null
    }

    /** The grace period's clock and timer; only [advance] moves it. */
    private inner class TestTimer {
        var now = 1_000_000L
        private val pending = mutableListOf<Triple<Long, Job, () -> Unit>>()

        fun after(ms: Long, action: () -> Unit): Job = Job().also { pending += Triple(now + ms, it, action) }

        fun advance(ms: Long) = onMain {
            now += ms
            val due = pending.filter { it.first <= now }
            pending -= due.toSet()
            for ((_, job, action) in due) if (job.isActive) { job.cancel(); action() }
        }
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
    private val settings = Settings(MemoryKeyValue())
    private val timer = TestTimer()
    private val videoOps = Executors.newSingleThreadExecutor()
    private val mainThread = Executors.newSingleThreadExecutor()
    private val main = mainThread.asCoroutineDispatcher()
    private val scope = CoroutineScope(SupervisorJob() + main)
    private val clock = { System.nanoTime() / 1_000_000 }
    private val bitmap = blank<Bitmap>()
    private val decodes = AtomicInteger()
    private val lightTiming = LightControl.Timing(quietMs = 10, queryAfterMs = 10, checkAfterMs = 30, assertAfterMs = 10)
    private val conn = onMain {
        ScopeConnection(wifi, store, scope, settings, clock, lightTiming) {
            ScopeSession(
                links, scope, videoOps, ScopeSession.Timing(preStartMs = 10, tickMs = 20, retryMs = 60_000), clock,
                decode = { decodes.incrementAndGet(); bitmap },
            )
        }
    }
    private var services = 0
    private var serviceStarts = true
    /** Wake locks taken (their timeouts) and how many are held now. */
    private val wakeLocks = mutableListOf<Long>()
    private var awake = 0
    private val keeper = onMain {
        GraceKeeper(conn, settings, { timer.now }, timer::after, { services++; serviceStarts }) { timeout ->
            wakeLocks += timeout
            awake++
            var held = true
            AutoCloseable { if (held) { held = false; awake-- } }
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

    private fun grace(seconds: Int, powerOff: Boolean) {
        settings.graceSeconds = seconds
        settings.powerOffAfterGrace = powerOff
        poweringOff = powerOff
    }

    private fun join() {
        onMain { conn.connect() }
        await("the request") { wifi.starts.isNotEmpty() }  // after connect()'s own release
        wifi.releases.clear()
        onMain { wifi.state.value = ScopeWifi.State.Available(blank<Network>(), Target.AnyScope) }
        await("START") { links.starts() >= 1 }
    }

    /** Video up, and the light's re-apply and check done, so only keepalives go out from here. */
    private fun stream() {
        links.last(Protocol.DATA_PORT).incoming.put(frame())
        await("streaming") { conn.isStreaming.value }
        await("the light checked") { conn.lightState.value.status is LightControl.Status.Mismatch }
    }

    private fun sendsSince(mark: Int) = links.sends().drop(mark).map { it.bytes }

    /** The release happened after [last] went out, and nothing followed it. */
    private fun assertReleasedAfter(vararg last: List<Byte>) {
        await("the release") { wifi.releases.isNotEmpty() }
        Thread.sleep(100)
        val atRelease = wifi.releases.first()
        assertEquals(last.toList(), atRelease.takeLast(last.size).map { it.bytes })
        assertEquals(atRelease, links.sends())
        assertEquals(1, wifi.releases.size)
    }

    @Test fun immediatelyWithPowerOff_stopThen663EThenRelease() {
        grace(0, powerOff = true)
        join()
        stream()
        onMain { keeper.onLeave() }
        assertReleasedAfter(STOP, POWER_OFF)
        assertNull(keeper.kept.value)
        assertEquals(0, services)
        assertEquals("bebird-ES-1", conn.book.value.last?.ssid)
    }

    @Test fun immediatelyWithoutPowerOff_stopThenRelease() {
        grace(0, powerOff = false)
        join()
        stream()
        onMain { keeper.onLeave() }
        assertReleasedAfter(STOP)
        assertTrue(links.sends().none { it.bytes == POWER_OFF })
        assertEquals(0, services)
    }

    @Test fun duringTheGracePeriodOnlyKeepalivesGoOut_thenPowerOff() {
        grace(60, powerOff = true)
        join()
        stream()
        val mark = links.sends().size
        onMain { keeper.onLeave() }
        assertEquals(1, services)
        assertEquals(1_060_000L, keeper.kept.value?.endsAt)

        val decoded = decodes.get()
        links.last(Protocol.DATA_PORT).incoming.put(frame(8))
        Thread.sleep(200)
        timer.advance(59_999)
        Thread.sleep(100)
        assertEquals(decoded, decodes.get())  // decoding paused
        val kept = sendsSince(mark)
        assertTrue("$kept", kept.size >= 5 && kept.all { it == BATTERY })
        assertTrue(wifi.releases.isEmpty())
        assertNotNull(keeper.kept.value)

        assertEquals(listOf(90_000L), wakeLocks)  // grace + 30 s
        assertEquals(1, awake)

        timer.advance(1)
        assertReleasedAfter(STOP, POWER_OFF)
        assertTrue(sendsSince(mark).dropLast(2).all { it == BATTERY })
        assertNull(keeper.kept.value)
        assertTrue(conn.decoding)
        assertEquals(0, awake)
    }

    @Test fun graceEndsWithADisconnectWhenPowerOffIsOff() {
        grace(30, powerOff = false)
        join()
        stream()
        onMain { keeper.onLeave() }
        timer.advance(30_000)
        assertReleasedAfter(STOP)
        assertTrue(links.sends().none { it.bytes == POWER_OFF })
    }

    @Test fun comingBackWithinTheGracePeriodCarriesOnWithTheSameSession() {
        grace(120, powerOff = true)
        join()
        stream()
        val mark = links.sends().size
        onMain { keeper.onLeave() }
        Thread.sleep(100)
        timer.advance(119_000)
        onMain { keeper.onReturn() }
        assertNull(keeper.kept.value)
        assertTrue(conn.decoding)
        assertEquals(0, awake)

        val decoded = decodes.get()
        val frames = conn.stats.value.frames
        links.last(Protocol.DATA_PORT).incoming.put(frame(9))
        await("the next frame") { conn.stats.value.frames > frames }
        assertTrue(decodes.get() > decoded)
        timer.advance(10_000)  // past the old deadline: the timer is gone
        Thread.sleep(100)
        assertTrue(wifi.releases.isEmpty())
        assertEquals(1, links.starts())
        assertTrue(sendsSince(mark).all { it == BATTERY })  // no STOP, no START
        assertEquals(1, links.opened.count { it.remotePort == Protocol.DATA_PORT })
    }

    @Test fun expiryBeforeVideoOnlyDisconnects() {
        grace(30, powerOff = true)
        join()  // START sent, no frame yet
        onMain { keeper.onLeave() }
        timer.advance(30_000)
        assertReleasedAfter(STOP)
        assertTrue(links.sends().none { it.bytes == POWER_OFF })
    }

    @Test fun closingTheAppEndsAtOnce() {
        grace(600, powerOff = true)
        join()
        stream()
        onMain { keeper.onClose() }
        assertEquals(1, wifi.releases.size)  // done before onClose returns: the process may go next
        assertReleasedAfter(STOP, POWER_OFF)
        assertEquals(0, services)
        assertNull(keeper.kept.value)
    }

    @Test fun closingTheAppWithoutPowerOffOnlyDisconnects() {
        grace(600, powerOff = false)
        join()
        stream()
        onMain { keeper.onClose() }
        assertEquals(1, wifi.releases.size)
        assertReleasedAfter(STOP)
        assertTrue(links.sends().none { it.bytes == POWER_OFF })
    }

    @Test fun leavingWithNoGracePeriodReleasesBeforeReturning() {
        // swiped away from the foreground: no service, so nothing may be left to finish
        grace(0, powerOff = true)
        join()
        stream()
        onMain { keeper.onLeave() }
        assertEquals(1, wifi.releases.size)
        assertReleasedAfter(STOP, POWER_OFF)
    }

    @Test fun closingTheAppDuringTheGracePeriodEndsAtOnce() {
        // swiped away from the recent apps while the service keeps the connection
        grace(600, powerOff = false)
        join()
        stream()
        onMain { keeper.onLeave() }
        Thread.sleep(50)
        onMain { keeper.onClose() }
        assertReleasedAfter(STOP)
        assertNull(keeper.kept.value)
        timer.advance(600_000)
        Thread.sleep(100)
        assertEquals(1, wifi.releases.size)
    }

    @Test fun theNotificationsActionsEndItAtOnce() {
        grace(600, powerOff = false)  // the setting doesn't matter to the explicit action
        poweringOff = true
        join()
        stream()
        onMain { keeper.onLeave() }
        onMain { keeper.powerOffNow() }
        assertReleasedAfter(STOP, POWER_OFF)
        assertNull(keeper.kept.value)
    }

    @Test fun aStaleNotificationActionAfterComingBackDoesNothing() {
        grace(600, powerOff = true)
        join()
        stream()
        onMain {
            keeper.onLeave()
            keeper.onReturn()
            keeper.disconnectNow()
            keeper.powerOffNow()
        }
        Thread.sleep(150)
        assertTrue(wifi.releases.isEmpty())
        assertTrue(conn.isStreaming.value)
    }

    @Test fun whenTheServiceCannotStartItEndsAtOnce() {
        grace(60, powerOff = true)
        serviceStarts = false
        join()
        stream()
        onMain { keeper.onLeave() }
        assertReleasedAfter(STOP, POWER_OFF)
        assertNull(keeper.kept.value)
    }

    @Test fun whenTheServiceFailsInTheForegroundItEndsAsAtExpiry() {
        grace(60, powerOff = true)
        join()
        stream()
        onMain { keeper.onLeave() }
        onMain { keeper.serviceFailed() }
        assertReleasedAfter(STOP, POWER_OFF)
        assertNull(keeper.kept.value)
        assertEquals(0, awake)
        onMain { keeper.serviceFailed() }  // nothing kept any more: nothing happens
        Thread.sleep(100)
        assertEquals(1, wifi.releases.size)
    }

    @Test fun repeatedLeaveAndReturnKeepsOneSessionAndOnlyTheLastDeadlineCounts() {
        grace(60, powerOff = true)
        join()
        stream()
        val mark = links.sends().size
        repeat(3) {
            onMain { keeper.onLeave() }
            Thread.sleep(30)
            timer.advance(40_000)
            onMain { keeper.onReturn() }
        }
        onMain { keeper.onLeave() }  // the last one, from 120 s on
        timer.advance(59_000)  // well past the first deadlines
        Thread.sleep(100)
        assertTrue(wifi.releases.isEmpty())
        assertNotNull(keeper.kept.value)
        assertEquals(1, links.starts())
        assertTrue(sendsSince(mark).all { it == BATTERY })
        assertEquals(4, services)
        assertEquals(1, awake)
        timer.advance(1_000)
        assertReleasedAfter(STOP, POWER_OFF)
        assertEquals(0, awake)
    }

    @Test fun losingTheNetworkWhileKeptLetsGoInsteadOfAskingAgain() {
        // Unavailable for the exact request: in the foreground that retries by SSID (maybe with
        // Android's picker); in the background it must not.
        grace(60, powerOff = true)
        join()
        stream()
        val requests = wifi.starts.size
        onMain { keeper.onLeave() }
        onMain { wifi.state.value = ScopeWifi.State.Unavailable(Target.Exact("bebird-ES-1", "12:34:56:78:9A:9F")) }
        await("the release") { wifi.releases.isNotEmpty() }
        Thread.sleep(150)
        assertEquals(requests, wifi.starts.size)
        timer.advance(60_000)  // the end of the period: nothing left to switch off
        Thread.sleep(100)
        assertNull(keeper.kept.value)
        assertTrue(links.sends().none { it.bytes == POWER_OFF })
        assertEquals(requests, wifi.starts.size)
    }

    @Test fun ourOwnLaunchIsNeverAnImmediateEnd() {
        // Files or Open from the snackbar, with the grace period set to Immediately
        grace(0, powerOff = true)
        join()
        stream()
        val mark = links.sends().size
        onMain {
            keeper.launchingOver()
            keeper.onLeave()
        }
        assertEquals(1, services)
        assertEquals(1_120_000L, keeper.kept.value?.endsAt)
        assertFalse(conn.decoding)
        Thread.sleep(100)
        timer.advance(119_999)
        Thread.sleep(100)
        assertTrue(wifi.releases.isEmpty())
        assertTrue(sendsSince(mark).all { it == BATTERY })

        onMain { keeper.onReturn() }
        val frames = conn.stats.value.frames
        links.last(Protocol.DATA_PORT).incoming.put(frame(9))
        await("the next frame") { conn.stats.value.frames > frames }
        timer.advance(10_000)
        Thread.sleep(100)
        assertTrue(wifi.releases.isEmpty())
        assertEquals(1, links.starts())
        assertTrue(sendsSince(mark).all { it == BATTERY })  // no STOP, no START
        // the next ordinary leave is Immediately again
        onMain { keeper.onLeave() }
        assertReleasedAfter(STOP, POWER_OFF)
    }

    @Test fun theEndOfOurOwnLaunchsCoverPowersOff() {
        grace(0, powerOff = true)
        join()
        stream()
        onMain {
            keeper.launchingOver()
            keeper.onLeave()
        }
        timer.advance(120_000)
        assertReleasedAfter(STOP, POWER_OFF)
        assertNull(keeper.kept.value)
    }

    @Test fun aLongerGracePeriodThanTheCoverWins() {
        grace(300, powerOff = false)
        join()
        stream()
        onMain {
            keeper.launchingOver()
            keeper.onLeave()
        }
        assertEquals(1_300_000L, keeper.kept.value?.endsAt)
        timer.advance(299_999)
        Thread.sleep(100)
        assertTrue(wifi.releases.isEmpty())
        timer.advance(1)
        assertReleasedAfter(STOP)
    }

    @Test fun aLaunchThatFailedCoversNothing() {
        grace(0, powerOff = false)
        join()
        stream()
        onMain {
            keeper.launchingOver()
            keeper.launchFailed()
            keeper.onLeave()
        }
        assertEquals(0, services)
        assertReleasedAfter(STOP)
    }

    @Test fun theCoverStillHoldsWhenTheServiceIsRefused() {
        grace(0, powerOff = true)
        serviceStarts = false
        join()
        stream()
        onMain {
            keeper.launchingOver()
            keeper.onLeave()
        }
        Thread.sleep(100)
        assertTrue(wifi.releases.isEmpty())
        timer.advance(120_000)
        assertReleasedAfter(STOP, POWER_OFF)
    }

    @Test fun leavingWhenNotConnectedKeepsNothing() {
        grace(60, powerOff = true)
        onMain { keeper.onLeave() }
        Thread.sleep(100)
        assertEquals(0, services)
        assertNull(keeper.kept.value)
        assertEquals(emptyList<Sent>(), links.sends())
    }

    @Test fun aHoldKeepsTheConnectionPastTheGracePeriod() {
        // a recording (#15)
        grace(30, powerOff = true)
        join()
        stream()
        val hold = onMain { keeper.hold() }
        onMain { keeper.onLeave() }
        assertEquals(true, keeper.kept.value?.held)
        timer.advance(31_000)
        onMain { keeper.onClose() }
        Thread.sleep(150)
        assertTrue(wifi.releases.isEmpty())
        onMain { hold.close() }
        assertReleasedAfter(STOP, POWER_OFF)
        onMain { hold.close() }  // idempotent
    }

    @Test fun aHoldKeepsTheConnectionEvenWithNoGracePeriod() {
        grace(0, powerOff = false)
        join()
        stream()
        val hold = onMain { keeper.hold() }
        onMain { keeper.onLeave() }
        timer.advance(0)
        Thread.sleep(100)
        assertEquals(1, services)
        assertTrue(wifi.releases.isEmpty())
        onMain { hold.close() }
        assertReleasedAfter(STOP)
    }

    private companion object {
        val STOP = Protocol.STOP.toList()
        val BATTERY = Protocol.BATTERY.toList()
        val POWER_OFF = listOf<Byte>(0x66, 0x3E)
    }
}
