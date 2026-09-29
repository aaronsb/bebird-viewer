// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import com.bockelie.bebird.annotate.Palette
import com.bockelie.bebird.annotate.Pt
import com.bockelie.bebird.annotate.Tool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** What rotation keeps: the tool, the colour and an open text dialog's point. */
class AnnotateToolsTest {
    @Test fun toolColourAndTextPointSurviveASaveAndRestore() {
        val t = AnnotateTools().apply { tool = Tool.TEXT; color = Palette.CYAN; textAt = Pt(0.25f, 0.75f) }
        val back = AnnotateTools.restored(t.saved())
        assertEquals(Tool.TEXT, back.tool)
        assertEquals(Palette.CYAN, back.color)
        assertEquals(Pt(0.25f, 0.75f), back.textAt)
    }

    @Test fun noDialogOpenRestoresNone() {
        val back = AnnotateTools.restored(AnnotateTools().apply { tool = Tool.PEN }.saved())
        assertEquals(Tool.PEN, back.tool)
        assertNull(back.textAt)
    }

    @Test fun moveTurnsOffBackToTheLastDrawingTool() {
        val t = AnnotateTools()
        t.draw(Tool.PEN)
        t.toggleMove()
        assertEquals(Tool.MOVE, t.tool)
        t.toggleMove()
        assertEquals(Tool.PEN, t.tool)
        // choosing a drawing tool leaves Move
        t.toggleMove(); t.draw(Tool.BOX)
        assertEquals(Tool.BOX, t.tool)
    }

    @Test fun moveAndTheToolToGoBackToSurviveRotation() {
        val t = AnnotateTools().apply { draw(Tool.ELLIPSE); toggleMove() }
        val back = AnnotateTools.restored(t.saved())
        assertEquals(Tool.MOVE, back.tool)
        back.toggleMove()
        assertEquals(Tool.ELLIPSE, back.tool)
    }

    @Test fun unknownValuesFallBackToTheDefaults() {
        val back = AnnotateTools.restored(listOf("LASER", 0x12345678, null, 0.5f))
        assertEquals(Tool.ARROW, back.tool)
        assertEquals(Palette.RED, back.color)
        assertNull(back.textAt)
    }
}
