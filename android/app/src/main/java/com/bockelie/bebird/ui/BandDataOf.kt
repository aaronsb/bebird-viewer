// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import com.bockelie.bebird.band.BandData
import com.bockelie.bebird.connection.ScopeConnection
import com.bockelie.bebird.devices.KnownDevice
import com.bockelie.bebird.scope.ScopeSession
import java.time.LocalDateTime

/** How the band (and capture metadata) names the device: its nickname, or the SSID without "bebird-". */
fun deviceName(device: KnownDevice?): String? = device?.let { it.nickname ?: it.ssid.removePrefix("bebird-") }

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
) = BandData(
    batteryPercent = stats.battery?.percent,
    charging = stats.battery?.state == 2,
    lightPercent = light.level.takeIf { online },
    roll = shownRoll.takeIf { stats.frame != null },
    trim = trim,
    fps = stats.fps.takeIf { online },
    droppedPerSecond = if (online) stats.droppedPerSecond else 0,
    device = deviceName(device?.takeIf { online }),
    time = now,
    label = label.ifEmpty { null },
)
