// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.wifi

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier
import android.os.PatternMatcher
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.net.Inet4Address

/**
 * Joins a scope's open access point as an app-local network (WifiNetworkSpecifier), the
 * Android counterpart of wifi.py. The phone's default network is left alone: the process is
 * never bound to the scope's network; each socket is bound with [Network.bindSocket] instead.
 *
 * Callbacks arrive on ConnectivityManager's thread; [state] is safe to read from anywhere.
 */
class ScopeWifi(context: Context) {
    sealed interface State {
        data object Idle : State
        data object Requesting : State
        data class Available(val network: Network) : State
        data object Lost : State
        /** The user dismissed the system's network picker, or no scope was found. */
        data object Unavailable : State
    }

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()
    @Volatile private var callback: ConnectivityManager.NetworkCallback? = null

    /** File the network request; the system shows its picker for matching networks. */
    fun start() {
        if (callback != null) return
        // Scopes are "bebird-<model>-<number>". PatternMatcher has no case-insensitive mode, so
        // unlike wifi.py (which lowercases) a network named "Bebird..." would not match.
        val specifier = WifiNetworkSpecifier.Builder()
            .setSsidPattern(PatternMatcher(SSID_PREFIX, PatternMatcher.PATTERN_PREFIX))
            .build()
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)  // the scope has none
            .setNetworkSpecifier(specifier)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                Log.i(TAG, "available: $network")
                _state.value = State.Available(network)
            }

            override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
                val local = lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
                val gateways = lp.routes.mapNotNull { it.gateway as? Inet4Address }
                Log.i(TAG, "link $network: iface=${lp.interfaceName} local=$local gateways=$gateways")
                if (local.none { it.hostAddress?.startsWith(SCOPE_NET) == true }) {
                    Log.w(TAG, "local address not in ${SCOPE_NET}0/24; is this a scope?")
                }
            }

            override fun onLost(network: Network) {
                Log.i(TAG, "lost: $network")
                _state.value = State.Lost
            }

            override fun onUnavailable() {
                Log.i(TAG, "unavailable")
                _state.value = State.Unavailable
                callback = null  // the framework has already released the request
            }
        }
        callback = cb
        _state.value = State.Requesting
        Log.i(TAG, "requesting network: ssid prefix \"$SSID_PREFIX\", no internet capability")
        cm.requestNetwork(request, cb)
    }

    /** Release the request, which drops the phone off the scope's network. */
    fun stop() {
        callback?.let {
            runCatching { cm.unregisterNetworkCallback(it) }
            Log.i(TAG, "request released")
        }
        callback = null
        _state.value = State.Idle
    }

    companion object {
        const val TAG = "BebirdSpike"
        const val SSID_PREFIX = "bebird"
        private const val SCOPE_NET = "192.168.5."
    }
}
