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
 * Callbacks arrive on ConnectivityManager's thread and may still arrive after [stop]; each one
 * checks, under [lock], that its request is still the current one and otherwise does nothing.
 */
class ScopeWifi(context: Context) {
    sealed interface State {
        data object Idle : State
        data object Requesting : State
        data class Available(val network: Network) : State
        data object Lost : State
        /** The user dismissed the system's network picker, or no scope was found. */
        data object Unavailable : State
        /** The request could not be filed, e.g. the permission was revoked. */
        data class Failed(val reason: String) : State
    }

    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val lock = Any()
    private var current: ConnectivityManager.NetworkCallback? = null  // guarded by lock

    /** File the network request; the system shows its picker for matching networks. */
    fun start() {
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
        val cb = Callback()
        // Held across requestNetwork so a fast first callback waits until `current` is set.
        synchronized(lock) {
            if (current != null) return
            try {
                cm.requestNetwork(request, cb)
            } catch (e: RuntimeException) {  // SecurityException, TooManyRequestsException
                Log.e(TAG, "network request failed", e)
                _state.value = State.Failed(e.message ?: e.javaClass.simpleName)
                return
            }
            current = cb
            _state.value = State.Requesting
        }
        Log.i(TAG, "requesting network: ssid prefix \"$SSID_PREFIX\", no internet capability")
    }

    /** Release the request, which drops the phone off the scope's network. */
    fun stop() {
        val cb = synchronized(lock) {
            current.also {
                current = null
                _state.value = State.Idle
            }
        } ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
        Log.i(TAG, "request released")
    }

    private inner class Callback : ConnectivityManager.NetworkCallback() {
        /** Run [block] only while this callback's request is the current one. */
        private inline fun ifCurrent(block: () -> Unit) = synchronized(lock) { if (current === this) block() }

        override fun onAvailable(network: Network) = ifCurrent {
            Log.i(TAG, "available: $network")
            _state.value = State.Available(network)
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
            Log.i(TAG, "unavailable")
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
