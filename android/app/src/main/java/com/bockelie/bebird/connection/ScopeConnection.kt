// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.connection

import android.content.Context
import android.util.Log
import com.bockelie.bebird.control.LightControl
import com.bockelie.bebird.control.RollFilter
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
import com.bockelie.bebird.settings.MemoryKeyValue
import com.bockelie.bebird.settings.Settings
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
import kotlinx.coroutines.delay
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
    private val settings: Settings = Settings(MemoryKeyValue()),
    private val clock: () -> Long = { System.nanoTime() / 1_000_000 },
    lightTiming: LightControl.Timing = LightControl.Timing(),
    private val newSession: (ScopeWifi.State.Available) -> ScopeSession = { ScopeSession(NetworkLinks(it.network), scope) },
) {
    constructor(context: Context, scope: CoroutineScope, settings: Settings) :
        this(ScopeWifi(context), DeviceStore(context), scope, settings)

    /** The light as the UI shows it: the level (0 % is off) and whether the scope confirmed it. */
    data class Light(val level: Int, val status: LightControl.Status)

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
    private var sessionJobs: Job? = null  // following the session's stats and light replies
    private var streaming = false          // the current session has shown a frame
        set(value) {
            field = value
            _streaming.value = value
        }
    private val _streaming = MutableStateFlow(false)
    /** The current session has shown a frame (video may have stalled since): what [powerOff] needs. */
    val isStreaming: StateFlow<Boolean> = _streaming.asStateFlow()
    private var remembered: Identify.Result? = null  // for the current network
    // The request the user last asked for; null after disconnect(). A fallback runs only for it,
    // so a late Unavailable can't file a request after Disconnect or leaving the app.
    private var wanted: Target? = null

    private val light = LightControl(settings.light, settings.lightBeforeOff, lightTiming)
    private var lightJob: Job? = null  // wakes pumpLight() when the light control is next due
    private val _light = MutableStateFlow(Light(light.level, light.status))
    val lightState: StateFlow<Light> = _light.asStateFlow()

    private val roll = RollFilter()
    private val _shownRoll = MutableStateFlow(0)
    /** The roll angle to rotate by: the sensor's, with a 3° deadband. */
    val shownRoll: StateFlow<Int> = _shownRoll.asStateFlow()

    init {
        scope.launch {
            wifi.state.collect { state ->
                when (state) {
                    // Not after disconnect() or powerOff(): the network is on its way out, and a
                    // new session would send STOP/START after the old one's STOP (or 66 3E).
                    is ScopeWifi.State.Available -> if (wanted != null) startSession(state)
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

    /** The light slider moved (0-100 %); sent once it is still. */
    fun setLight(percent: Int) {
        light.set(percent, clock())
        pumpLight()
    }

    /** Light off, or back on to the last level. */
    fun toggleLight() {
        light.toggle(clock())
        pumpLight()
    }

    /** Restart the video session on the same network, as the desktop's Reconnect does. */
    fun reconnect() {
        if (wanted == null) return  // disconnect() or powerOff() in progress: the network is being released
        val state = wifi.state.value as? ScopeWifi.State.Available ?: return
        Log.i(TAG, "reconnect")
        startSession(state)
    }

    /** Stop the session, then release the network once its STOP has gone out. */
    fun disconnect() {
        wanted = null
        gate.disconnect(stopSession())
    }

    /**
     * Switch the scope off (see [ScopeSession.powerOff]), then release the network once that
     * has gone out, as [disconnect] does; the device stays remembered. Only once video has
     * started, so the scope is known to be there and listening: otherwise nothing is sent
     * and this returns false. For the menu item, and for the end of the background grace
     * period (#18), which disconnects instead when this returns false.
     */
    fun powerOff(): Boolean {
        if (session == null || !streaming) return false
        Log.i(TAG, "power off")
        wanted = null
        gate.disconnect(stopSession(ScopeSession::powerOff, "powered off"))
        return true
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

    /** Whether sessions decode frames; off while the app is covered (see ExternalLaunch). */
    var decoding = true
        set(value) {
            field = value
            session?.decoding = value
        }

    private fun startSession(state: ScopeWifi.State.Available) {
        stopSession()  // its STOP is queued ahead of the new session's STOP and START (see ScopeSession)
        val s = newSession(state)
        s.decoding = decoding
        session = s
        streaming = false
        sessionJobs = scope.launch {
            launch {
                s.stats.collect {
                    _stats.value = it
                    if (it.frame != null) _shownRoll.value = roll.update(it.angle)
                    if (!streaming && it.frames > 0) {
                        streaming = true
                        light.onStreaming(clock())  // re-apply the level once video is up
                        pumpLight()
                    }
                }
            }
            launch { s.lightReports.collect { light.onReported(it) } }
        }
        s.start()
    }

    private fun stopSession(end: (ScopeSession) -> Future<*> = ScopeSession::stop, status: String = "stopped"): Future<*>? {
        val s = session ?: return null
        session = null
        streaming = false
        sessionJobs?.cancel()
        sessionJobs = null
        pumpLight()  // offline now: drop any check in progress
        _stats.value = s.stats.value.copy(frame = null, fps = 0, status = status, beacon = null)
        return end(s)
    }

    /**
     * Send what the light control asks for, publish its state, remember a level once it is
     * sent (or dropped, while offline), and wake up again when the control is next due.
     */
    private fun pumpLight() {
        val s = session
        for (cmd in light.poll(clock(), online = s != null && streaming)) {
            when (cmd) {
                is LightControl.Command.Set -> s?.setLight(cmd.raw)
                LightControl.Command.Query -> s?.queryLight()
            }
        }
        if (light.status != LightControl.Status.Pending && light.level != settings.light) {
            settings.light = light.level
            settings.lightBeforeOff = light.beforeOff
        }
        _light.value = Light(light.level, light.status)
        lightJob?.cancel()
        lightJob = light.nextDue()?.let { due ->
            scope.launch {
                delay(maxOf(0, due - clock()))
                pumpLight()
            }
        }
    }

    companion object {
        private const val TAG = "BebirdSpike"
        // A dead link can't hold up the release for longer than this.
        private const val STOP_TIMEOUT_MS = 1000L
    }
}
