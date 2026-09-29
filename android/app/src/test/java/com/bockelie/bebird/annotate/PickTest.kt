// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.annotate

import com.bockelie.bebird.band.Fonts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class PickTest {
    private val renderer = AnnotationRenderer(Fonts.source)
    private val picker = Picker(renderer, 480, 480)
    private val tol = 10.0  // frame pixels
    private val red = Palette.RED

    /** A point [px] frame pixels across and down. */
    private fun at(x: Double, y: Double) = Pt((x / 480).toFloat(), (y / 480).toFloat())

    private fun pick(m: Mark, x: Double, y: Double) = picker.pick(listOf(m), at(x, y), tol)

    private fun near(want: Float, got: Float) = assertEquals(want, got, 1e-5f)

    // --- hit-testing ---

    @Test fun aBoxIsPickedByItsOutlineNotItsInside() {
        val box = Mark.Box(at(120.0, 120.0), at(360.0, 360.0), red)
        assertEquals(0, pick(box, 120.0, 240.0))   // on the left side
        assertEquals(0, pick(box, 128.0, 240.0))   // within the tolerance plus half a stroke
        assertNull(pick(box, 140.0, 240.0))        // inside, away from the outline
        assertNull(pick(box, 240.0, 240.0))
        assertNull(pick(box, 100.0, 240.0))        // outside, too far
    }

    @Test fun anEllipseIsPickedByItsOutline() {
        val e = Mark.Ellipse(at(120.0, 120.0), at(360.0, 360.0), red)
        assertEquals(0, pick(e, 360.0, 240.0))
        assertEquals(0, pick(e, 240.0, 125.0))
        assertNull(pick(e, 240.0, 240.0))
        assertNull(pick(e, 125.0, 125.0))  // the bounding box's corner, far from the curve
    }

    @Test fun anArrowIsPickedByItsShaftOrHead() {
        val a = Mark.Arrow(at(100.0, 240.0), at(300.0, 240.0), red)
        assertEquals(0, pick(a, 200.0, 245.0))
        // a barb: back from the tip, above the shaft
        assertEquals(0, pick(a, 285.0, 232.0))
        assertNull(pick(a, 200.0, 270.0))
        assertNull(pick(a, 330.0, 240.0))
    }

    @Test fun aPenStrokeIsPickedAlongItsPath() {
        val p = Mark.Pen(listOf(at(100.0, 100.0), at(200.0, 100.0), at(200.0, 300.0)), red)
        assertEquals(0, pick(p, 150.0, 104.0))
        assertEquals(0, pick(p, 196.0, 250.0))
        assertNull(pick(p, 150.0, 200.0))  // inside the corner, not on the stroke
    }

    @Test fun textIsPickedAnywhereInItsBox() {
        val t = Mark.Text(Pt(0.25f, 0.5f), "AB", red)
        val r = renderer.textBounds(t, 480, 480)
        // two cells at scale 2 from x = 120, centred on y = 240, with the outline
        assertEquals(AnnotationRenderer.PixelRect(118, 222, 154, 258), r)
        assertEquals(0, pick(t, 136.0, 240.0))
        assertEquals(0, pick(t, 160.0, 240.0))  // just right of the box, within the tolerance
        assertNull(pick(t, 180.0, 240.0))
        assertNull(pick(t, 136.0, 280.0))
    }

    @Test fun overlapsGoToTheTopmostMarkAndEmptySpaceToNone() {
        val under = Mark.Box(at(120.0, 120.0), at(360.0, 360.0), red)
        val over = Mark.Arrow(at(60.0, 240.0), at(200.0, 240.0), Palette.CYAN)
        val marks = listOf(under, over)
        assertEquals(1, picker.pick(marks, at(120.0, 240.0), tol))  // where both are
        assertEquals(0, picker.pick(marks, at(360.0, 240.0), tol))  // only the box
        assertNull(picker.pick(marks, at(420.0, 60.0), tol))
        assertNull(picker.pick(emptyList(), at(240.0, 240.0), tol))
    }

    @Test fun theTouchToleranceIs24DpInFramePixels() {
        // 2.625 px per dp, the 480-px frame shown 1080 px wide: 63 screen px = 28 frame px
        assertEquals(28.0, Picker.tolerance(2.625f, 1080f, 480), 1e-9)
    }

    // --- moving ---

    @Test fun eachKindOfMarkMovesByTheDrag() {
        val dx = 0.1f; val dy = 0.05f
        val box = picker.moved(Mark.Box(Pt(0.2f, 0.2f), Pt(0.4f, 0.4f), red), dx, dy) as Mark.Box
        near(0.3f, box.a.x); near(0.25f, box.a.y); near(0.5f, box.b.x); near(0.45f, box.b.y)
        val e = picker.moved(Mark.Ellipse(Pt(0.4f, 0.4f), Pt(0.2f, 0.2f), red), dx, dy) as Mark.Ellipse
        near(0.5f, e.a.x); near(0.25f, e.b.y)
        val a = picker.moved(Mark.Arrow(Pt(0.1f, 0.5f), Pt(0.3f, 0.5f), red), dx, dy) as Mark.Arrow
        near(0.2f, a.from.x); near(0.55f, a.to.y)
        val p = picker.moved(Mark.Pen(listOf(Pt(0.1f, 0.1f), Pt(0.2f, 0.3f)), red), dx, dy) as Mark.Pen
        near(0.2f, p.points[0].x); near(0.35f, p.points[1].y)
        val t = picker.moved(Mark.Text(Pt(0.2f, 0.5f), "AB", red), dx, dy) as Mark.Text
        near(0.3f, t.at.x); near(0.55f, t.at.y)
        assertEquals(red, t.color)
    }

    @Test fun marksStayWithinTheFrame() {
        val box = picker.moved(Mark.Box(Pt(0.6f, 0.2f), Pt(0.9f, 0.4f), red), 0.3f, -0.5f) as Mark.Box
        near(1f, box.b.x); near(0.7f, box.a.x)   // stopped at the right edge, keeping its size
        near(0f, box.a.y); near(0.2f, box.b.y)   // and at the top
        val pen = picker.moved(Mark.Pen(listOf(Pt(0.1f, 0.5f), Pt(0.3f, 0.6f)), red), -0.5f, 0.9f) as Mark.Pen
        near(0f, pen.points[0].x); near(1f, pen.points[1].y)
        val arrow = picker.moved(Mark.Arrow(Pt(0.5f, 0.5f), Pt(0.8f, 0.9f), red), 0f, 0.5f) as Mark.Arrow
        near(1f, arrow.to.y); near(0.6f, arrow.from.y)
        // text: its drawn box stays inside
        val text = picker.moved(Mark.Text(Pt(0.5f, 0.5f), "AB", red), 0.9f, 0f) as Mark.Text
        val r = renderer.textBounds(text, 480, 480)
        assertTrue("${r.right}", r.right <= 480)
    }

    @Test fun aLabelAlreadyOverTheEdgeCanComeBackButGoesNoFurther() {
        val long = Mark.Text(Pt(0.8f, 0.5f), "a long label past the edge", red)
        assertTrue(renderer.textBounds(long, 480, 480).right > 480)
        near(0.8f, (picker.moved(long, 0.05f, 0f) as Mark.Text).at.x)
        near(0.7f, (picker.moved(long, -0.1f, 0f) as Mark.Text).at.x)
    }

    @Test fun theClampOnItsOwn() {
        near(0.1f, Picker.clamp(0.3f, 0.2f, 0.9f))     // right edge
        near(-0.2f, Picker.clamp(-0.5f, 0.2f, 0.9f))   // left edge
        near(0.05f, Picker.clamp(0.05f, 0.2f, 0.9f))   // free
        near(0f, Picker.clamp(0.1f, 0.5f, 1.3f))       // over the edge: no further
        near(-0.1f, Picker.clamp(-0.1f, 0.5f, 1.3f))   // but back is fine
    }

    // --- history ---

    @Test fun aMoveIsOneStepAndUndoPutsItBack() {
        val a = Mark.Box(Pt(0.1f, 0.1f), Pt(0.2f, 0.2f), red)
        val b = Mark.Arrow(Pt(0.5f, 0.5f), Pt(0.7f, 0.7f), red)
        val s = Sketch().add(a).add(b)
        val moved = picker.moved(a, 0.3f, 0.3f)
        val after = s.replace(0, moved)
        assertEquals(listOf(moved, b), after.marks)  // same place in the stacking order
        assertEquals(listOf(a, b), after.undo().marks)
        assertEquals(listOf(a), after.undo().undo().marks)
    }

    @Test fun aDeleteIsOneStepAndUndoBringsTheMarkBack() {
        val a = Mark.Box(Pt(0.1f, 0.1f), Pt(0.2f, 0.2f), red)
        val b = Mark.Text(Pt(0.5f, 0.5f), "x", red)
        val c = Mark.Pen(listOf(Pt(0.3f, 0.3f), Pt(0.4f, 0.4f)), red)
        val after = Sketch().add(a).add(b).add(c).remove(1)
        assertEquals(listOf(a, c), after.marks)
        assertEquals(listOf(a, b, c), after.undo().marks)
    }

    @Test fun noOpMovesAndDeletesAddNoStep() {
        val a = Mark.Box(Pt(0.1f, 0.1f), Pt(0.2f, 0.2f), red)
        val s = Sketch().add(a)
        assertSame(s, s.replace(0, a))
        assertSame(s, s.replace(3, a))
        assertSame(s, s.remove(-1))
        assertFalse(s.replace(0, a).undo().canUndo)
    }

    @Test fun theMoveToolMakesNoMarks() {
        assertNull(Drag.mark(Tool.MOVE, listOf(Pt(0.1f, 0.1f), Pt(0.9f, 0.9f)), red))
    }
}
