// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.connection

import com.bockelie.bebird.devices.DeviceBook
import com.bockelie.bebird.wifi.ScopeWifi.Target

/**
 * What happens when an exact request finds nothing. Pure, so the policy is testable:
 * - an exact request with a BSSID ends Unavailable: ask once more by SSID alone ([afterUnavailable]),
 *   and keep the BSSID for now, since the scope may just be off or the user cancelled;
 * - that SSID-only request joins: the scope was there, so the BSSID was wrong ([afterJoin]
 *   disproves it, if it was only derived);
 * - it ends Unavailable too: nothing is proven, the BSSID stays, and there is no third try.
 */
object BssidFallback {
    fun afterUnavailable(target: Target): Target.Exact? = target.fallback() as? Target.Exact

    fun afterJoin(book: DeviceBook, target: Target): DeviceBook =
        (target as? Target.Exact)?.suspect?.let(book::disprove) ?: book
}
