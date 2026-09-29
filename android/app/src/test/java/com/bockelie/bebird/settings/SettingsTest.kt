// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsTest {
    @Test fun defaults() {
        val s = Settings(MemoryKeyValue())
        assertEquals(100, s.light)
        assertEquals(100, s.lightBeforeOff)
        assertEquals(true, s.autoRotate)
        assertEquals(0, s.trim)
        assertEquals(ThemeMode.SYSTEM, s.theme)
    }

    @Test fun roundTripAndClamping() {
        val kv = MemoryKeyValue()
        Settings(kv).apply { light = 0; lightBeforeOff = 42; autoRotate = false; trim = -45; theme = ThemeMode.DARK }
        val s = Settings(kv)
        assertEquals(0, s.light)
        assertEquals(42, s.lightBeforeOff)
        assertEquals(false, s.autoRotate)
        assertEquals(-45, s.trim)
        assertEquals(ThemeMode.DARK, s.theme)
        kv.putInt("light", 500); kv.putInt("trim", 999); kv.putString("theme", "PURPLE")
        assertEquals(100, s.light)
        assertEquals(180, s.trim)
        assertEquals(ThemeMode.SYSTEM, s.theme)
    }

    @Test fun gracePeriodDefaultsToAMinuteWithPowerOff() {
        val kv = MemoryKeyValue()
        val s = Settings(kv)
        assertEquals(60, s.graceSeconds)
        assertEquals(true, s.powerOffAfterGrace)
        s.graceSeconds = 0
        assertEquals(0, s.graceSeconds)
        s.graceSeconds = 600
        s.powerOffAfterGrace = false
        assertEquals(600, Settings(kv).graceSeconds)
        assertEquals(false, Settings(kv).powerOffAfterGrace)
        kv.putInt("grace_s", 45)  // not one of the choices
        assertEquals(60, s.graceSeconds)
    }
}
