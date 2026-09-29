// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.annotate

import kotlin.math.abs
import kotlin.math.hypot

/**
 * A point on the annotated frame, in image coordinates: 0 at the left (top) edge, 1 at the
 * right (bottom). Marks are stored this way, so the screen and the saved file draw them in the
 * same place whatever the letterboxing or the output size.
 */
data class Pt(val x: Float, val y: Float)

/** One annotation. Ellipse and box are given by two opposite corners of their bounding box. */
sealed interface Mark {
    val color: Int

    data class Ellipse(val a: Pt, val b: Pt, override val color: Int) : Mark
    data class Box(val a: Pt, val b: Pt, override val color: Int) : Mark
    /** A line from [from] with the head at [to]. */
    data class Arrow(val from: Pt, val to: Pt, override val color: Int) : Mark
    data class Pen(val points: List<Pt>, override val color: Int) : Mark
    /** [text] starting at [at], vertically centred on it. */
    data class Text(val at: Pt, val text: String, override val color: Int) : Mark
}

/** The drawing tools, and [MOVE]: drag a mark to move it, hold on it to delete it. */
enum class Tool { ELLIPSE, BOX, ARROW, PEN, TEXT, MOVE }

/** The fixed colours to draw with (opaque ARGB), the first being the default. */
object Palette {
    const val RED = 0xFFFF3B30.toInt()
    const val YELLOW = 0xFFFFD60A.toInt()
    const val GREEN = 0xFF34C759.toInt()
    const val CYAN = 0xFF32ADE6.toInt()
    const val WHITE = 0xFFFFFFFF.toInt()
    val ALL = listOf(RED, YELLOW, GREEN, CYAN, WHITE)
}

/**
 * The marks on a paused frame, with every earlier state for Undo; Clear, a move and a delete
 * are one more state each, so Undo brings the marks back as they were. Immutable: each change
 * returns a new sketch.
 */
class Sketch private constructor(val marks: List<Mark>, private val past: List<List<Mark>>) {
    constructor() : this(emptyList(), emptyList())

    val canUndo get() = past.isNotEmpty()

    fun add(mark: Mark) = Sketch(marks + mark, past + listOf(marks))

    fun clear() = if (marks.isEmpty()) this else Sketch(emptyList(), past + listOf(marks))

    /** The mark at [index] replaced by [mark] (moved), keeping its place in the stacking order. */
    fun replace(index: Int, mark: Mark) =
        if (index !in marks.indices || marks[index] == mark) this
        else Sketch(marks.toMutableList().also { it[index] = mark }, past + listOf(marks))

    fun remove(index: Int) =
        if (index !in marks.indices) this else Sketch(marks.filterIndexed { i, _ -> i != index }, past + listOf(marks))

    fun undo() = if (past.isEmpty()) this else Sketch(past.last(), past.dropLast(1))
}

/** Building a mark from a drag: the points so far, and the mark they make. Pure. */
object Drag {
    /** Pen points closer than this (in image units) to the last one are skipped. */
    const val PEN_STEP = 0.003f

    /** Smaller drags than this (in image units, either way) make no mark: a slip, not a shape. */
    const val MIN_SIZE = 0.01f

    /**
     * The drag's points after moving to [p]: the pen keeps its path, the other tools only the
     * start and the latest point.
     */
    fun extend(points: List<Pt>, p: Pt, tool: Tool): List<Pt> = when {
        points.isEmpty() -> listOf(p)
        tool == Tool.PEN -> if (hypot(p.x - points.last().x, p.y - points.last().y) < PEN_STEP) points else points + p
        else -> listOf(points.first(), p)
    }

    /** The mark a drag through [points] with [tool] makes, or null (text is placed by a tap; too small a drag). */
    fun mark(tool: Tool, points: List<Pt>, color: Int): Mark? {
        if (points.isEmpty()) return null
        val a = points.first()
        val b = points.last()
        val big = points.any { maxOf(abs(it.x - a.x), abs(it.y - a.y)) >= MIN_SIZE }
        if (!big) return null
        return when (tool) {
            Tool.ELLIPSE -> Mark.Ellipse(a, b, color)
            Tool.BOX -> Mark.Box(a, b, color)
            Tool.ARROW -> Mark.Arrow(a, b, color)
            Tool.PEN -> Mark.Pen(points, color)
            Tool.TEXT, Tool.MOVE -> null
        }
    }
}
