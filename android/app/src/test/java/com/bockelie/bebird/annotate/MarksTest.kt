// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.annotate

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MarksTest {
    private val box = Mark.Box(Pt(0.1f, 0.1f), Pt(0.5f, 0.5f), Palette.RED)
    private val arrow = Mark.Arrow(Pt(0.2f, 0.8f), Pt(0.6f, 0.4f), Palette.YELLOW)

    // --- sketch: add, undo, clear ---

    @Test fun addingAndUndoingGoBackOneMarkAtATime() {
        val s = Sketch().add(box).add(arrow)
        assertEquals(listOf(box, arrow), s.marks)
        assertEquals(listOf(box), s.undo().marks)
        assertEquals(emptyList<Mark>(), s.undo().undo().marks)
        assertFalse(s.undo().undo().canUndo)
    }

    @Test fun clearIsUndoable() {
        val s = Sketch().add(box).add(arrow).clear()
        assertTrue(s.marks.isEmpty())
        assertTrue(s.canUndo)
        assertEquals(listOf(box, arrow), s.undo().marks)
    }

    @Test fun nothingToUndoOrClearChangesNothing() {
        val empty = Sketch()
        assertFalse(empty.canUndo)
        assertSame(empty, empty.undo())
        assertSame(empty, empty.clear())
        // clearing an empty sketch adds no step to undo
        val s = Sketch().add(box).undo()
        assertFalse(s.clear().canUndo)
    }

    @Test fun aSketchNeverChangesInPlace() {
        val one = Sketch().add(box)
        one.add(arrow); one.clear(); one.undo()
        assertEquals(listOf(box), one.marks)
    }

    // --- drags ---

    @Test fun shapesKeepTheStartAndTheLatestPoint() {
        var pts = emptyList<Pt>()
        for (p in listOf(Pt(0.1f, 0.1f), Pt(0.2f, 0.3f), Pt(0.4f, 0.5f))) pts = Drag.extend(pts, p, Tool.BOX)
        assertEquals(listOf(Pt(0.1f, 0.1f), Pt(0.4f, 0.5f)), pts)
        assertEquals(Mark.Box(Pt(0.1f, 0.1f), Pt(0.4f, 0.5f), Palette.GREEN), Drag.mark(Tool.BOX, pts, Palette.GREEN))
        assertEquals(Mark.Ellipse(Pt(0.1f, 0.1f), Pt(0.4f, 0.5f), Palette.GREEN), Drag.mark(Tool.ELLIPSE, pts, Palette.GREEN))
        assertEquals(Mark.Arrow(Pt(0.1f, 0.1f), Pt(0.4f, 0.5f), Palette.GREEN), Drag.mark(Tool.ARROW, pts, Palette.GREEN))
    }

    @Test fun thePenKeepsItsPathButSkipsTinySteps() {
        var pts = emptyList<Pt>()
        for (p in listOf(Pt(0.1f, 0.1f), Pt(0.1001f, 0.1f), Pt(0.2f, 0.1f), Pt(0.2f, 0.3f))) pts = Drag.extend(pts, p, Tool.PEN)
        assertEquals(listOf(Pt(0.1f, 0.1f), Pt(0.2f, 0.1f), Pt(0.2f, 0.3f)), pts)
        assertEquals(Mark.Pen(pts, Palette.WHITE), Drag.mark(Tool.PEN, pts, Palette.WHITE))
    }

    @Test fun aSlipOrATextDragMakesNoMark() {
        assertNull(Drag.mark(Tool.BOX, listOf(Pt(0.5f, 0.5f), Pt(0.505f, 0.503f)), Palette.RED))
        assertNull(Drag.mark(Tool.PEN, emptyList(), Palette.RED))
        assertNull(Drag.mark(Tool.TEXT, listOf(Pt(0.1f, 0.1f), Pt(0.9f, 0.9f)), Palette.RED))
        // a pen stroke that comes back to its start still counts
        assertTrue(Drag.mark(Tool.PEN, listOf(Pt(0.5f, 0.5f), Pt(0.6f, 0.5f), Pt(0.5f, 0.5f)), Palette.RED) is Mark.Pen)
    }

    // --- screen <-> image ---

    private fun near(want: Pt, got: Pt) {
        assertEquals(want.x, got.x, 1e-5f)
        assertEquals(want.y, got.y, 1e-5f)
    }

    @Test fun aSquareFrameInATallViewIsLetterboxedTopAndBottom() {
        val fit = ImageFit(1080f, 1500f, 480, 480)
        assertEquals(1080f, fit.width, 1e-3f); assertEquals(1080f, fit.height, 1e-3f)
        assertEquals(0f, fit.left, 1e-3f); assertEquals(210f, fit.top, 1e-3f)
        near(Pt(0f, 0f), fit.toImage(0f, 210f))
        near(Pt(0.5f, 0.5f), fit.toImage(540f, 750f))
        near(Pt(1f, 1f), fit.toImage(1080f, 1290f))
    }

    @Test fun aSquareFrameInAWideViewIsLetterboxedLeftAndRight() {
        val fit = ImageFit(1600f, 800f, 480, 480)
        assertEquals(800f, fit.width, 1e-3f); assertEquals(400f, fit.left, 1e-3f); assertEquals(0f, fit.top, 1e-3f)
        near(Pt(0.25f, 0.75f), fit.toImage(600f, 600f))
    }

    @Test fun pointsRoundTripAndTouchesOutsideHoldToTheEdge() {
        val fit = ImageFit(1080f, 1500f, 480, 480)
        for (p in listOf(Pt(0f, 0f), Pt(0.3f, 0.7f), Pt(1f, 0.25f))) {
            val back = fit.toImage(fit.toViewX(p), fit.toViewY(p))
            near(p, back)
        }
        // in the letterbox bands, above and below the frame
        near(Pt(0.5f, 0f), fit.toImage(540f, 10f))
        near(Pt(1f, 1f), fit.toImage(2000f, 1499f))
    }

    @Test fun aNonSquareImageKeepsItsShape() {
        val fit = ImageFit(1000f, 1000f, 400, 200)
        assertEquals(1000f, fit.width, 1e-3f); assertEquals(500f, fit.height, 1e-3f); assertEquals(250f, fit.top, 1e-3f)
        near(Pt(0.5f, 0.5f), fit.toImage(500f, 500f))
    }
}
