// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.scope

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CompletableFuture

/** The ViewModel's ordering of network request and release against session STOPs. */
class NetworkGateTest {
    private val events = mutableListOf<String>()
    /** At each markEnding: the events so far, and whether the gate's lock was held. */
    private val marks = mutableListOf<Pair<List<String>, Boolean>>()
    private lateinit var lockHeld: () -> Boolean
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gate = NetworkGate(
        scope,
        release = { synchronized(events) { events += "release" } },
        stopTimeoutMs = 5000,
        markEnding = { synchronized(marks) { marks += events() to lockHeld() } },
    )

    init {
        // the gate's private lock, to check markEnding runs under it
        val lock = NetworkGate::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(gate)
        lockHeld = { Thread.holdsLock(lock) }
    }

    private val request = { synchronized(events) { events += "request" } }

    @After fun tearDown() = scope.cancel()

    private fun events() = synchronized(events) { events.toList() }
    private fun stopDone(stop: CompletableFuture<Unit>) {
        synchronized(events) { events += "stop" }
        stop.complete(Unit)
    }

    private fun await(what: String, cond: () -> Boolean) {
        val until = System.nanoTime() + 2_000_000_000
        while (!cond()) {
            if (System.nanoTime() > until) throw AssertionError("timed out waiting for $what; events ${events()}")
            Thread.sleep(2)
        }
    }

    @Test fun releaseWaitsForStop() {
        val stop = CompletableFuture<Unit>()
        gate.disconnect(stop)
        Thread.sleep(100)
        assertEquals(emptyList<String>(), events())
        stopDone(stop)
        await("release") { events().size == 2 }
        assertEquals(listOf("stop", "release"), events())
    }

    @Test fun aSecondDisconnectStillWaitsForTheOutstandingStop() {
        // onStop then onCleared: the second call has no session of its own to stop
        val stop = CompletableFuture<Unit>()
        gate.disconnect(stop)
        gate.disconnect(null)
        Thread.sleep(100)
        assertEquals(emptyList<String>(), events())
        stopDone(stop)
        await("both releases") { events().size == 3 }
        assertEquals(listOf("stop", "release", "release"), events())
    }

    @Test fun connectWaitsForThePendingRelease() {
        val stop = CompletableFuture<Unit>()
        gate.disconnect(stop)
        gate.connect(request)
        Thread.sleep(100)
        assertEquals(emptyList<String>(), events())
        stopDone(stop)
        await("request") { "request" in events() }
        assertEquals(listOf("stop", "release", "request"), events())
    }

    @Test fun disconnectCancelsAWaitingConnect() {
        // Connect during a pending release, then the app goes to the background
        val stop = CompletableFuture<Unit>()
        gate.disconnect(stop)
        gate.connect(request)
        gate.disconnect(null)
        stopDone(stop)
        await("both releases") { events().count { it == "release" } == 2 }
        Thread.sleep(100)
        assertEquals(listOf("stop", "release", "release"), events())
    }

    @Test fun cancellingTheScopeCancelsAWaitingConnect() {
        // onCleared: viewModelScope is cancelled while the connect waits
        val stop = CompletableFuture<Unit>()
        gate.disconnect(stop)
        gate.connect(request)
        scope.cancel()
        stopDone(stop)
        await("release") { "release" in events() }
        Thread.sleep(100)
        assertEquals(listOf("stop", "release"), events())
    }

    @Test fun eachDisconnectMarksTheRequestEndingUnderTheLock() {
        // Under the lock, after a waiting connect is cancelled: a request is either never filed,
        // or filed before the mark and marked (#37).
        gate.connect(request)
        await("request") { "request" in events() }
        gate.disconnect(null)
        await("release") { "release" in events() }
        gate.disconnect(null)
        assertEquals(listOf(listOf("request") to true, listOf("request", "release") to true), synchronized(marks) { marks.toList() })
    }

    @Test fun aConnectCancelledByDisconnectFilesNothingAfterTheMark() {
        val stop = CompletableFuture<Unit>()
        gate.disconnect(stop)
        gate.connect(request)
        gate.disconnect(null)
        stopDone(stop)
        await("both releases") { events().count { it == "release" } == 2 }
        Thread.sleep(100)
        assertEquals(2, synchronized(marks) { marks.size })
        assertTrue("request" !in events())
    }

    @Test fun connectWithNothingPendingRequestsAtOnce() {
        gate.connect(request)
        await("request") { events() == listOf("request") }
    }
}
