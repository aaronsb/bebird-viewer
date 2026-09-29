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
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
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
import com.bockelie.bebird.annotate.Pt
import com.bockelie.bebird.annotate.Tool
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.toBitmap
import kotlin.math.roundToInt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

/**
 * The annotate tool and colour in use, and where a text label is being placed (its dialog
 * open). Kept across rotation with [Saver].
 */
@Stable
class AnnotateTools {
    var tool by mutableStateOf(Tool.ARROW)
    var color by mutableIntStateOf(Palette.RED)
    var textAt by mutableStateOf<Pt?>(null)

    /** Tool, colour and text point as saveable values. */
    fun saved(): List<Any?> = listOf(tool.name, color, textAt?.x, textAt?.y)

    companion object {
        fun restored(v: List<Any?>) = AnnotateTools().apply {
            tool = Tool.entries.firstOrNull { it.name == v.getOrNull(0) } ?: Tool.ARROW
            color = (v.getOrNull(1) as? Int)?.takeIf { it in Palette.ALL } ?: Palette.RED
            val x = v.getOrNull(2) as? Float
            val y = v.getOrNull(3) as? Float
            textAt = if (x != null && y != null) Pt(x, y) else null
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
        // Move: the mark under the finger (highlighted), and where it is being moved to
        var picked by remember { mutableStateOf<Int?>(null) }
        var moving by remember { mutableStateOf<Mark?>(null) }
        val shown = moving?.let { m -> picked?.let { i -> marks.mapIndexed { j, o -> if (j == i) m else o } } } ?: marks
        val layer = rememberLayer(shown, image.width, image.height, renderer)
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
                layer?.let {
                    Image(
                        bitmap = it.image,
                        contentDescription = null,
                        contentScale = ContentScale.FillBounds,
                        filterQuality = FilterQuality.None,
                        modifier = Modifier.fillMaxSize(),
                    )
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
                        val index = picker.pick(current, start, tolerance) ?: return@awaitEachGesture  // empty space
                        val mark = current[index]
                        try {
                            picked = index
                            // Within the long-press timeout, moving past the touch slop starts a
                            // move; holding still until it runs out deletes. Only the first finger counts.
                            var at = down.position
                            val press = withTimeoutOrNull(viewConfiguration.longPressTimeoutMillis) {
                                var result: Press? = null
                                while (result == null) {
                                    val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id }
                                    result = when {
                                        change == null || change.isConsumed -> Press.GONE
                                        !change.pressed -> Press.LIFTED
                                        (change.position - down.position).getDistance() > viewConfiguration.touchSlop -> {
                                            change.consume()
                                            at = change.position
                                            Press.DRAG
                                        }
                                        else -> null
                                    }
                                }
                                result
                            }
                            // The marks may have changed meanwhile (Undo with another finger): then leave them be.
                            fun still() = current.getOrNull(index) === mark
                            when (press) {
                                null -> {
                                    if (still()) {
                                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                        deleteMark(index)
                                    }
                                    picked = null
                                    // the rest of this touch does nothing
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        change.consume()
                                        if (!change.pressed) break
                                    }
                                }
                                Press.DRAG -> {
                                    fun follow(p: Offset) {
                                        val to = fit.toImage(p.x, p.y)
                                        moving = picker.moved(mark, to.x - start.x, to.y - start.y)
                                    }
                                    follow(at)
                                    while (true) {
                                        val change = awaitPointerEvent().changes.firstOrNull { it.id == down.id } ?: break
                                        if (!change.pressed) {
                                            // one history entry for the whole drag
                                            moving?.let { if (still()) moveMark(index, it) }
                                            break
                                        }
                                        if (change.isConsumed) break  // taken by something else: the mark stays
                                        change.consume()
                                        follow(change.position)
                                    }
                                }
                                Press.LIFTED, Press.GONE -> Unit
                            }
                        } finally {
                            picked = null
                            moving = null
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
            // The mark a Move press has picked: what will move, or be deleted on a long press.
            picked?.let { i -> (moving ?: marks.getOrNull(i))?.let { highlight(picker.bounds(it), fit) } }
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

/** How a Move press went before the long-press timeout ran out (null from the timeout: a long press). */
private enum class Press { DRAG, LIFTED, GONE }

/** A translucent box around [b] (image coordinates), a little larger, for the picked mark. */
private fun DrawScope.highlight(b: Picker.Bounds, fit: ImageFit) {
    val pad = 6.dp.toPx()
    val topLeft = Offset(fit.toViewX(Pt(b.left, b.top)) - pad, fit.toViewY(Pt(b.left, b.top)) - pad)
    val size = Size(fit.toViewX(Pt(b.right, b.bottom)) - topLeft.x + pad, fit.toViewY(Pt(b.right, b.bottom)) - topLeft.y + pad)
    drawRect(Color.White.copy(alpha = 0.2f), topLeft, size)
    drawRect(Color.White, topLeft, size, style = Stroke(width = 2.dp.toPx()))
}

/** The marks drawn at the frame's size, and which marks they are. */
private class Layer(val marks: List<Mark>, val image: ImageBitmap)

/**
 * [marks] rasterised off the main thread at [w] × [h] (the frame's size); the previous layer
 * stays up until the new one is ready. A newer change cancels an older render, which stops
 * at its next mark rather than running on alongside.
 */
@Composable
private fun rememberLayer(marks: List<Mark>, w: Int, h: Int, renderer: AnnotationRenderer): Layer? {
    var layer by remember(w, h) { mutableStateOf<Layer?>(null) }
    LaunchedEffect(marks, w, h) {
        if (w <= 0 || h <= 0) return@LaunchedEffect
        try {
            val image = withContext(Dispatchers.Default) {
                renderer.render(marks, w, h) { ensureActive() }.toBitmap().asImageBitmap()
            }
            layer = Layer(marks, image)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("BebirdSpike", "drawing the annotations failed", e)
        }
    }
    return layer
}
