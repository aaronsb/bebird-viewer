// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bockelie.bebird.scope.NetworkLinks
import com.bockelie.bebird.scope.ScopeSession
import com.bockelie.bebird.scope.NetworkGate
import com.bockelie.bebird.wifi.ScopeWifi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.util.concurrent.Future

/**
 * Ties the Wi-Fi request to a video session: a session starts when the scope's network
 * becomes available and stops when it is lost, on [disconnect], or when the ViewModel is
 * cleared. The network request is released only after the session's STOP has gone out.
 */
class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    private val wifi = ScopeWifi(app)
    val wifiState: StateFlow<ScopeWifi.State> = wifi.state

    private val _stats = MutableStateFlow(ScopeSession.Stats(status = "not connected"))
    val stats: StateFlow<ScopeSession.Stats> = _stats.asStateFlow()

    private var session: ScopeSession? = null
    private var statsJob: Job? = null
    // Requests and releases run off the main thread; on viewModelScope, so onCleared also
    // cancels a connect that is still waiting.
    private val gate = NetworkGate(viewModelScope, wifi::start, wifi::stop, STOP_TIMEOUT_MS)

    init {
        viewModelScope.launch {
            wifi.state.collect { state ->
                when (state) {
                    is ScopeWifi.State.Available -> startSession(state)
                    else -> stopSession()
                }
            }
        }
    }

    /** Waits for any pending release, or start() would see the old request and do nothing. */
    fun connect() = gate.connect()

    /** Stop the session, then release the network once its STOP has gone out. */
    fun disconnect() = gate.disconnect(stopSession())

    private fun startSession(state: ScopeWifi.State.Available) {
        stopSession()  // its STOP is queued ahead of the new session's STOP and START (see ScopeSession)
        val s = ScopeSession(NetworkLinks(state.network), viewModelScope)
        session = s
        statsJob = viewModelScope.launch { s.stats.collect { _stats.value = it } }
        s.start()
    }

    private fun stopSession(): Future<*>? {
        val s = session ?: return null
        session = null
        statsJob?.cancel()
        statsJob = null
        _stats.value = s.stats.value.copy(frame = null, fps = 0, status = "stopped")
        return s.stop()
    }

    override fun onCleared() {
        Log.i(TAG, "ViewModel cleared")
        disconnect()
    }

    companion object {
        private const val TAG = "BebirdSpike"
        // A dead link can't hold up the release for longer than this.
        private const val STOP_TIMEOUT_MS = 1000L
    }
}
