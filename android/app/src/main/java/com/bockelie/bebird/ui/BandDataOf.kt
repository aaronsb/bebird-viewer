// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import com.bockelie.bebird.band.BandData
import com.bockelie.bebird.connection.ScopeConnection
import com.bockelie.bebird.devices.KnownDevice
import com.bockelie.bebird.scope.ScopeSession
import java.time.LocalDateTime

/**
 * How the band and saved files name the scope. By default only its model ("ES"): the SSID's
 * suffix is a per-unit ID (often part of the MAC), and pictures get shared. With [showScopeId]
 * (off unless the user turns it on) the SSID without "bebird-", e.g. "ES-123456". Nicknames
 * stay in the device list and never go into files.
 */
fun deviceName(device: KnownDevice?, beaconModel: String?, showScopeId: Boolean): String? {
    val id = device?.ssid?.removePrefix("bebird-") ?: return beaconModel
    return if (showScopeId) id else beaconModel ?: id.substringBefore('-').ifEmpty { null }
}

/** The band's values from the connection's state; shared by the screen and captures so they match. */
fun bandDataOf(
    stats: ScopeSession.Stats,
    light: ScopeConnection.Light,
    shownRoll: Int,
    trim: Int,
    device: KnownDevice?,
    online: Boolean,
    label: String,
    now: LocalDateTime,
    showScopeId: Boolean,
) = BandData(
    batteryPercent = stats.battery?.percent,
    charging = stats.battery?.isCharging == true,
    lightPercent = light.level.takeIf { online },
    roll = shownRoll.takeIf { stats.frame != null },
    trim = trim,
    fps = stats.fps.takeIf { online },
    droppedPerSecond = if (online) stats.droppedPerSecond else 0,
    device = if (online) deviceName(device, stats.beacon?.model, showScopeId) else null,
    time = now,
    label = label.ifEmpty { null },
)
