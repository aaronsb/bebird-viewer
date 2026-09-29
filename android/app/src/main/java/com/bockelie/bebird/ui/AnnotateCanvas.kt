// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.bockelie.bebird.R
import com.bockelie.bebird.annotate.AnnotationRenderer
import com.bockelie.bebird.annotate.Drag
import com.bockelie.bebird.annotate.ImageFit
import com.bockelie.bebird.annotate.Mark
import com.bockelie.bebird.annotate.Palette
import com.bockelie.bebird.annotate.Pt
import com.bockelie.bebird.annotate.Tool
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.toBitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

/** The annotate tool and colour in use, and where a text label is being placed (its dialog open). */
@Stable
class AnnotateTools {
    var tool by mutableStateOf(Tool.ARROW)
    var color by mutableIntStateOf(Palette.RED)
    var textAt by mutableStateOf<Pt?>(null)
}

/**
 * The paused, upright [image] fitted whole in the viewport (no zoom), with [marks] over it.
 * Dragging draws with the current tool; with Text, a tap chooses where the label goes. Marks
 * are kept in image coordinates ([ImageFit]); the saved marks are drawn by the same
 * [AnnotationRenderer], here at the on-screen size.
 */
@Composable
fun AnnotateCanvas(
    image: Bitmap, marks: List<Mark>, renderer: AnnotationRenderer, tools: AnnotateTools, outline: Boolean,
    onMark: (Mark) -> Unit, modifier: Modifier,
) {
    val description = stringResource(R.string.annotate_canvas_description)
    val addMark by rememberUpdatedState(onMark)
    BoxWithConstraints(modifier.clipToBounds().background(Color(BandRenderer.BACKGROUND))) {
        val vw = constraints.maxWidth.toFloat()
        val vh = constraints.maxHeight.toFloat()
        val fit = remember(vw, vh, image.width, image.height) { ImageFit(vw, vh, image.width, image.height) }
        val w = fit.width.roundToInt()
        val h = fit.height.roundToInt()
        var drag by remember { mutableStateOf(emptyList<Pt>()) }  // the drag in progress
        val layer = rememberLayer(marks, w, h, renderer)
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
            Modifier.fillMaxSize().semantics { contentDescription = description }.pointerInput(tools.tool, tools.color, fit) {
                val tool = tools.tool
                val color = tools.color
                if (tool == Tool.TEXT) {
                    detectTapGestures { tools.textAt = fit.toImage(it.x, it.y) }
                } else {
                    detectDragGestures(
                        onDragStart = { drag = Drag.extend(emptyList(), fit.toImage(it.x, it.y), tool) },
                        onDragEnd = {
                            Drag.mark(tool, drag, color)?.let(addMark)
                            drag = emptyList()
                        },
                        onDragCancel = { drag = emptyList() },
                    ) { change, _ ->
                        change.consume()
                        drag = Drag.extend(drag, fit.toImage(change.position.x, change.position.y), tool)
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
            for (m in pending) strokes(m, fit, w, h)
            Drag.mark(tools.tool, drag, tools.color)?.let { strokes(it, fit, w, h) }
        }
    }
}

/** [m] as the renderer draws it (the same pieces and widths), smoothed; text waits for the layer. */
private fun DrawScope.strokes(m: Mark, fit: ImageFit, w: Int, h: Int) {
    val segs = AnnotationRenderer.segments(m, w, h)
    val half = AnnotationRenderer.half(w).toFloat()
    val edge = AnnotationRenderer.edge(w).toFloat()
    for ((color, width) in listOf(Color.Black to 2 * (half + edge), Color(m.color) to 2 * half)) {
        for (s in segs) {
            drawLine(
                color,
                Offset(fit.left + s.x0.toFloat(), fit.top + s.y0.toFloat()),
                Offset(fit.left + s.x1.toFloat(), fit.top + s.y1.toFloat()),
                strokeWidth = width,
                cap = StrokeCap.Round,
            )
        }
    }
}

/** The marks drawn at the on-screen size, and which marks they are. */
private class Layer(val marks: List<Mark>, val image: ImageBitmap)

/** [marks] rasterised off the main thread at [w] × [h]; the previous layer stays up until the new one is ready. */
@Composable
private fun rememberLayer(marks: List<Mark>, w: Int, h: Int, renderer: AnnotationRenderer): Layer? {
    var layer by remember(w, h) { mutableStateOf<Layer?>(null) }
    LaunchedEffect(marks, w, h) {
        if (w <= 0 || h <= 0) return@LaunchedEffect
        try {
            val image = withContext(Dispatchers.Default) { renderer.render(marks, w, h).toBitmap().asImageBitmap() }
            layer = Layer(marks, image)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("BebirdSpike", "drawing the annotations failed", e)
        }
    }
    return layer
}
