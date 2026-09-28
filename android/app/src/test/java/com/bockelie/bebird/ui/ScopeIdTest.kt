// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import com.bockelie.bebird.band.BandLayout
import com.bockelie.bebird.band.Fonts
import com.bockelie.bebird.capture.ExifWriter
import com.bockelie.bebird.capture.SnapshotMeta
import com.bockelie.bebird.connection.ScopeConnection
import com.bockelie.bebird.control.LightControl
import com.bockelie.bebird.devices.KnownDevice
import com.bockelie.bebird.proto.Beacon
import com.bockelie.bebird.scope.ScopeSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime

/** The scope's unique ID (its SSID suffix) stays out of the band and files unless asked for. */
class ScopeIdTest {
    private val suffix = "123456"
    private val device = KnownDevice("bebird-ES-$suffix", "AA:BB:CC:DD:EE:FF", nickname = "Otto", lastSeen = 0)
    private val stats = ScopeSession.Stats(beacon = Beacon("bebird-ES-$suffix", "aa:bb:cc:dd:ee:fe", "ES"))
    private val now = LocalDateTime.of(2026, 9, 28, 14, 3, 7)

    private fun band(showId: Boolean) =
        bandDataOf(stats, ScopeConnection.Light(50, LightControl.Status.Idle), 10, 0, device, true, "", now, showId)

    private fun meta(showId: Boolean) = SnapshotMeta(
        taken = ZonedDateTime.of(now, ZoneOffset.UTC), roll = 10, rotationApplied = 10, autoRotate = true, trim = 0,
        lightPercent = 50, lightRaw = 36, batteryPercent = null, batteryState = null, fps = 10, zoom = 1.0, zoomed = false,
        label = null, device = band(showId).device, model = "ES",
    )

    private fun bandText(showId: Boolean): String {
        val placed = BandLayout.place(band(showId), Fonts.source)
        return String(placed.map { it.codepoint }.toIntArray(), 0, placed.size)
    }

    private fun ByteArray.contains(s: String, charset: java.nio.charset.Charset): Boolean {
        val needle = s.toByteArray(charset)
        return (0..size - needle.size).any { i -> needle.indices.all { this[i + it] == needle[it] } }
    }

    @Test fun byDefaultOnlyTheModel() {
        assertEquals("ES", deviceName(device, "ES", showScopeId = false))
        assertEquals("ES", deviceName(device, null, showScopeId = false))  // no beacon yet: from the SSID
        assertEquals("ES", deviceName(null, "ES", showScopeId = false))
        assertNull(deviceName(null, null, showScopeId = false))
    }

    @Test fun whenAskedTheFullId() {
        assertEquals("ES-$suffix", deviceName(device, "ES", showScopeId = true))
    }

    @Test fun theNicknameNeverGoesIntoFiles() {
        for (on in listOf(false, true)) {
            assertFalse("Otto" in (band(on).device ?: ""))
            assertFalse("Otto" in meta(on).json())
        }
    }

    @Test fun defaultKeepsTheIdOutOfBandJsonAndExif() {
        assertEquals("ES", band(false).device)
        assertFalse(bandText(false).contains(suffix))
        assertFalse(meta(false).json().contains(suffix))
        val exif = ExifWriter.segment(meta(false))
        assertFalse(exif.contains(suffix, Charsets.US_ASCII))
        assertFalse(exif.contains(suffix, Charsets.UTF_16LE))
    }

    @Test fun withTheOptionTheIdIsInBandJsonAndExif() {
        assertEquals("ES-$suffix", band(true).device)
        assertTrue(bandText(true).contains("ES-$suffix"))
        assertTrue(meta(true).json().contains("ES-$suffix"))
        assertTrue(ExifWriter.segment(meta(true)).contains(suffix, Charsets.US_ASCII))
    }
}
