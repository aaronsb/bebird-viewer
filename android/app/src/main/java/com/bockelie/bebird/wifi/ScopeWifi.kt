// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.MacAddress
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.net.wifi.WifiNetworkSpecifier
import android.os.Build
import android.os.PatternMatcher
import android.os.SystemClock
import android.util.Log
import androidx.annotation.RequiresApi
import com.bockelie.bebird.devices.WifiIds
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/**
 * Joins a scope's open access point as an app-local network (WifiNetworkSpecifier), the
 * Android counterpart of wifi.py. The phone's default network is left alone: the process is
 * never bound to the scope's network; each socket is bound with [Network.bindSocket] instead.
 *
 * Callbacks arrive on ConnectivityManager's thread and may still arrive after [stop]; each one
 * checks, under [lock], that its request is still the current one and otherwise does nothing.
 */
class ScopeWifi(context: Context) {
    sealed interface State {
        data object Idle : State
        data object Requesting : State
        /** [target] is the request that joined it, so callers know which scope this is. */
        data class Available(val network: Network, val target: Target) : State
        data object Lost : State
        /** The user dismissed the system's network picker, or no scope was found. */
        data object Unavailable : State
        /** The request could not be filed, e.g. the permission was revoked. */
        data class Failed(val reason: String) : State
    }

    /** What to ask Android for. */
    sealed interface Target {
        /** Any "bebird*" network: Android shows its picker every time. */
        data object AnyScope : Target
        /**
         * This exact access point. With a BSSID, Android remembers the user's approval and joins
         * without the picker from the second time on; with only an SSID it still asks.
         */
        data class Exact(val ssid: String, val bssid: String?) : Target
    }

