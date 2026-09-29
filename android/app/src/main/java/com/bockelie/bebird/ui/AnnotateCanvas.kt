// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.AwaitPointerEventScope
import androidx.compose.ui.input.pointer.PointerId
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.bockelie.bebird.R
import com.bockelie.bebird.annotate.AnnotationRenderer
import com.bockelie.bebird.annotate.Drag
import com.bockelie.bebird.annotate.ImageFit
import com.bockelie.bebird.annotate.Mark
import com.bockelie.bebird.annotate.Palette
import com.bockelie.bebird.annotate.Picker
import com.bockelie.bebird.annotate.PressOutcome
import com.bockelie.bebird.annotate.Pt
import com.bockelie.bebird.annotate.Tool
import com.bockelie.bebird.annotate.classifyMovePress
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.toBitmap
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The annotate tool and colour in use, and where a text label is being placed (its dialog
 * open). Move is a mode over the drawing tools: turning it off goes back to [drawTool], the
 * last drawing tool. Kept across rotation with [Saver].
 */
@Stable
class AnnotateTools {
    var tool by mutableStateOf(Tool.ARROW)
        private set
    var color by mutableIntStateOf(Palette.RED)
    var textAt by mutableStateOf<Pt?>(null)
    var drawTool by mutableStateOf(Tool.ARROW)
        private set

    /** Choose a drawing tool (which also leaves Move). */
    fun draw(t: Tool) {
        tool = t
        if (t != Tool.MOVE) drawTool = t
    }

    /** Move on, or off again back to the last drawing tool. */
    fun toggleMove() {
        tool = if (tool == Tool.MOVE) drawTool else Tool.MOVE
    }

    /** Tool, colour, text point and last drawing tool as saveable values. */
    fun saved(): List<Any?> = listOf(tool.name, color, textAt?.x, textAt?.y, drawTool.name)

    companion object {
        fun restored(v: List<Any?>) = AnnotateTools().apply {
            tool = Tool.entries.firstOrNull { it.name == v.getOrNull(0) } ?: Tool.ARROW
            color = (v.getOrNull(1) as? Int)?.takeIf { it in Palette.ALL } ?: Palette.RED
            val x = v.getOrNull(2) as? Float
            val y = v.getOrNull(3) as? Float
            textAt = if (x != null && y != null) Pt(x, y) else null
            drawTool = Tool.entries.firstOrNull { it.name == v.getOrNull(4) && it != Tool.MOVE }
                ?: tool.takeIf { it != Tool.MOVE } ?: Tool.ARROW
        }

        val Saver = listSaver<AnnotateTools, Any?>(save = { it.saved() }, restore = ::restored)
    }
}

/**
 * The paused, upright [image] fitted whole in the viewport (no zoom), with [marks] over it.
 * Dragging draws with the current tool, from where the finger went down, following that
 * finger only; with Text, a tap chooses where the label goes; with Move, a drag on a mark
 * moves it and holding still on it deletes it. No input while not [enabled]
 * (a save is running). Marks are kept in image coordinates ([ImageFit]). The marks
 * layer is drawn by the same [AnnotationRenderer] at the frame's own size, then scaled like
 * the frame, so the screen shows the saved file's pixels.
 */
