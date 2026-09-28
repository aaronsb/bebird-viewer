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
}