    /** The connected network as Android reports it; either field is null when redacted. */
    data class Identity(val ssid: String?, val bssid: String?)

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)
    private val wm = appContext.getSystemService(WifiManager::class.java)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    // Separate from state, so learning the identity never restarts the session.
    private val _identity = MutableStateFlow<Identity?>(null)
    val identity: StateFlow<Identity?> = _identity.asStateFlow()

    private val lock = Any()
    private var current: Callback? = null  // guarded by lock

    /** File the network request for [target]. Does nothing while a request is already filed. */
    fun start(target: Target) {
        // Built inside a try: a bad stored SSID or BSSID must fail this request, not the app.
        val request = try {
            val specifier = WifiNetworkSpecifier.Builder().apply {
                when (target) {
                    // PatternMatcher has no case-insensitive mode, so unlike wifi.py (which
                    // lowercases) a network named "Bebird..." would not match.
                    Target.AnyScope -> setSsidPattern(PatternMatcher(SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
                    is Target.Exact -> {
                        setSsid(target.ssid)
                        target.bssid?.let { setBssid(MacAddress.fromString(it)) }
                    }
                }
            }.build()
            NetworkRequest.Builder()
                .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)  // the scope has none
                .setNetworkSpecifier(specifier)
                .build()
        } catch (e: IllegalArgumentException) {
            Log.e(TAG, "invalid network $target", e)
            synchronized(lock) { if (current == null) _state.value = State.Failed("invalid network: ${e.message}") }
            return
        }
        val kind = when (target) {
            Target.AnyScope -> "prefix \"$SSID_PREFIX\" (picker expected)"
            is Target.Exact -> if (target.bssid != null) "exact ssid=${target.ssid} bssid=${target.bssid}"
                else "exact ssid=${target.ssid}, no bssid (picker expected)"
        }
        val cb = newCallback(target, kind)
        // Held across requestNetwork so a fast first callback waits until `current` is set.
        synchronized(lock) {
            if (current != null) return
            try {
                cm.requestNetwork(request, cb)
            } catch (e: RuntimeException) {  // SecurityException, TooManyRequestsException
                Log.e(TAG, "network request failed ($kind)", e)
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
                return
            }
            current = cb
            _identity.value = null
            _state.value = State.Requesting
        }
        Log.i(TAG, "request: $kind")
    }

    /** Release the request, which drops the phone off the scope's network. */
    fun stop() {
        val cb = synchronized(lock) {
            current.also {
                current = null
                _state.value = State.Idle
                _identity.value = null
            }
        } ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
        Log.i(TAG, "request released")
    }

    /**
     * Scope networks in the phone's last Wi-Fi scan, as (SSID, BSSID) with BSSID null where
     * redacted. Doesn't start a scan. Empty (and logged) if the permission doesn't allow it.
     */
    @Suppress("DEPRECATION")  // ScanResult.SSID: its replacement getWifiSsid() is API 33+
    fun scopesInRange(): List<Identity> = try {
        val all = wm.scanResults.orEmpty()
        val scopes = all.filter { WifiIds.isScope(it.SSID) }.map { Identity(WifiIds.ssid(it.SSID), WifiIds.bssid(it.BSSID)) }
        Log.i(TAG, "scan results: ${all.size} networks, scopes: " +
            scopes.joinToString { "${it.ssid}/${it.bssid ?: "bssid redacted"}" }.ifEmpty { "none" })
        scopes.distinct()
    } catch (e: SecurityException) {
        Log.w(TAG, "scan results not allowed: ${e.message}")
        emptyList()
    }

    private fun newCallback(target: Target, kind: String): Callback {
        val now = SystemClock.elapsedRealtime()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Callback(target, kind, now, ConnectivityManager.NetworkCallback.FLAG_INCLUDE_LOCATION_INFO)
        } else {
            Callback(target, kind, now)
        }
    }

    // On API 31+ SSID and BSSID in WifiInfo are redacted unless the callback asks for location
    // info, and even then only if the app holds a location permission. We ask, and log what comes.
    // The flags constructor is API 31, hence two constructors; see newCallback().
    private inner class Callback : ConnectivityManager.NetworkCallback {
        val target: Target
        val kind: String
        val requestedAt: Long

        constructor(target: Target, kind: String, requestedAt: Long) : super() {
            this.target = target
            this.kind = kind
            this.requestedAt = requestedAt
        }

        @RequiresApi(Build.VERSION_CODES.S)
        constructor(target: Target, kind: String, requestedAt: Long, flags: Int) : super(flags) {
            this.target = target
            this.kind = kind
            this.requestedAt = requestedAt
        }

        /** Run [block] only while this callback's request is the current one. */
        private inline fun ifCurrent(block: () -> Unit) = synchronized(lock) { if (current === this) block() }

        override fun onAvailable(network: Network) = ifCurrent {
            val ms = SystemClock.elapsedRealtime() - requestedAt
            // A picker takes the user seconds; an approved exact request joins in about one.
            Log.i(TAG, "available: $network after $ms ms (request: $kind)")
            _state.value = State.Available(network, target)
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = ifCurrent {
            val info = caps.transportInfo as? WifiInfo ?: return@ifCurrent
            @Suppress("DEPRECATION")  // getSSID(): its replacement getWifiSsid() is API 33+ and not public
            val rawSsid = info.ssid
            val id = Identity(WifiIds.ssid(rawSsid), WifiIds.bssid(info.bssid))
            if (id != _identity.value) {
                Log.i(TAG, "wifi info: raw ssid=$rawSsid raw bssid=${info.bssid} -> $id")
                _identity.value = id
            }
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) = ifCurrent {
            val local = lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
            val gateways = lp.routes.mapNotNull { it.gateway as? Inet4Address }
            Log.i(TAG, "link $network: iface=${lp.interfaceName} local=$local gateways=$gateways")
            if (local.none { it.hostAddress?.startsWith(SCOPE_NET) == true }) {
                Log.w(TAG, "local address not in ${SCOPE_NET}0/24; is this a scope?")
            }
        }

        override fun onLost(network: Network) = ifCurrent {
            Log.i(TAG, "lost: $network")
            _state.value = State.Lost
        }

        override fun onUnavailable() = ifCurrent {
            val ms = SystemClock.elapsedRealtime() - requestedAt
            Log.i(TAG, "unavailable after $ms ms (request: $kind)")
            _state.value = State.Unavailable
            current = null  // the framework has already released the request
        }
    }

    companion object {
        const val TAG = "BebirdSpike"
        const val SSID_PREFIX = "bebird"
        private const val SCOPE_NET = "192.168.5."
    }
}
