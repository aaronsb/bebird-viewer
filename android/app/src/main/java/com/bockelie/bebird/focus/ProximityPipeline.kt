// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import android.os.SystemClock
import com.bockelie.bebird.connection.ScopeConnection
import com.bockelie.bebird.settings.KeyValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
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
    val options = ProximityOptions(ProximitySettings(kv), gate)

    private val _result = MutableStateFlow<FocusResult?>(null)
    val result: StateFlow<FocusResult?> = _result.asStateFlow()

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "bebird-proximity").apply { isDaemon = true } }
    // A result from a frame that was already queued when estimation was turned off is dropped.
    private val frames = ProximityFrames(gate, worker::execute) { r -> _result.value = r.takeIf { gate.enabled } }

    init {
        scope.launch {
            connection.stats.map { it.frame to it.angle }.distinctUntilChanged().collect { (frame, roll) ->
                when {
                    frame == null -> _result.value = null  // the stream stopped
                    frame.width != FrameGeometry.SIZE || frame.height != FrameGeometry.SIZE -> Unit
                    else -> frames.offer(SystemClock.elapsedRealtime() / 1000.0, roll) { buffers -> frame.lumaInto(buffers) }
                }
            }
        }
        scope.launch {
            options.state.collect { if (!it.enabled) _result.value = null }
        }
    }
}
