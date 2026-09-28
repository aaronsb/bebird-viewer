// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LabelsTest {
    private fun cps(s: String) = s.codePointCount(0, s.length)

    @Test fun shortTextIsUnchanged() {
        assertEquals("left ear", Labels.limit("left ear"))
        assertEquals("", Labels.limit(""))
    }

    @Test fun limitsCodePointsNotUtf16Units() {
        // 60 astral characters are 120 UTF-16 units but only 60 code points: all kept
        val sixty = "😀".repeat(60)
        assertEquals(sixty, Labels.limit(sixty))
        val cut = Labels.limit("😀".repeat(61))
        assertEquals(60, cps(cut))
        assertTrue(!Character.isHighSurrogate(cut.last()))  // no half emoji at the end
    }

    @Test fun neverSplitsAGrapheme() {
        // 59 letters, then a family emoji (5 code points joined): it would straddle 60, so it goes whole
        val family = "👩‍👩‍👧"
        val cut = Labels.limit("a".repeat(59) + family)
        assertEquals("a".repeat(59), cut)
        // an accented letter written as e + combining acute at the boundary
        val accent = Labels.limit("a".repeat(59) + "éx")
        assertEquals("a".repeat(59), accent)
    }

    @Test fun settingsStoreTheLimitedLabel() {
        val s = Settings(MemoryKeyValue())
        s.label = "  " + "😀".repeat(70) + "  "
        assertEquals(60, cps(s.label))
        s.label = ""
        assertEquals("", s.label)
    }
}
