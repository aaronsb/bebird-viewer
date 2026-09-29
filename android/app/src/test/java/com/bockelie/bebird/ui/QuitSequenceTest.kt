// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import com.bockelie.bebird.scope.FakeLinks.Companion.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.Future
import kotlin.concurrent.thread

/** Quit's order (#38): the recording finished, then the connection ended, then released, then the exit. */
class QuitSequenceTest {
    private val mainThread = Executors.newSingleThreadExecutor { r -> Thread(r, "main") }
    private val scope = CoroutineScope(SupervisorJob() + mainThread.asCoroutineDispatcher())
    private val events = CopyOnWriteArrayList<String>()
    private val onMain = CopyOnWriteArrayList<Boolean>()  // whether each end and exit ran on "main"

    @After fun tearDown() {
        scope.cancel()
        mainThread.shutdownNow()
    }

    private fun mainOnly(what: String) {
        onMain += Thread.currentThread().name.startsWith("main")  // " @coroutine#n" in debug mode
        events += what
    }

    private fun sequence(
        recording: () -> Future<*>? = { null },
        released: (Long) -> Boolean = { true },
        recordingWaitMs: Long = 2000,
    ) = QuitSequence(
        scope, Dispatchers.IO,
        finishRecording = recording,
        endConnection = { mainOnly("power off") },
        awaitRelease = { ms -> events += "awaiting release"; released(ms).also { events += if (it) "released" else "release pending" } },
        recordingWaitMs = recordingWaitMs,
        releaseWaitMs = 300,
    )

    private fun exit() = mainOnly("exit")

    @Test fun theRecordingIsFinishedBeforeThePowerOffAndTheExitComesAfterTheRelease() {
        val file = CompletableFuture<Unit>()
        val q = sequence(recording = {
            events += "stop recording"
            thread { Thread.sleep(200); events += "file finished"; file.complete(Unit) }
            file
        }, released = { Thread.sleep(100); true })
        onMain { q.start(::exit) }
        await("exit") { "exit" in events }
        assertEquals(listOf("stop recording", "file finished", "power off", "awaiting release", "released", "exit"), events.toList())
        assertEquals(listOf(true, true), onMain.toList())
    }

    @Test fun aRecordingThatWontFinishHoldsQuitOnlyForTheBound() {
        val q = sequence(recording = { CompletableFuture<Unit>() }, recordingWaitMs = 200)
        val t0 = System.nanoTime()
        onMain { q.start(::exit) }
        await("exit") { "exit" in events }
        val tookMs = (System.nanoTime() - t0) / 1_000_000
        assertTrue("took $tookMs ms", tookMs in 150..1500)
        assertEquals(listOf("power off", "awaiting release", "released", "exit"), events.toList())
    }

    @Test fun aFailedRecordingDoesNotHoldQuit() {
        val q = sequence(recording = { CompletableFuture.failedFuture<Unit>(IllegalStateException("encoder")) })
        onMain { q.start(::exit) }
        await("exit") { "exit" in events }
        assertEquals(listOf("power off", "awaiting release", "released", "exit"), events.toList())
    }

    @Test fun aReleaseThatDoesntComeStillExits() {
        val q = sequence(released = { ms -> Thread.sleep(ms); false })
        onMain { q.start(::exit) }
        await("exit") { "exit" in events }
        assertEquals(listOf("power off", "awaiting release", "release pending", "exit"), events.toList())
    }

    @Test fun onlyOnce() {
        val q = sequence(released = { Thread.sleep(100); true })
        val started = CopyOnWriteArrayList<Boolean>()
        onMain {
            started += q.start(::exit)
            started += q.start(::exit)  // a second hold, or TalkBack, while the first is going
        }
        await("exit") { "exit" in events }
        Thread.sleep(200)
        assertEquals(listOf(true, false), started.toList())
        assertEquals(1, events.count { it == "power off" })
        assertEquals(1, events.count { it == "exit" })
    }

    @Test fun nothingWaitsOnTheMainThread() {
        // the main thread stays free while the sequence waits on the recording and the release
        val file = CompletableFuture<Unit>()
        val q = sequence(recording = { file }, released = { Thread.sleep(300); true })
        onMain { q.start(::exit) }
        Thread.sleep(50)
        assertFalse(onMain { "exit" in events })  // the main thread answers while waiting
        file.complete(Unit)
        await("exit") { "exit" in events }
    }

    /** Run [block] on the main thread and wait for it. */
    private fun <T> onMain(block: () -> T): T = mainThread.submit(Callable { block() }).get()
}
