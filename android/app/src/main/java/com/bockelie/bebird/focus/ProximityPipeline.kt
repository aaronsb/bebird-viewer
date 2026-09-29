// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.bockelie.bebird.connection.ScopeConnection
import com.bockelie.bebird.settings.KeyValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Executors

/**
 * Proximity estimation for the app's one connection: every decoded frame (raw, unrotated, as
 * the session publishes it; none while not decoding) is offered to one [ProximityGate] through
 * [ProximityFrames], on its own worker thread so decoding never waits. The latest [result] is
 * what the screen's overlay draws; null while estimation is off or there is no video.
 * App-scoped, like the connection, so it survives the activity.
 */
class ProximityPipeline(connection: ScopeConnection, kv: KeyValue, scope: CoroutineScope) {
    private val gate = ProximityGate()

    private val _result = MutableStateFlow<FocusResult?>(null)
    val result: StateFlow<FocusResult?> = _result.asStateFlow()

    private val _stopped = MutableStateFlow(false)
    /** Estimation stopped for this session after repeated errors (the setting stays on). */
    val stopped: StateFlow<Boolean> = _stopped.asStateFlow()

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "bebird-proximity").apply { isDaemon = true } }
    private val frames = ProximityFrames(
        gate, worker::execute,
        onError = { Log.e(TAG, "proximity estimator failed (${it.javaClass.simpleName})", it) },
        onGiveUp = {
            Log.e(TAG, "proximity estimator keeps failing: stopped for this session")
            _result.value = null
            _stopped.value = true
        },
        // a result from a frame already queued when estimation was turned off is dropped
    ) { r -> _result.value = r.takeIf { gate.enabled } }
    val options = ProximityOptions(ProximitySettings(kv), gate, stoppedForSession = { frames.gaveUp })

    private var stoppedNoticeShown = false  // main thread

    /** True the first time it's asked after estimation stopped: the notice is shown once per session. */
    fun takeStoppedNotice(): Boolean {
        if (!_stopped.value || stoppedNoticeShown) return false
        stoppedNoticeShown = true
        return true
    }

    init {
        scope.launch {
            var last: Bitmap? = null
            connection.stats.collect { stats ->
                val frame = stats.frame
                if (frame === last) return@collect  // not a new frame (battery, fps, ...)
                last = frame
                if (frame == null) {
                    // The stream ended (stop, a lost link, another scope): what the estimator
                    // learned about the tip and the scene doesn't carry over to the next one.
                    _result.value = null
                    frames.restart()
                    return@collect
                }
                if (!gate.enabled) return@collect  // off: no clock read, no lambda, nothing queued
                if (!ProximityFrames.accepts(frame.width, frame.height, frame.config == Bitmap.Config.ARGB_8888)) return@collect
                val roll = stats.angle
                frames.offer(SystemClock.elapsedRealtime() / 1000.0, roll) { buffers -> frame.lumaInto(buffers) }
            }
        }
        scope.launch {
            options.state.collect { if (!it.enabled) _result.value = null }
        }
    }

    private companion object {
        const val TAG = "BebirdSpike"
    }
}
