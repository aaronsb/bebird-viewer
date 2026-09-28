// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.devices

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiIdsTest {
    @Test fun ssid() {
        assertEquals("bebird-ES-1", WifiIds.ssid("\"bebird-ES-1\""))
        assertEquals("bebird-ES-1", WifiIds.ssid("bebird-ES-1"))
        assertNull(WifiIds.ssid(WifiIds.REDACTED_SSID))
        assertNull(WifiIds.ssid("\"\""))
        assertNull(WifiIds.ssid(null))
    }

    @Test fun bssid() {
        assertEquals("C8:47:8C:0A:0B:0C", WifiIds.bssid("c8:47:8c:0a:0b:0c"))
        assertEquals("C8:47:8C:0A:0B:0C", WifiIds.bssid("C8-47-8C-0A-0B-0C"))
        assertEquals("C8:47:8C:0A:0B:0C", WifiIds.bssid("c8478c0a0b0c"))
        assertNull(WifiIds.bssid(WifiIds.REDACTED_BSSID))
        assertNull(WifiIds.bssid("00:00:00:00:00:00"))
        assertNull(WifiIds.bssid("…"))
        assertNull(WifiIds.bssid("c8:47:8c:0a:0b"))
        assertNull(WifiIds.bssid(null))
    }

    @Test fun isScope() {
        assertTrue(WifiIds.isScope("bebird-ES-1"))
        assertTrue(WifiIds.isScope("Bebird-X"))
        assertFalse(WifiIds.isScope("home"))
        assertFalse(WifiIds.isScope(null))
    }
}
