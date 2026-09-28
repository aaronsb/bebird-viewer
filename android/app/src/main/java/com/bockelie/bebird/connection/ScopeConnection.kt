// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.connection

import android.content.Context
import android.util.Log
import com.bockelie.bebird.devices.BookStore
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
import com.bockelie.bebird.wifi.WifiControl
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import java.util.concurrent.Future

/**
 * The scope connection: the Wi-Fi request, the video session on it, and the remembered
 * devices. A session starts when the scope's network becomes available and stops when it is
 * lost or on [disconnect]; the network request is released only after the session's STOP has
 * gone out (see [NetworkGate]). Owned by the ViewModel for now; it only needs a Context and a
 * scope, so a foreground service can own it instead when the app keeps the connection in the
 * background (#18). Not thread-safe: call it from the main thread.
 */
class ScopeConnection(
    private val wifi: WifiControl,
    private val store: BookStore,
    private val scope: CoroutineScope,
    private val newSession: (ScopeWifi.State.Available) -> ScopeSession = { ScopeSession(NetworkLinks(it.network), scope) },
) {
    constructor(context: Context, scope: CoroutineScope) : this(ScopeWifi(context), DeviceStore(context), scope)

    val wifiState: StateFlow<ScopeWifi.State> = wifi.state

    private val _stats = MutableStateFlow(ScopeSession.Stats(status = "not connected"))
    val stats: StateFlow<ScopeSession.Stats> = _stats.asStateFlow()

    private val _book = MutableStateFlow(store.load())
    val book: StateFlow<DeviceBook> = _book.asStateFlow()

    private val _inRange = MutableStateFlow<List<ScopeWifi.Identity>?>(null)
    /** Scopes in the phone's last Wi-Fi scan, or null if scans aren't available; see [refreshInRange]. */
    val inRange: StateFlow<List<ScopeWifi.Identity>?> = _inRange.asStateFlow()

    private val gate = NetworkGate(scope, wifi::stop, STOP_TIMEOUT_MS)
    private var session: ScopeSession? = null
    private var statsJob: Job? = null
    private var remembered: Identify.Result? = null  // for the current network
    // The request the user last asked for; null after disconnect(). A fallback runs only for it,
    // so a late Unavailable can't file a request after Disconnect or leaving the app.
    private var wanted: Target? = null

    init {
        scope.launch {
            wifi.state.collect { state ->
                when (state) {
                    is ScopeWifi.State.Available -> startSession(state)
                    is ScopeWifi.State.Unavailable -> {
                        stopSession()
                        if (state.target == wanted) fallBack(state.target)
                    }
                    else -> stopSession()
                }
            }
        }
        scope.launch {
            // Stats change with every frame; only its beacon matters here.
            val beacons = _stats.map { it.beacon }.distinctUntilChanged()
            combine(wifi.state, wifi.identity, beacons) { state, id, beacon -> Triple(state, id, beacon) }
                .collect { (state, id, beacon) ->
                    if (state is ScopeWifi.State.Available) remember(state.target, id, beacon) else remembered = null
                }
        }
    }

    /** Join the last device by its exact SSID/BSSID, or show the picker if there is none. */
    fun connect() = request(_book.value.last?.let { Target.Exact(it.ssid, it.bssid) } ?: Target.AnyScope)

    fun connectTo(ssid: String, bssid: String?) = request(Target.Exact(ssid, bssid))

    /** Make [device] the one Connect goes to; if connected, switch to it now. */
    fun select(device: KnownDevice) {
        val active = wifi.state.value.let { it !is ScopeWifi.State.Idle && it !is ScopeWifi.State.Unavailable && it !is ScopeWifi.State.Failed }
        edit { it.select(device.key) }
        if (active) connect()
    }

    /** The picker, listing every "bebird*" network, to join a different scope. */
    fun pickDifferent() = request(Target.AnyScope)

    /** Stop the session, then release the network once its STOP has gone out. */
    fun disconnect() {
        wanted = null
        gate.disconnect(stopSession())
    }

    fun rename(device: KnownDevice, nickname: String?) = edit { it.rename(device.key, nickname) }

    fun forget(device: KnownDevice) {
        Log.i(TAG, "forget ${device.ssid} bssid=${device.bssid}")
        edit { it.forget(device.key) }
    }

    /** Re-read the phone's last scan (no new scan is started) off the main thread. */
    fun refreshInRange() {
        scope.launch(Dispatchers.IO) { _inRange.value = wifi.scopesInRange() }
    }

    private fun request(t: Target, why: String? = null) {
        disconnect()  // whatever is filed or streaming now; the gate orders the new request after it
        wanted = t
        gate.connect { wifi.start(t, why) }
    }

    /**
     * An exact request with a BSSID found nothing: the BSSID is wrong, or the scope is off, or
     * the user cancelled. Ask once more by SSID alone (Android may show its picker). Only if
     * that joins is the BSSID known to be wrong (see [remember]); if it also finds nothing,
     * the BSSID is kept for next time. A request without a BSSID has no fallback, so this
     * never loops.
     */
    private fun fallBack(target: Target) {
        val fallback = BssidFallback.afterUnavailable(target) ?: return
        Log.w(TAG, "exact request ssid=${fallback.ssid} bssid=${fallback.suspect} found nothing; retrying by SSID only")
        request(fallback, "fallback after bssid ${fallback.suspect} found nothing")
    }

    private fun edit(change: (DeviceBook) -> DeviceBook) {
        val book = _book.updateAndGet(change)
        store.save(book)
    }

    private fun remember(target: Target, id: ScopeWifi.Identity?, beacon: Beacon?) {
        val exact = target as? Target.Exact
        if (exact?.suspect != null && BssidFallback.afterJoin(_book.value, target) != _book.value) {
            // The fallback joined, so the scope was there and that BSSID was wrong.
            Log.w(TAG, "joined ${exact.ssid} by SSID after bssid ${exact.suspect} found nothing: rejecting that derived BSSID")
            edit { BssidFallback.afterJoin(it, target) }
        }
        val result = Identify.resolve(
            exactSsid = exact?.ssid, exactBssid = exact?.bssid,
            wifiSsid = id?.ssid, wifiBssid = id?.bssid,
            beaconSsid = beacon?.ssid, beaconMac = beacon?.mac,
        ) ?: return
        if (result == remembered) return
        remembered = result
        Log.i(TAG, "remembering ${result.ssid} (from ${result.ssidFrom}), bssid ${result.bssid ?: "unknown"}" +
            (result.bssidFrom?.let { " (from $it, ${if (result.bssidConfirmed) "confirmed" else "unconfirmed"})" } ?: "") +
            "; wifi info ${id?.ssid}/${id?.bssid}, beacon ${beacon?.ssid}/${beacon?.mac}")
        edit { it.seen(result.ssid, result.bssid, System.currentTimeMillis(), result.bssidConfirmed) }
    }

    private fun startSession(state: ScopeWifi.State.Available) {
        stopSession()  // its STOP is queued ahead of the new session's STOP and START (see ScopeSession)
        val s = newSession(state)
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
