// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.scope

import android.util.Log
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