@Composable
fun AnnotateCanvas(
    image: Bitmap, marks: List<Mark>, renderer: AnnotationRenderer, tools: AnnotateTools, outline: Boolean,
    enabled: Boolean, onMark: (Mark) -> Unit, onMove: (Int, Mark) -> Unit, onDelete: (Int) -> Unit, modifier: Modifier,
) {
    val description = stringResource(R.string.annotate_canvas_description)
    val addMark by rememberUpdatedState(onMark)
    val moveMark by rememberUpdatedState(onMove)
    val deleteMark by rememberUpdatedState(onDelete)
    val current by rememberUpdatedState(marks)
    val haptic = LocalHapticFeedback.current
    val picker = remember(renderer, image.width, image.height) { Picker(renderer, image.width, image.height) }
    BoxWithConstraints(modifier.clipToBounds().background(Color(BandRenderer.BACKGROUND))) {
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        val fit = remember(vw, vh, image.width, image.height) { ImageFit(vw, vh, image.width, image.height) }
        val w = fit.width.roundToInt()
        val h = fit.height.roundToInt()
        var stroke by remember { mutableStateOf(emptyList<Pt>()) }  // the drag in progress
        var move by remember { mutableStateOf<MoveState?>(null) }  // a Move press, while it lasts
        // A dropped mark, shown where it landed until a layer drawn from the current marks has it
        // (apart from [move], so pressing again meanwhile doesn't put it back at its old spot).
        var landing by remember { mutableStateOf<Landing?>(null) }
        // While Move holds a mark, the layer leaves it out and draws it alone, so a drag slides
        // that mark's own pixels instead of drawing everything again for each movement.
        val held = move?.takeIf { it.phase != Phase.DELETED }?.mark
        val layer = rememberLayer(marks, held, image.width, image.height, renderer)
        LaunchedEffect(layer, landing) {
            val l = landing ?: return@LaunchedEffect
            // only a layer of the marks after the drop (never the list the mark was dropped from)
            if (layer?.marks === marks && marks !== l.before) landing = null
        }
        with(LocalDensity.current) {
            Box(Modifier.offset { IntOffset(fit.left.roundToInt(), fit.top.roundToInt()) }.size(w.toDp(), h.toDp())) {
                // As the live view shows it: the image circle, and the hair-thin ring with the overlay.
                Image(
                    bitmap = image.asImageBitmap(),
                    contentDescription = null,
                    contentScale = ContentScale.FillBounds,
                    modifier = Modifier.fillMaxSize().clip(CircleShape).background(Color.Black)
                        .then(if (outline) Modifier.border(Dp.Hairline, Color(BandRenderer.CIRCLE), CircleShape) else Modifier),
                )
                layer?.let { l ->
                    Image(
                        bitmap = l.base,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxSize(),
                    )
                    // The layer's lone mark: where it is being dragged or has landed; where it is, if
                    // still one of the marks (a tap on it); else nowhere (deleted, or undone meanwhile).
                    val lm = landing?.state
                    val mv = move
                    val at: Pair<Int, Int>? = when {
                        l.lone == null -> null
                        // held: follows the drag while still one of the marks (not undone meanwhile)
                        mv != null && mv.mark === l.picked ->
                            if (mv.phase == Phase.DELETED || marks.none { it === l.picked }) null else mv.dx to mv.dy
                        lm != null && lm.mark === l.picked -> lm.dx to lm.dy
                        marks.any { it === l.picked } -> 0 to 0
                        else -> null
                    }
                    if (l.lone != null && at != null) {
                        val k = fit.width / image.width  // screen px per frame px
                        Image(
                            bitmap = l.lone,
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            filterQuality = FilterQuality.None,
                            modifier = Modifier.fillMaxSize().graphicsLayer {
                                translationX = at.first * k
                                translationY = at.second * k
                            },
                        )
                    }
                }
            }
        }
        Canvas(
            Modifier.fillMaxSize().semantics { contentDescription = description }.pointerInput(tools.tool, tools.color, fit, enabled, picker) {
                if (!enabled) return@pointerInput
                val tool = tools.tool
                val color = tools.color
                if (tool == Tool.MOVE) {
                    awaitEachGesture {
                        val down = awaitFirstDown()
                        val start = fit.toImage(down.position.x, down.position.y)
                        val tolerance = Picker.tolerance(density, fit.width, image.width)
                        val mark = picker.pick(current, start, tolerance)?.let { current[it] } ?: return@awaitEachGesture  // empty space
                        // Found again by identity when acting: Undo with another finger may have moved it in the list.
                        fun index() = current.indexOfFirst { it === mark }
                        var state = MoveState(mark, picker.bounds(mark), Phase.PRESSED)
                        try {
                            move = state
                            val (outcome, at) = awaitMovePressOutcome(down, viewConfiguration.touchSlop, viewConfiguration.longPressTimeoutMillis)
                            when (outcome) {
                                PressOutcome.DELETE -> {
                                    val i = index()
                                    if (i >= 0) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        deleteMark(i)
                                        move = state.copy(phase = Phase.DELETED)  // red until the finger lifts
                                    }
                                    awaitFingerUp(down.id)
                                }
                                PressOutcome.MOVE -> {
                                    fun follow(p: Offset) {
                                        val to = fit.toImage(p.x, p.y)
                                        val (px, py) = picker.offsetPx(mark, to.x - start.x, to.y - start.y, state.bounds)
                                        state = state.copy(phase = Phase.DRAGGING, dx = px, dy = py)
                                        move = state
                                    }
                                    follow(at)
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            // Compose delivers a cancelled touch (a system gesture) as this same
                                            // release, so the two can't be told apart: either way the mark lands
                                            // where it is shown. One history entry for the whole drag.
                                            val i = index()
                                            if (i >= 0 && (state.dx != 0 || state.dy != 0)) {
                                                val before = current
                                                moveMark(i, picker.shifted(mark, state.dx, state.dy))
                                                landing = Landing(state, before)
                                            }
                                            break
                                        }
                                        if (change.isConsumed) break  // taken by something else: the mark stays
                                        change.consume()
                                        follow(change.position)
                                    }
                                }
                                PressOutcome.NONE -> Unit
                            }
                        } finally {
                            move = null
                        }
                    }
                } else if (tool == Tool.TEXT) {
                    detectTapGestures { tools.textAt = fit.toImage(it.x, it.y) }
                } else {
                    awaitEachGesture {
                        // From the down point itself, not where the touch slop was crossed.
                        val down = awaitFirstDown()
                        try {
                            var pts = Drag.extend(emptyList(), fit.toImage(down.position.x, down.position.y), tool)
                            stroke = pts
                            // Only the finger that went down draws: a second finger neither moves
                            // the stroke nor carries it on when the first lifts.
                            while (true) {
                                val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                if (!change.pressed) {
                                    Drag.mark(tool, pts, color)?.let(addMark)
                                    break
                                }
                                if (change.isConsumed) break  // taken by something else: no mark
                                change.consume()
                                pts = Drag.extend(pts, fit.toImage(change.position.x, change.position.y), tool)
                                stroke = pts
                            }
                        } finally {
                            stroke = emptyList()  // also when the input is restarted mid-drag
                        }
                    }
                }
            },
        ) {
            // Marks the layer doesn't show yet (it is drawn off the main thread), then the drag.
            val pending = when {
                layer == null -> marks
                marks.size >= layer.marks.size && layer.marks.indices.all { marks[it] === layer.marks[it] } ->
                    marks.subList(layer.marks.size, marks.size)
                else -> emptyList()  // after Undo or Clear, until the layer catches up
            }
            for (m in pending) strokes(m, fit, image.width, image.height)
            Drag.mark(tools.tool, stroke, tools.color)?.let { strokes(it, fit, image.width, image.height) }
            // The mark a Move press holds: what will move, or (red) what was just deleted.
            move?.takeIf { m -> m.phase == Phase.DELETED || marks.any { it === m.mark } }?.let { m ->
                val b = m.bounds
                val dx = m.dx.toFloat() / image.width
                val dy = m.dy.toFloat() / image.height
                highlight(Picker.Bounds(b.left + dx, b.top + dy, b.right + dx, b.bottom + dy), fit, deleted = m.phase == Phase.DELETED)
            }
        }
    }
}

