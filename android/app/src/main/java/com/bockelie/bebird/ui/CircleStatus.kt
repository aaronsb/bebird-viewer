// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import androidx.annotation.StringRes
import com.bockelie.bebird.R
import com.bockelie.bebird.wifi.ScopeWifi

/**
 * What the empty image circle says (#32): the connection's state and what to do next. Each hint
 * names the button the screen shows in that state: Connect while idle, not found or failed;
 * Disconnect while connecting, connected or lost; Reconnect only once the network is joined.
 */
enum class CircleStatus(@StringRes val title: Int, @StringRes val hint: Int) {
    NOT_CONNECTED(R.string.circle_not_connected, R.string.circle_not_connected_hint),
    CONNECTING(R.string.circle_connecting, R.string.circle_connecting_hint),
    WAITING(R.string.circle_waiting, R.string.circle_waiting_hint),
    LOST(R.string.circle_lost, R.string.circle_lost_hint),
    NOT_FOUND(R.string.circle_not_found, R.string.circle_not_found_hint),
    FAILED(R.string.circle_failed, R.string.circle_failed_hint);

    companion object {
        /** The message for [wifi], or null once there is a picture to show ([hasFrame]). */
        fun of(wifi: ScopeWifi.State, hasFrame: Boolean): CircleStatus? = if (hasFrame) null else when (wifi) {
            ScopeWifi.State.Idle -> NOT_CONNECTED
            ScopeWifi.State.Requesting -> CONNECTING
            is ScopeWifi.State.Available -> WAITING
            ScopeWifi.State.Lost -> LOST
            is ScopeWifi.State.Unavailable -> NOT_FOUND
            is ScopeWifi.State.Failed -> FAILED
        }
    }
}
