// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.scope

import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import kotlin.concurrent.thread

/**
 * Run [release] on a new thread once [stopped] (a session's STOP, see [ScopeSession.stop]) has
 * completed, or after [timeoutMs] if it hangs, so the network outlives the STOP but a dead link
 * can't hold it forever. Returns the thread, for callers that must wait for the release.
 */
fun afterStop(stopped: Future<*>?, timeoutMs: Long, release: () -> Unit): Thread =
    thread(name = "bebird-release") {
        try {
            stopped?.get(timeoutMs, TimeUnit.MILLISECONDS)
        } catch (_: TimeoutException) {
            Log.w("BebirdSpike", "STOP still pending after $timeoutMs ms; releasing anyway")
        } catch (_: Exception) {
            // the STOP task failed or was interrupted: nothing more to wait for
        }
        release()
    }

/**
 * Orders the network request against session teardown, for the ViewModel:
 * - every release waits for the latest STOP, however many times [disconnect] is called
 *   (STOPs run in FIFO order, so the latest one completing means all earlier ones have);
 * - a [connect] waits for any pending release, and a [disconnect] cancels a connect that is
 *   still waiting, so no request is filed after the app has gone to the background.
 */
class NetworkGate(
    private val scope: CoroutineScope,
    private val release: () -> Unit,
    private val stopTimeoutMs: Long,
) {
    private val lock = Any()
    private var lastStop: Future<*>? = null  // guarded by lock
    private var releasing: Thread? = null     // guarded by lock
    private var connecting: Job? = null       // guarded by lock

    /** Run [request] (file the network request) once any pending release is done. */
    fun connect(request: () -> Unit) = synchronized(lock) {
        connecting?.cancel()
        val pending = releasing
        connecting = scope.launch(Dispatchers.IO) {
            runInterruptible { pending?.join() }
            // Under the lock, so a disconnect() either cancels us first or releases after us.
            synchronized(lock) { if (isActive) request() }
        }
    }

    /** [stopped] is the session's STOP, or null if there was no session to stop. */
    fun disconnect(stopped: Future<*>?) = synchronized(lock) {
        connecting?.cancel()
        connecting = null
        if (stopped != null) lastStop = stopped
        releasing = afterStop(lastStop, stopTimeoutMs, release)
    }
}