/**
 * [m] as the renderer draws it on the [iw] × [ih] frame (the same pieces and widths), scaled
 * to the view and smoothed; text waits for the layer.
 */
private fun DrawScope.strokes(m: Mark, fit: ImageFit, iw: Int, ih: Int) {
    val k = fit.width / iw
    val segs = AnnotationRenderer.segments(m, iw, ih)
    val half = AnnotationRenderer.half(iw).toFloat() * k
    val edge = AnnotationRenderer.edge(iw).toFloat() * k
    for ((color, width) in listOf(Color.Black to 2 * (half + edge), Color(m.color) to 2 * half)) {
        for (s in segs) {
            drawLine(
                color,
                Offset(fit.left + s.x0.toFloat() * k, fit.top + s.y0.toFloat() * k),
                Offset(fit.left + s.x1.toFloat() * k, fit.top + s.y1.toFloat() * k),
                strokeWidth = width,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** Where a Move press is: held, being dragged, or just deleted (a long press). */
private enum class Phase { PRESSED, DRAGGING, DELETED }

/** A dropped move ([state]), and the marks as they were before it ([before]). */
private class Landing(val state: MoveState, val before: List<Mark>)

/** A Move press on [mark] (its drawn [bounds]), moved by ([dx], [dy]) whole frame pixels so far. */
private data class MoveState(val mark: Mark, val bounds: Picker.Bounds, val phase: Phase, val dx: Int = 0, val dy: Int = 0)

/**
 * Wait until a Move press on a mark is decided ([classifyMovePress]): moved past the [slop],
 * held still for [timeoutMs], or ended. Only the first finger ([down]) counts. Returns the
 * outcome and the finger's position then; a move's event is consumed.
 */
private suspend fun AwaitPointerEventScope.awaitMovePressOutcome(
    down: PointerInputChange, slop: Float, timeoutMs: Long,
): Pair<PressOutcome, Offset> {
    var elapsed = 0L
    while (true) {
        // no event before the timeout: the finger is still down and still, a long press
        val event = withTimeoutOrNull(timeoutMs - elapsed) { awaitPointerEvent() } ?: return PressOutcome.DELETE to down.position
        val change = event.changes.firstOrNull { it.id == down.id }
        if (change != null) elapsed = change.uptimeMillis - down.uptimeMillis
        val outcome = classifyMovePress(
            present = change != null, pressed = change?.pressed == true, consumed = change?.isConsumed == true,
            distance = change?.let { (it.position - down.position).getDistance() } ?: 0f,
            slop = slop, elapsedMs = elapsed, timeoutMs = timeoutMs,
        ) ?: continue
        if (outcome == PressOutcome.MOVE) change?.consume()
        return outcome to (change?.position ?: down.position)
    }
}

/** Swallow the rest of the touch by pointer [id] until it lifts. */
private suspend fun AwaitPointerEventScope.awaitFingerUp(id: PointerId) {
    while (true) {
        val change = awaitPointerEvent().changes.firstOrNull { it.id == id } ?: return
        change.consume()
        if (!change.pressed) return
    }
}

/** A translucent box around [b] (image coordinates), a little larger, for the held mark; red once [deleted]. */
private fun DrawScope.highlight(b: Picker.Bounds, fit: ImageFit, deleted: Boolean) {
    val pad = 6.dp.toPx()
    val color = if (deleted) Color(0xFFFF3B30) else Color.White
    val topLeft = Offset(fit.toViewX(Pt(b.left, b.top)) - pad, fit.toViewY(Pt(b.left, b.top)) - pad)
    val size = Size(fit.toViewX(Pt(b.right, b.bottom)) - topLeft.x + pad, fit.toViewY(Pt(b.right, b.bottom)) - topLeft.y + pad)
    drawRect(color.copy(alpha = 0.2f), topLeft, size)
    drawRect(color, topLeft, size, style = Stroke(width = 2.dp.toPx()))
}

/**
 * The marks drawn at the frame's size: [base] is every mark but [picked] (the one Move holds,
 * if any), which is drawn alone as [lone]. [marks] says which marks these are.
 */
private class Layer(val marks: List<Mark>, val picked: Mark?, val base: ImageBitmap, val lone: ImageBitmap?)

/**
 * [marks] rasterised off the main thread at [w] × [h] (the frame's size), with [picked] apart
 * (see [Layer]); the previous layer stays up until the new one is ready. A newer change cancels
 * an older render, which stops at its next mark rather than running on alongside.
 */
@Composable
private fun rememberLayer(marks: List<Mark>, picked: Mark?, w: Int, h: Int, renderer: AnnotationRenderer): Layer? {
    var layer by remember(w, h) { mutableStateOf<Layer?>(null) }
    LaunchedEffect(marks, picked, w, h) {
        if (w <= 0 || h <= 0) return@LaunchedEffect
        try {
            val (base, lone) = withContext(Dispatchers.Default) {
                val base = renderer.render(marks.filter { it !== picked }, w, h) { ensureActive() }.toBitmap().asImageBitmap()
                base to picked?.let { renderer.render(listOf(it), w, h) { ensureActive() }.toBitmap().asImageBitmap() }
            }
            layer = Layer(marks, picked, base, lone)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("BebirdSpike", "drawing the annotations failed", e)
        }
    }
    return layer
}
