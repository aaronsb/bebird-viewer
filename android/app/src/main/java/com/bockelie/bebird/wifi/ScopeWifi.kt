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
import kotlinx.coroutines.flow.StateFlow
import java.net.Inet4Address

/**
 * Joins a scope's open access point as an app-local network (WifiNetworkSpecifier), the
 * Android counterpart of wifi.py. The phone's default network is left alone: the process is
 * never bound to the scope's network; each socket is bound with [Network.bindSocket] instead.
 *
 * Callbacks arrive on ConnectivityManager's thread and may still arrive after [stop]; [slot]
 * ignores any but the current request's. A loss releases the request here and now (#37): the
 * scope's network doesn't come back to an old request, so Connect files a new one.
 */
class ScopeWifi(context: Context) : WifiControl {
    sealed interface State {
        data object Idle : State
        data object Requesting : State
        /** [target] is the request that joined it, so callers know which scope this is. */
        data class Available(val network: Network, val target: Target) : State
        /**
         * The network went (scope off, battery, out of range) and its request has been released;
         * until the next [start] or [stop], so the screen can tell it from a deliberate Disconnect.
         */
        data object Lost : State
        /** The user dismissed the system's picker, or [target] was not found. */
        data class Unavailable(val target: Target) : State
        /** The request could not be filed, e.g. the permission was revoked. */
        data class Failed(val reason: String) : State

        /** A request is filed and not yet released: there is something for Disconnect to end. */
        val filed: Boolean get() = this == Requesting || this is Available
    }

    /** What to ask Android for. */
    sealed interface Target {
        /** Any "bebird*" network: Android shows its picker every time. */
        data object AnyScope : Target
        /**
         * This exact access point. With a BSSID, Android remembers the user's approval and joins
         * without the picker from the second time on; with only an SSID it still asks.
         */
        data class Exact(
            val ssid: String,
            val bssid: String?,
            /** On a fallback: the BSSID that found nothing. If this joins, that BSSID was wrong. */
            val suspect: String? = null,
        ) : Target

        /** What to ask for when this found nothing: the SSID alone, once; null if nothing is left. */
        fun fallback(): Target? = (this as? Exact)?.takeIf { it.bssid != null }?.let { Exact(it.ssid, null, suspect = it.bssid) }
    }

    /** The connected network as Android reports it; either field is null when redacted. */
    data class Identity(val ssid: String?, val bssid: String?)

    private val appContext = context.applicationContext
    private val cm = appContext.getSystemService(ConnectivityManager::class.java)
    private val wm = appContext.getSystemService(WifiManager::class.java)
    private val slot = RequestSlot<Callback>()
    override val state: StateFlow<State> = slot.state
    override val identity: StateFlow<Identity?> = slot.identity

    /** File the network request for [target]. Does nothing while a request is already filed. */
    override fun start(target: Target, why: String?) {
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
            slot.failed("invalid network: ${e.message}")
            return
        }
        val kind = when (target) {
            Target.AnyScope -> "prefix \"$SSID_PREFIX\" (picker expected)"
            is Target.Exact -> if (target.bssid != null) "exact ssid=${target.ssid} bssid=${target.bssid}"
                else "exact ssid=${target.ssid}, no bssid (picker expected)"
        } + (why?.let { ", $it" } ?: "")
        val cb = newCallback(target, kind)
        try {
            if (!slot.file(cb) { cm.requestNetwork(request, cb) }) return
        } catch (e: RuntimeException) {  // SecurityException, TooManyRequestsException
            Log.e(TAG, "network request failed ($kind)", e)
            slot.failed(e.message ?: e.javaClass.simpleName)
            return
        }
        Log.i(TAG, "request: $kind")
    }

    override fun markEnding() = slot.markEnding()

    /** Release the request, which drops the phone off the scope's network. */
    override fun stop() {
        val cb = slot.release() ?: return
        runCatching { cm.unregisterNetworkCallback(cb) }
        Log.i(TAG, "request released")
    }

    /**
     * Scope networks in the phone's last Wi-Fi scan, as (SSID, BSSID) with BSSID null where
     * redacted. Doesn't start a scan. Null when scan results aren't available to the app
     * (refused, or no networks at all, which is what Android returns without location access).
     */
    @Suppress("DEPRECATION")  // ScanResult.SSID: its replacement getWifiSsid() is API 33+
    override fun scopesInRange(): List<Identity>? {
        val all = try {
            wm.scanResults.orEmpty()
        } catch (e: SecurityException) {
            Log.w(TAG, "scan results not allowed: ${e.message}")
            return null
        }
        if (all.isEmpty()) {
            Log.i(TAG, "scan results: none (no location access?); hiding in-range info")
            return null
        }
        val scopes = all.filter { WifiIds.isScope(it.SSID) }.map { Identity(WifiIds.ssid(it.SSID), WifiIds.bssid(it.BSSID)) }
        Log.i(TAG, "scan results: ${all.size} networks, scopes: " +
            scopes.joinToString { "${it.ssid}/${it.bssid ?: "bssid redacted"}" }.ifEmpty { "none" })
        return scopes.distinct()
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

        override fun onAvailable(network: Network) {
            if (!slot.isCurrent(this)) return
            val ms = SystemClock.elapsedRealtime() - requestedAt
            // A picker takes the user seconds; an approved exact request joins in about one.
            Log.i(TAG, "available: $network after $ms ms (request: $kind)")
            slot.update(this, State.Available(network, target))
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            val info = caps.transportInfo as? WifiInfo ?: return
            @Suppress("DEPRECATION")  // getSSID(): its replacement getWifiSsid() is API 33+ and not public
            val rawSsid = info.ssid
            val id = Identity(WifiIds.ssid(rawSsid), WifiIds.bssid(info.bssid))
            if (slot.identify(this, id)) Log.i(TAG, "wifi info: raw ssid=$rawSsid raw bssid=${info.bssid} -> $id")
        }

        override fun onLinkPropertiesChanged(network: Network, lp: LinkProperties) {
            if (!slot.isCurrent(this)) return
            val local = lp.linkAddresses.map { it.address }.filterIsInstance<Inet4Address>()
            val gateways = lp.routes.mapNotNull { it.gateway as? Inet4Address }
            Log.i(TAG, "link $network: iface=${lp.interfaceName} local=$local gateways=$gateways")
            if (local.none { it.hostAddress?.startsWith(SCOPE_NET) == true }) {
                Log.w(TAG, "local address not in ${SCOPE_NET}0/24; is this a scope?")
            }
        }

        override fun onLost(network: Network) {
            if (!slot.lost(this)) return
            Log.i(TAG, "lost: $network; releasing the request")
            runCatching { cm.unregisterNetworkCallback(this) }
        }

        override fun onUnavailable() {
            if (!slot.isCurrent(this)) return
            val ms = SystemClock.elapsedRealtime() - requestedAt
            Log.i(TAG, "unavailable after $ms ms (request: $kind)")
            slot.unavailable(this, target)  // the framework has already released the request
        }
    }

    companion object {
        const val TAG = "BebirdSpike"
        const val SSID_PREFIX = "bebird"
        private const val SCOPE_NET = "192.168.5."
    }
}
