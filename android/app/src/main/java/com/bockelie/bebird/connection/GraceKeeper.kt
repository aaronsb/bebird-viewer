// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.connection

import android.util.Log
import com.bockelie.bebird.settings.Settings
import com.bockelie.bebird.wifi.ScopeWifi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What happens to the connection when the app leaves the screen (#18, #19). With a grace period
 * of 0 it ends at once. Otherwise it is kept, with the network request and the battery keepalive
 * running and decoding off ([ScopeConnection.decoding]), until the period ends or the app comes
 * back; coming back carries on with the same session: no STOP, no START, no picker. Ending means
 * switching the scope off if that setting is on and video had started ([ScopeConnection.powerOff]),
 * and disconnecting otherwise. Closing the app ends it at once, the same way.
 *
 * Android freezes a background app within seconds, so while the connection is kept a foreground
 * service holds the app up: [startService] starts it, and the service follows [kept] and stops
 * once that is null. The service doesn't stop the CPU from sleeping with the screen off, which
 * would stall the keepalive and the timer, so a wake lock ([stayAwake]) is held as well.
 * Main thread only, like [ScopeConnection].
 */
class GraceKeeper(
    private val connection: ScopeConnection,
    private val settings: Settings,
    /** Milliseconds, monotonic. */
    private val clock: () -> Long,
    /** Run the action on the main thread after the delay (ms), unless the job is cancelled. */
    private val after: (Long, () -> Unit) -> Job,
    /** Start the foreground service; false if Android refused. */
    private val startService: () -> Boolean,
    /** Keep the CPU awake until the handle is closed, or for at most the timeout (ms). */
    private val stayAwake: (Long) -> AutoCloseable = { AutoCloseable {} },
) {
    /**
     * The connection is kept in the background until [endsAt] ([clock] time), then the scope is
     * switched off ([endsWithPowerOff]) or disconnected; while [held], past that until the hold ends.
     */
    data class Kept(val endsAt: Long, val endsWithPowerOff: Boolean, val held: Boolean)

    private val _kept = MutableStateFlow<Kept?>(null)
    val kept: StateFlow<Kept?> = _kept.asStateFlow()

    private var timer: Job? = null
    private val cover = ExternalLaunch()
    private var awake: AutoCloseable? = null
    private var holds = 0
    private var expired = false  // the grace period is over, but a hold keeps the connection

    init {
        connection.onLetGo = ::lostWhileKept
    }

    /**
     * The screen went away (onStop, not for a configuration change). After our own launch over
     * the app ([launchingOver]) the connection is kept for at least the cover's time, never ended
     * at once, even with no grace period.
     */
    fun onLeave() {
        val covered = cover.coversStop(clock())
        cover.returned()
        val grace = settings.graceSeconds * 1000L
        leave(if (covered) maxOf(grace, cover.capMs) else grace, covered)
    }

    /** Just before the app starts another activity over itself (Files, Open, a picker). */
    fun launchingOver() = cover.begin(clock())

    /** That launch failed: nothing covers the next stop. */
    fun launchFailed() = cover.returned()

    /**
     * The activity is in front again. After a launch that only paused it (a translucent chooser,
     * cancelled) there is no onStart, so the cover is cleared here too.
     */
    fun onResumed() = cover.returned()

    /** The app was closed (the Activity finishing, or the task swiped away): end now. */
    fun onClose() {
        if (_kept.value == null) leave(0) else expire()
    }

    /** The screen is back: the kept session carries on, and the service stops. */
    fun onReturn() {
        cover.returned()
        stopTimer()
        sleep()
        expired = false
        connection.decoding = true
        _kept.value = null
    }

    /** The connection let go on its own (the network went while kept): nothing left to end. */
    private fun lostWhileKept() {
        if (_kept.value == null) return
        Log.i(TAG, "the connection went while kept; nothing left to keep")
        stopTimer()
        sleep()
        expired = false
        connection.decoding = true
        _kept.value = null
    }

    /** The service could not run in the foreground: end now, as if the period had run out. */
    fun serviceFailed() {
        _kept.value?.let { end(it.endsWithPowerOff) }
    }

    /** The notification's Disconnect; nothing once the app is back (a stale notification). */
    fun disconnectNow() {
        if (_kept.value != null) end(powerOff = false)
    }

    /** The notification's Power off: disconnects instead if video hasn't started. */
    fun powerOffNow() {
        if (_kept.value != null) end(powerOff = true)
    }

    /**
     * Keep the connection past the grace period, and past closing the app, until the returned
     * handle is closed: for a recording in progress (#15). Disconnect and Power off from the
     * notification still end it.
     */
    fun hold(): AutoCloseable {
        holds++
        _kept.value?.let { _kept.value = it.copy(held = true) }
        var open = true
        return AutoCloseable {
            if (open) {
                open = false
                holds--
                if (holds == 0) {
                    _kept.value?.let { _kept.value = it.copy(held = false) }
                    if (expired) end(_kept.value?.endsWithPowerOff ?: settings.powerOffAfterGrace)
                }
            }
        }
    }

    private fun leave(graceMs: Long, covered: Boolean = false) {
        if (_kept.value != null) return
        val powerOff = settings.powerOffAfterGrace
        val found = connection.wifiState.value.let { it !is ScopeWifi.State.Unavailable && it !is ScopeWifi.State.Failed }
        if (!connection.isWanted || !found) {
            connection.disconnect()  // a connect still waiting for a release, or one that found nothing
            return
        }
        if (graceMs == 0L && holds == 0) {
            end(powerOff)
            return
        }
        Log.i(TAG, "left the app${if (covered) " for our own launch" else ""}: keeping the connection for $graceMs ms")
        connection.decoding = false
        expired = false
        _kept.value = Kept(clock() + graceMs, powerOff, held = holds > 0)
        timer = after(graceMs) { expire() }
        awake = stayAwake(graceMs + AWAKE_MARGIN_MS)
        if (!startService()) {
            if (covered) {
                // never at once for our own launch; the timer still ends it, if Android lets it run
                Log.w(TAG, "the foreground service could not start; keeping the connection anyway")
            } else {
                Log.w(TAG, "the foreground service could not start; ending now")
                end(powerOff)
            }
        }
    }

    private fun expire() {
        stopTimer()
        if (holds > 0) {
            expired = true
            return
        }
        end(_kept.value?.endsWithPowerOff ?: settings.powerOffAfterGrace)
    }

    private fun end(powerOff: Boolean) {
        stopTimer()
        sleep()
        expired = false
        Log.i(TAG, if (powerOff) "ending the connection: power off" else "ending the connection: disconnect")
        if (!(powerOff && connection.powerOff())) connection.disconnect()
        connection.decoding = true
        _kept.value = null
        // The process may go right after this (the app closed with no service running), so see
        // the sends and the release through; the release itself gives up on STOP after 1 s.
        val released = connection.awaitRelease(RELEASE_WAIT_MS)
        Log.i(TAG, if (released) "connection ended and released" else "release still pending after $RELEASE_WAIT_MS ms")
    }

    private fun stopTimer() {
        timer?.cancel()
        timer = null
    }

    private fun sleep() {
        awake?.close()
        awake = null
    }

    private companion object {
        const val TAG = "BebirdSpike"
        // The wake lock's safety timeout, past the end of the grace period.
        const val AWAKE_MARGIN_MS = 30_000L
        const val RELEASE_WAIT_MS = 1000L
    }
}
