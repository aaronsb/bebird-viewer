// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.graphics.Bitmap
import android.util.Log
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import com.bockelie.bebird.R
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.PixelText
import com.bockelie.bebird.band.toBitmap
import com.bockelie.bebird.capture.ZoomCrop
import com.bockelie.bebird.focus.FrameGeometry
import com.bockelie.bebird.focus.OverlayRenderer
import com.bockelie.bebird.focus.OverlayShape
import com.bockelie.bebird.focus.ScaleDisclaimer
import com.bockelie.bebird.focus.ScaleOverlay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The image circle inside a rectangular viewport. Pinch zooms (1-6x) and drag pans, clipped
 * to the viewport; double-tap resets. Display only: nothing here changes what is received.
 * While there is no [frame], the circle says [status] (#32); while the scale is shown, a note
 * says it is approximate (#35). Both are drawn with [text] once the band's fonts are loaded.
 */
@Composable
fun ZoomableCircle(
    frame: Bitmap?, rotation: Int, outline: Boolean, view: ZoomView, modifier: Modifier,
    proximity: List<OverlayShape> = emptyList(), overlayRenderer: OverlayRenderer? = null,
    status: CircleStatus? = null, text: PixelText? = null,
) {
    // Outside the image circle the viewport is the band's black, not the theme's surface, so
    // image and band read as one panel (as in saved stills with the overlay).
    BoxWithConstraints(modifier.clipToBounds().background(Color(BandRenderer.BACKGROUND)), contentAlignment = Alignment.Center) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val side = minOf(w, h)
        SideEffect { view.viewportW = w; view.viewportH = h }  // for the zoomed snapshot's crop
        fun clamp(o: Offset, z: Float) = clampOffset(o, z, w, h, side)
        // A resize (rotation, multi-window) keeps the view inside the new bounds.
        LaunchedEffect(w, h) { view.offset = clamp(view.offset, view.zoom) }
        // For TalkBack: what the picture is, and the zoom that pinch and double-tap give (#40).
        // Derived, so a pinch recomposes only when what TalkBack would say changes.
        val zoomText by remember(view) { derivedStateOf { zoomLabel(view.zoom) } }
        val actions by remember(view) { derivedStateOf { zoomActions(view.zoom, view.offset) } }
        val description = zoomText?.let { stringResource(R.string.live_picture_zoom, it) } ?: stringResource(R.string.live_picture)
        val actionLabels = ZoomAction.entries.associateWith { stringResource(it.label) }
        Box(
            Modifier.fillMaxSize()
                .then(
                    if (frame == null) Modifier
                    else Modifier.semantics {
                        contentDescription = description
                        customActions = actions.map { a ->
                            CustomAccessibilityAction(actionLabels.getValue(a)) {
                                val (z, o) = zoomStep(a, view.zoom, view.offset, w, h, side)
                                view.zoom = z
                                view.offset = o
                                true
                            }
                        }
                    },
                )
                .pointerInput(Unit) { detectTapGestures(onDoubleTap = { view.zoom = 1f; view.offset = Offset.Zero }) }
                .pointerInput(w, h) {
                    detectTransformGestures { centroid, pan, gestureZoom, _ ->
                        val newZoom = (view.zoom * gestureZoom).coerceIn(1f, 6f)
                        // Keep the point under the fingers where it is: relative to the viewport
                        // centre, o' = (o - p) * z'/z + p, then add the pan.
                        val p = centroid - Offset(w / 2, h / 2)
                        view.offset = clamp((view.offset - p) * (newZoom / view.zoom) + p + pan, newZoom)
                        view.zoom = newZoom
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            Box(
                Modifier.size(with(LocalDensity.current) { side.toDp() })
                    .graphicsLayer {
                        // With no picture the empty circle sits unzoomed round the status text;
                        // the zoom is kept for when the picture comes back.
                        if (frame != null) {
                            scaleX = view.zoom
                            scaleY = view.zoom
                            translationX = view.offset.x
                            translationY = view.offset.y
                        }
                    }
                    .clip(CircleShape)
                    .background(Color.Black)
                    // the same hair-thin ring saved images get (#15)
                    .then(if (outline) Modifier.border(Dp.Hairline, Color(BandRenderer.CIRCLE), CircleShape) else Modifier),
            ) {
                frame?.let {
                    // Rotated clockwise by the roll angle (and trim) keeps the picture upright.
                    Image(
                        bitmap = it.asImageBitmap(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize().rotate(rotation.toFloat()),
                    )
                }
                // The proximity scale is centred on the image centre and zooms and pans with the
                // picture (so mm stay true on screen), but stays upright: see scaleLayerRotation.
                // Display only; saved files don't include it.
                if (frame != null && overlayRenderer != null) {
                    ScaleLayer(proximity.filterNot(ScaleOverlay::isClose), overlayRenderer, side.toInt(), scaleLayerRotation(rotation))
                }
            }
            // Where the circle sits at zoom 1, whatever the zoom or pan left from the last picture.
            // Display only, like the scale: captures take the frame, never this layer.
            if (frame == null && status != null && text != null) {
                StatusLayer(status, text, w.toInt(), h.toInt(), side.toInt(), Modifier.align(Alignment.TopStart))
            }
            // CLOSE stays upright at the viewport's upper left, whatever the roll or zoom.
            if (frame != null && overlayRenderer != null) {
                CloseLayer(proximity.filter(ScaleOverlay::isClose), overlayRenderer, side.toInt(), Modifier.align(Alignment.TopStart))
            }
            // The scale is an estimate: said at the upper right, upright and fixed like CLOSE (#35).
            if (text != null && ScaleDisclaimer.shown(frame != null, proximity)) {
                DisclaimerLayer(text, w.toInt(), h.toInt(), side.toInt(), Modifier.align(Alignment.TopEnd))
            }
        }
    }
}

/** The viewport's zoom and pan, kept outside it so a snapshot can crop what is visible. */
@Stable
class ZoomView {
    var zoom by mutableFloatStateOf(1f)
    var offset by mutableStateOf(Offset.Zero)
    var viewportW = 0f
    var viewportH = 0f

    /** The visible part of a [frameSize]-px frame, or null when not zoomed in. */
    fun crop(frameSize: Int): ZoomCrop.Rect? =
        ZoomCrop.visible(frameSize, viewportW, viewportH, minOf(viewportW, viewportH), zoom, offset.x, offset.y)
}

/** How far the circle may move at zoom [z] in a [w] × [h] viewport: only as far as it overhangs it. */
internal fun clampOffset(o: Offset, z: Float, w: Float, h: Float, side: Float) = Offset(
    o.x.coerceIn(-maxOf(0f, (side * z - w) / 2), maxOf(0f, (side * z - w) / 2)),
    o.y.coerceIn(-maxOf(0f, (side * z - h) / 2), maxOf(0f, (side * z - h) / 2)),
)

/** The viewport's zoom actions for TalkBack, standing in for pinch and double-tap. */
internal enum class ZoomAction(@StringRes val label: Int) {
    IN(R.string.zoom_in), OUT(R.string.zoom_out), RESET(R.string.zoom_reset)
}

/** The zoom steps the actions go through, within the gestures' 1-6x. */
internal val ZOOM_STOPS = listOf(1f, 1.5f, 2f, 3f, 4f, 6f)

/** The actions that do something at [zoom] and [offset]: in below 6x, out above 1x, reset when not at rest. */
internal fun zoomActions(zoom: Float, offset: Offset): List<ZoomAction> = buildList {
    if (zoom < ZOOM_STOPS.last()) add(ZoomAction.IN)
    if (zoom > ZOOM_STOPS.first()) add(ZoomAction.OUT)
    if (zoom > ZOOM_STOPS.first() || offset != Offset.Zero) add(ZoomAction.RESET)
}

/**
 * The zoom and offset after [action]: to the next stop up or down, about the viewport centre
 * (the offset scales with the zoom), clamped as the gestures clamp it; reset goes to 1x, centred.
 */
internal fun zoomStep(action: ZoomAction, zoom: Float, offset: Offset, w: Float, h: Float, side: Float): Pair<Float, Offset> {
    val z = when (action) {
        ZoomAction.IN -> ZOOM_STOPS.firstOrNull { it > zoom + 0.01f } ?: ZOOM_STOPS.last()
        ZoomAction.OUT -> ZOOM_STOPS.lastOrNull { it < zoom - 0.01f } ?: ZOOM_STOPS.first()
        ZoomAction.RESET -> return ZOOM_STOPS.first() to Offset.Zero
    }
    return z to clampOffset(offset * (z / zoom), z, w, h, side)
}

/** [zoom] as TalkBack says it ("2", "1.5"), or null at 1x. */
internal fun zoomLabel(zoom: Float): String? {
    val tenths = Math.round(zoom * 10)
    if (tenths <= 10) return null
    return if (tenths % 10 == 0) "${tenths / 10}" else "${tenths / 10}.${tenths % 10}"
}

/** Which scale [shapes] draw, for TalkBack: its sentence, and whether it is locked. */
internal enum class ScaleKind(@StringRes val text: Int) {
    RING(R.string.scale_ring), BOWTIE(R.string.scale_bowtie), BAR(R.string.scale_bar)
}

/** The scale in [shapes] (CLOSE left out), and whether it is locked (lock colour); null if none. */
internal fun scaleSpoken(shapes: List<OverlayShape>): Pair<ScaleKind, Boolean>? {
    val kind = when {
        shapes.any { it is OverlayShape.Arc && it.endDeg - it.startDeg >= 360.0 } -> ScaleKind.RING
        shapes.any { it is OverlayShape.Arc } -> ScaleKind.BOWTIE
        shapes.any { it is OverlayShape.Line && it.outlined } -> ScaleKind.BAR
        else -> return null
    }
    val locked = shapes.any { ((it as? OverlayShape.Arc)?.color ?: (it as? OverlayShape.Line)?.color) == ScaleOverlay.LOCK }
    return kind to locked
}

/** The scale drawn at the circle's on-screen size [side] px, so its rings stay one pixel thin. */
@Composable
private fun ScaleLayer(shapes: List<OverlayShape>, renderer: OverlayRenderer, side: Int, rotation: Float) {
    if (shapes.isEmpty() || side <= 0) return
    val image = rendered(shapes, side) {
        renderer.render(shapes, side, side, side.toDouble() / FrameGeometry.SIZE).toBitmap().asImageBitmap()
    } ?: return
    // Said when TalkBack reaches it, not announced: it changes with every lock and unlock.
    val description = scaleSpoken(shapes)?.let { (kind, locked) ->
        stringResource(R.string.scale_description, stringResource(kind.text), stringResource(if (locked) R.string.scale_locked else R.string.scale_not_locked))
    }
    Image(
        bitmap = image,
        contentDescription = description,
        contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.None,
        modifier = Modifier.fillMaxSize().rotate(rotation),
    )
}

/**
 * [status] in the empty circle, in the band's font at the same whole scale as CLOSE: laid out in
 * 1/k of the circle's [side], only the text's box drawn, off the main thread, and shown k times
 * larger without smoothing at its place in the unzoomed circle, centred in the viewport.
 * TalkBack announces each new status.
 */
@Composable
private fun StatusLayer(status: CircleStatus, text: PixelText, viewportW: Int, viewportH: Int, side: Int, modifier: Modifier) {
    val k = maxOf(1, side / FrameGeometry.SIZE)
    val small = side / k
    val title = stringResource(status.title)
    val hint = stringResource(status.hint)
    var drawn by remember { mutableStateOf<Pair<ImageBitmap, PixelText.Block>?>(null) }
    LaunchedEffect(text, title, hint, small) {
        drawn = try {
            withContext(Dispatchers.Default) {
                text.circleBlock(listOf(title, hint), small)?.let { b ->
                    text.draw(b.lines, b.width, b.height, BandRenderer.VALUE).toBitmap().asImageBitmap() to b
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("BebirdSpike", "circle status not drawn", e)
            null
        }
    }
    val (image, block) = drawn ?: return
    val left = (viewportW - small * k) / 2 + block.x * k
    val top = (viewportH - small * k) / 2 + block.y * k
    val description = "$title. $hint"
    with(LocalDensity.current) {
        Image(
            bitmap = image,
            contentDescription = description,
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.None,
            modifier = modifier.offset { IntOffset(left, top) }.size((image.width * k).toDp(), (image.height * k).toDp())
                .semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * The scale's disclaimer at the viewport's upper right, clear of CLOSE: drawn at scale 1 off the
 * main thread and shown at [ScaleDisclaimer.place]'s whole scale without smoothing. TalkBack
 * reads the full sentence.
 */
@Composable
private fun DisclaimerLayer(text: PixelText, viewportW: Int, viewportH: Int, side: Int, modifier: Modifier) {
    val k = maxOf(1, side / FrameGeometry.SIZE)
    val paragraphs = listOf(
        stringResource(R.string.scale_note_title), stringResource(R.string.scale_note_focus), stringResource(R.string.scale_note_sensor),
    )
    val description = stringResource(R.string.scale_note_description)
    var drawn by remember { mutableStateOf<Pair<ImageBitmap, Int>?>(null) }
    LaunchedEffect(text, paragraphs, viewportW, viewportH, k) {
        drawn = try {
            withContext(Dispatchers.Default) {
                ScaleDisclaimer.place(text, paragraphs, viewportW, viewportH, k, CLOSE_W * k)?.let { p ->
                    text.draw(p.lines, p.width, p.height, BandRenderer.TAG, outline = true).toBitmap().asImageBitmap() to p.scale
                }
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            Log.e("BebirdSpike", "scale note not drawn", e)
            null
        }
    }
    val (image, s) = drawn ?: return
    with(LocalDensity.current) {
        val inset = (ScaleDisclaimer.INSET * s).toDp()
        Image(
            bitmap = image,
            contentDescription = description,
            contentScale = ContentScale.FillBounds,
            filterQuality = FilterQuality.None,
            modifier = modifier.padding(top = inset, end = inset).size((image.width * s).toDp(), (image.height * s).toDp()),
        )
    }
}

/** The CLOSE indicator's raw-frame window: the triangle and its label, upper left. */
private const val CLOSE_X = 10.0
private const val CLOSE_Y = 8.0
private const val CLOSE_W = 112
private const val CLOSE_H = 58

/** CLOSE at a whole scale for this screen, upright and outside the zoom. */
@Composable
private fun CloseLayer(shapes: List<OverlayShape>, renderer: OverlayRenderer, side: Int, modifier: Modifier) {
    if (shapes.isEmpty()) return
    val k = maxOf(1, side / FrameGeometry.SIZE)
    val image = rendered(shapes, k) {
        renderer.render(shapes, CLOSE_W * k, CLOSE_H * k, k.toDouble(), CLOSE_X, CLOSE_Y).toBitmap().asImageBitmap()
    } ?: return
    val description = stringResource(R.string.proximity_close_description)
    with(LocalDensity.current) {
        Image(
            bitmap = image,
            contentDescription = description,
            contentScale = ContentScale.None,
            filterQuality = FilterQuality.None,
            // TalkBack mentions CLOSE when it appears, without interrupting
            modifier = modifier.size(image.width.toDp(), image.height.toDp()).semantics { liveRegion = LiveRegionMode.Polite },
        )
    }
}

/**
 * [draw]'s image for ([shapes], [size]), rasterised off the main thread; the previous image
 * stays up until the new one is ready. A few recent ones are kept, so flipping between locked
 * and unlocked (or CLOSE on and off) reuses them instead of drawing again.
 */
@Composable
private fun rendered(shapes: List<OverlayShape>, size: Int, draw: () -> ImageBitmap): ImageBitmap? {
    val cache = remember(size) { BoundedCache<List<OverlayShape>, ImageBitmap>(6) }
    var image by remember(size) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(shapes, size) {
        image = cache[shapes] ?: withContext(Dispatchers.Default) { draw() }.also { cache[shapes] = it }
    }
    return image
}

/**
 * The proximity scale's rotation on screen for an image turned by [imageRotation] degrees:
 * none. The rings are round and centred on the image centre, so turning them adds nothing but
 * jerkiness; labels, the bar and the bowtie stay horizontal. (Zoom and pan still apply.)
 */
fun scaleLayerRotation(@Suppress("UNUSED_PARAMETER") imageRotation: Int): Float = 0f
