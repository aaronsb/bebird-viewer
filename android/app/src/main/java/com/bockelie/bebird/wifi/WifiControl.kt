// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.wifi

import kotlinx.coroutines.flow.StateFlow

/** What ScopeConnection needs from [ScopeWifi]; the seam its tests replace. */
interface WifiControl {
    val state: StateFlow<ScopeWifi.State>
    val identity: StateFlow<ScopeWifi.Identity?>

    /** File the network request for [target]; [why] is for the log. */
    fun start(target: ScopeWifi.Target, why: String? = null)

    /** Release the request. */
    fun stop()

    /** Scopes in the last Wi-Fi scan, or null if scan results aren't available. */
    fun scopesInRange(): List<ScopeWifi.Identity>?
}
