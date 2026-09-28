// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.connection

import android.content.Context
import android.util.Log
import com.bockelie.bebird.devices.DeviceBook
import com.bockelie.bebird.devices.DeviceStore
import com.bockelie.bebird.devices.Identify
import com.bockelie.bebird.devices.KnownDevice
import com.bockelie.bebird.proto.Beacon
import com.bockelie.bebird.scope.NetworkGate
import com.bockelie.bebird.scope.NetworkLinks
import com.bockelie.bebird.scope.ScopeSession
import com.bockelie.bebird.wifi.ScopeWifi
import com.bockelie.bebird.wifi.ScopeWifi.Target
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.util.concurrent.Future

/**
 * The scope connection: the Wi-Fi request, the video session on it, and the remembered
 * devices. A session starts when the scope's network becomes available and stops when it is
 * lost or on [disconnect]; the network request is released only after the session's STOP has
 * gone out (see [NetworkGate]). Owned by the ViewModel for now; it only needs a Context and a
 * scope, so a foreground service can own it instead when the app keeps the connection in the
 * background (#18).
 */
class ScopeConnection(context: Context, private val scope: CoroutineScope) {
    private val wifi = ScopeWifi(context)
    private val store = DeviceStore(context)
    val wifiState: StateFlow<ScopeWifi.State> = wifi.state

    private val _stats = MutableStateFlow(ScopeSession.Stats(status = "not connected"))
    val stats: StateFlow<ScopeSession.Stats> = _stats.asStateFlow()

    private val _book = MutableStateFlow(store.load())
    val book: StateFlow<DeviceBook> = _book.asStateFlow()

    private val _inRange = MutableStateFlow<List<ScopeWifi.Identity>>(emptyList())
    /** Scopes in the phone's last Wi-Fi scan; see [refreshInRange]. */
    val inRange: StateFlow<List<ScopeWifi.Identity>> = _inRange.asStateFlow()

    private val gate = NetworkGate(scope, wifi::stop, STOP_TIMEOUT_MS)
    private var session: ScopeSession? = null
    private var statsJob: Job? = null
    private var remembered: Identify.Result? = null  // for the current network

    init {
        scope.launch {
            wifi.state.collect { state ->
                when (state) {
                    is ScopeWifi.State.Available -> startSession(state)
                    else -> stopSession()
                }
            }
        }
        scope.launch {
            combine(wifi.state, wifi.identity, _stats) { state, id, stats -> Triple(state, id, stats.beacon) }
                .collect { (state, id, beacon) ->
                    if (state is ScopeWifi.State.Available) remember(state.target, id, beacon) else remembered = null
                }
        }
    }

    /** Join the last device by its exact SSID/BSSID, or show the picker if there is none. */
    fun connect() = request(_book.value.last?.let { Target.Exact(it.ssid, it.bssid) } ?: Target.AnyScope)

    fun connectTo(ssid: String, bssid: String?) = request(Target.Exact(ssid, bssid))

    /** The picker, listing every "bebird*" network, to join a different scope. */
    fun pickDifferent() = request(Target.AnyScope)

    /** Stop the session, then release the network once its STOP has gone out. */
    fun disconnect() = gate.disconnect(stopSession())

    fun rename(device: KnownDevice, nickname: String?) = edit { it.rename(device.key, nickname) }

    fun forget(device: KnownDevice) {
        Log.i(TAG, "forget ${device.ssid} bssid=${device.bssid}")
        edit { it.forget(device.key) }
    }

    /** Re-read the phone's last scan (no new scan is started) off the main thread. */
    fun refreshInRange() {
        scope.launch(Dispatchers.IO) { _inRange.value = wifi.scopesInRange() }
    }

    private fun request(t: Target) {
        disconnect()  // whatever is filed or streaming now; the gate orders the new request after it
        gate.connect { wifi.start(t) }
    }

    private fun edit(change: (DeviceBook) -> DeviceBook) {
        val book = _book.updateAndGet(change)
        store.save(book)
    }

    private fun remember(target: Target, id: ScopeWifi.Identity?, beacon: Beacon?) {
        val exact = target as? Target.Exact
        val result = Identify.resolve(
            exactSsid = exact?.ssid, exactBssid = exact?.bssid,
            wifiSsid = id?.ssid, wifiBssid = id?.bssid,
            beaconSsid = beacon?.ssid, beaconMac = beacon?.mac,
        ) ?: return
        if (result.ssid == remembered?.ssid && result.bssid == remembered?.bssid) return
        remembered = result
        Log.i(TAG, "remembering ${result.ssid} (from ${result.ssidFrom}), bssid ${result.bssid ?: "unknown"}" +
            (result.bssidFrom?.let { " (from $it)" } ?: "") +
            "; wifi info ${id?.ssid}/${id?.bssid}, beacon ${beacon?.ssid}/${beacon?.mac}")
        edit { it.seen(result.ssid, result.bssid, System.currentTimeMillis()) }
    }

    private fun startSession(state: ScopeWifi.State.Available) {
        stopSession()  // its STOP is queued ahead of the new session's STOP and START (see ScopeSession)
        val s = ScopeSession(NetworkLinks(state.network), scope)
        session = s
        statsJob = scope.launch { s.stats.collect { _stats.value = it } }
        s.start()
    }

    private fun stopSession(): Future<*>? {
        val s = session ?: return null
        session = null
        statsJob?.cancel()
        statsJob = null
        _stats.value = s.stats.value.copy(frame = null, fps = 0, status = "stopped", beacon = null)
        return s.stop()
    }

    companion object {
        private const val TAG = "BebirdSpike"
        // A dead link can't hold up the release for longer than this.
        private const val STOP_TIMEOUT_MS = 1000L
    }
}
