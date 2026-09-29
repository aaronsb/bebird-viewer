// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.util.Log
import com.bockelie.bebird.capture.VideoRecorder
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Quit (#38), in order: the recording finished, then the connection ended (the scope switched
 * off if video had started), then the network released, then the app closed. The waits run on
 * [io], each with a bound, so the screen never blocks and a stuck step can't keep the app open;
 * the rest runs in [scope] (the main thread, as the connection needs). Once only: a second
 * [start] does nothing.
 */
class QuitSequence(
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    /** Stop any recording; its completion, or null if there was none. */
    private val finishRecording: () -> Future<*>?,
    /** Power off or disconnect, without waiting for the release. */
    private val endConnection: () -> Unit,
    /** Wait up to the given ms for the network release; true if it happened. */
    private val awaitRelease: (Long) -> Boolean,
    private val recordingWaitMs: Long = RECORDING_WAIT_MS,
    private val releaseWaitMs: Long = RELEASE_WAIT_MS,
) {
    private var started = false

    /** Run the sequence, then [exit] on [scope]; false (and nothing happens) if already started. */
    fun start(exit: () -> Unit): Boolean {
        if (started) return false
        started = true
        scope.launch {
            val recording = finishRecording()
            if (recording != null) {
                val finished = withContext(io) {
                    try {
                        recording.get(recordingWaitMs, TimeUnit.MILLISECONDS)
                        true
                    } catch (_: TimeoutException) {
                        false
                    } catch (_: Exception) {
                        true  // it failed or was cancelled: nothing more to wait for
                    }
                }
                if (!finished) Log.w(TAG, "quit: the recording still isn't finished after $recordingWaitMs ms; going on")
            }
            endConnection()
            val released = withContext(io) { awaitRelease(releaseWaitMs) }
            if (!released) Log.w(TAG, "quit: the release is still pending after $releaseWaitMs ms; closing anyway")
            exit()
        }
        return true
    }

    private companion object {
        const val TAG = "BebirdSpike"
        // Finishing a recording drains the encoder for up to its deadline, then stops the muxer
        // and publishes the file: allow that, and a second for the rest.
        const val RECORDING_WAIT_MS = VideoRecorder.DRAIN_DEADLINE_MS + 1000L
        // Past the release's own 1 s limit on waiting for STOP (see NetworkGate).
        const val RELEASE_WAIT_MS = 1500L
    }
}
