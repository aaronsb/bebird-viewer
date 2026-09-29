// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

import com.bockelie.bebird.focus.OverlayShape.Arc
import com.bockelie.bebird.focus.OverlayShape.Label
import com.bockelie.bebird.focus.OverlayShape.Line
import com.bockelie.bebird.focus.OverlayShape.Warning
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ScaleOverlayTest {
    @Test fun ringsAreAMillimetreApartAndLabelledByDiameter() {
        val s = ScaleOverlay.shapes(ScaleStyle.RING, locked = false, close = false)
        val rings = s.filterIsInstance<Arc>()
        assertEquals(listOf(40.0, 80.0, 120.0, 160.0, 200.0), rings.map { it.r })
        assertTrue(rings.all { it.cx == 240.0 && it.cy == 240.0 && it.endDeg - it.startDeg == 360.0 })
        val labels = s.filterIsInstance<Label>().map { it.text }
        assertEquals(listOf("⌀2", "⌀4", "⌀6", "⌀8", "⌀10", "mm ±10%"), labels)
        assertEquals(Label(243.0, 198.0, "⌀2", TextAnchor.LEFT_BASELINE, ScaleOverlay.GREY), s.filterIsInstance<Label>()[0])
    }

    @Test fun unlockedIsGreyThinAndDashedLockedIsSolid() {
        val grey = ScaleOverlay.shapes(ScaleStyle.RING, locked = false, close = false).filterIsInstance<Arc>()
        assertTrue(grey.all { it.color == ScaleOverlay.GREY && it.width == 1 && it.dashDeg == 6.0 })
        val lock = ScaleOverlay.shapes(ScaleStyle.RING, locked = true, close = false).filterIsInstance<Arc>()
        assertTrue(lock.all { it.color == ScaleOverlay.LOCK && it.width == 2 && it.dashDeg == 0.0 })
    }

    @Test fun bowtieHasTwoWedgesWithATickPerMillimetre() {
        val dashed = ScaleOverlay.shapes(ScaleStyle.BOWTIE, locked = false, close = false)
        assertEquals(24, dashed.filterIsInstance<Line>().size)  // 4 edges × 6 dashes
        val ticks = dashed.filterIsInstance<Arc>()
        assertEquals(10, ticks.size)
        assertTrue(ticks.all { it.endDeg - it.startDeg == 30.0 && it.dashDeg == 0.0 })
        assertEquals(setOf(-15.0, 165.0), ticks.map { it.startDeg }.toSet())
        val solid = ScaleOverlay.shapes(ScaleStyle.BOWTIE, locked = true, close = false).filterIsInstance<Line>()
        assertEquals(4, solid.size)
        val edge = solid.first()
        assertEquals(240 + 210 * Math.cos(Math.toRadians(-15.0)), edge.x1, 1e-9)
    }

    @Test fun barHasElevenOutlinedTicksLongEveryFive() {
        val s = ScaleOverlay.shapes(ScaleStyle.BAR, locked = false, close = false)
        val lines = s.filterIsInstance<Line>()
        assertEquals(12, lines.size)
        assertTrue(lines.all { it.outlined })
        assertEquals(20.0, lines[0].x0, 1e-9)
        assertEquals(460.0, lines[0].x1, 1e-9)
        val long = lines.drop(1).filter { it.y1 - it.y0 == 28.0 }.map { it.x0 }
        assertEquals(listOf(40.0, 240.0, 440.0), long)
        assertEquals((0..10).map { "$it" } + "mm ±10%", s.filterIsInstance<Label>().map { it.text })
    }

    @Test fun closeAddsTheWarningUpperLeft() {
        assertTrue(ScaleOverlay.shapes(ScaleStyle.NONE, locked = true, close = false).isEmpty())
        val s = ScaleOverlay.shapes(ScaleStyle.NONE, locked = true, close = true)
        assertEquals(listOf(Warning(18.0, 16.0, 40.0, ScaleOverlay.WARNING), Label(66.0, 34.0, "CLOSE", TextAnchor.LEFT_MIDDLE, ScaleOverlay.WARNING)), s)
    }
}
