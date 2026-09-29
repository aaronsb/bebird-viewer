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
 * lost or on [disconnect]; on [disconnect] the network request is released only after the
 * session's STOP has gone out (see [NetworkGate]), and on a loss [ScopeWifi] has released it
 * already (#37). One per app (see [com.bockelie.bebird.BebirdApp]), shared by
 * the screen and the service that keeps it through the background grace period (#18, see
 * [GraceKeeper]). Not thread-safe: call it from the main thread.
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
    // The request connectOnLaunch() made: if it finds nothing (scope off), no fallback, since
    // that could show Android's picker nobody asked for.
    private var quiet: Target? = null
    /** Called when the connection lets go on its own: lost, or not found while not decoding (see [GraceKeeper]). */
    var onLetGo: (() -> Unit)? = null

    /** A connection was asked for and not ended since: something to keep in the background. */
    val isWanted: Boolean get() = wanted != null

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
                        if (state.target == wanted) {
                            // In the background a new request could show Android's picker over
                            // another app: let go instead (the grace period then just ends).
                            when {
                                !decoding -> {
                                    disconnect()
                                    onLetGo?.invoke()
                                }
                                state.target == quiet -> Log.i(TAG, "last device not found at launch; staying idle")
                                else -> fallBack(state.target)
                            }
                        }
                    }
                    ScopeWifi.State.Lost -> lost()
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

    /**
     * At launch (if [Settings.autoConnect]): connect to the last device, as [connect] does, but
     * only by its exact BSSID, so Android shows no picker, and without the fallback if it isn't
     * found. Nothing if no such device is remembered, or a connection is already wanted (kept
     * through the grace period).
     */
    fun connectOnLaunch() {
        if (!settings.autoConnect || wanted != null) return
        val sincePowerOff = clock() - settings.poweredOffAt
        if (sincePowerOff in 0 until POWER_OFF_QUIET_MS) {
            // it may still be shutting down: a new session would send STOP/START after its 66 3E
            Log.i(TAG, "powered off $sincePowerOff ms ago: not connecting at launch")
            return
        }
        val last = _book.value.last?.takeIf { it.bssid != null } ?: return
        Log.i(TAG, "connecting to the last device at launch")
        val t = Target.Exact(last.ssid, last.bssid)
        request(t)
        quiet = t
    }

    fun connectTo(ssid: String, bssid: String?) = request(Target.Exact(ssid, bssid))

    /** Make [device] the one Connect goes to; if connected, switch to it now. */
    fun select(device: KnownDevice) {
        val active = wifi.state.value.filed
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
        wifi.markEnding()
        gate.disconnect(stopSession())
    }

    /** Wait up to [timeoutMs] for the network release after [disconnect] or [powerOff]. */
    fun awaitRelease(timeoutMs: Long): Boolean = gate.awaitRelease(timeoutMs)

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
        wifi.markEnding()  // the scope drops its network after 66 3E, maybe before the release
        gate.disconnect(stopSession(ScopeSession::powerOff, "powered off"))
        settings.poweredOffAt = clock()
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
        quiet = null
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

    /**
     * The network went (scope off, battery, out of range) and [ScopeWifi] has released its
     * request: end any session without a STOP, which has no network to go over, and want
     * nothing more, so Connect starts afresh. The device stays remembered. A loss can come with
     * no session, since the state flow may skip Available. Ignored with nothing wanted (already
     * handled, or after [disconnect]) or with a new request waiting on the gate: a loss published
     * just before [request], which marks its old request ending ([WifiControl.markEnding]).
     */
    private fun lost() {
        val dropped = stopSession(ScopeSession::drop, "connection lost")
        if (dropped == null && (wanted == null || gate.isConnecting)) return
        Log.i(TAG, "connection lost; the request is released")
        wanted = null
        quiet = null
        onLetGo?.invoke()
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

    /** Whether sessions decode frames; off while the app is away (see [GraceKeeper]). */
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
        // No connecting at launch this soon after a power-off (the process may have been restarted).
        private const val POWER_OFF_QUIET_MS = 10_000L
    }
}
