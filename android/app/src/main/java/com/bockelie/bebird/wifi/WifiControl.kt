// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.wifi

import kotlinx.coroutines.flow.StateFlow

/** What ScopeConnection needs from [ScopeWifi]; the seam its tests replace. */
interface WifiControl {
    val state: StateFlow<ScopeWifi.State>
    val identity: StateFlow<ScopeWifi.Identity?>

    /** File the network request for [target]; [why] is for the log. */
    fun start(target: ScopeWifi.Target, why: String? = null)

    /**
     * The request is about to be released on purpose ([stop] follows once STOP has gone out): a
     * loss in between ends as Idle, so the screen doesn't flash CONNECTION LOST (#37).
     */
    fun markEnding()

    /** Release the request. */
    fun stop()

    /** Scopes in the last Wi-Fi scan, or null if scan results aren't available. */
    fun scopesInRange(): List<ScopeWifi.Identity>?
}
