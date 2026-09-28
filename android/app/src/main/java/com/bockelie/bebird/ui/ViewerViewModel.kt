// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.bockelie.bebird.scope.ScopeSession
import com.bockelie.bebird.wifi.ScopeWifi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Ties the Wi-Fi request to a video session: a session starts when the scope's network
 * becomes available and stops when it is lost, on [disconnect], or when the ViewModel is cleared.
 */
class ViewerViewModel(app: Application) : AndroidViewModel(app) {
    private val wifi = ScopeWifi(app)
    val wifiState: StateFlow<ScopeWifi.State> = wifi.state

    private val _stats = MutableStateFlow(ScopeSession.Stats(status = "not connected"))
    val stats: StateFlow<ScopeSession.Stats> = _stats.asStateFlow()

    private var session: ScopeSession? = null
    private var statsJob: Job? = null

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

    fun connect() = wifi.start()

    fun disconnect() {
        stopSession()
        wifi.stop()
    }

    private fun startSession(state: ScopeWifi.State.Available) {
        stopSession()
        val s = ScopeSession(state.network, viewModelScope)
        session = s
        statsJob = viewModelScope.launch { s.stats.collect { _stats.value = it } }
        s.start()
    }

    private fun stopSession() {
        val s = session ?: return
        session = null
        s.stop()
        statsJob?.cancel()
        statsJob = null
        _stats.value = s.stats.value.copy(frame = null, fps = 0)
    }

    override fun onCleared() {
        Log.i(TAG, "ViewModel cleared")
        disconnect()
    }

    companion object {
        private const val TAG = "BebirdSpike"
    }
}
