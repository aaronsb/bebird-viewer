// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bockelie.bebird.scope.NetworkLinks
import com.bockelie.bebird.scope.ScopeSession
import com.bockelie.bebird.scope.afterStop
import com.bockelie.bebird.wifi.ScopeWifi
import kotlinx.coroutines.Dispatchers
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
    @Volatile private var release: Thread? = null  // a pending release, see disconnect()

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

    fun connect() {
        // A Connect right after Disconnect waits for that release, or start() would see the
        // old request still filed and do nothing.
        viewModelScope.launch(Dispatchers.IO) {
            release?.join()
            wifi.start()
        }
    }

    fun disconnect() {
        val stopped = stopSession()
        // Off the main thread (and not on viewModelScope, which is already cancelled in onCleared).
        release = afterStop(stopped, STOP_TIMEOUT_MS) { wifi.stop() }
    }

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
