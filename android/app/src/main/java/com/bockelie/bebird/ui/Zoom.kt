// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
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
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import com.bockelie.bebird.R
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.toBitmap
import com.bockelie.bebird.capture.ZoomCrop
import com.bockelie.bebird.focus.FrameGeometry
import com.bockelie.bebird.focus.OverlayRenderer
import com.bockelie.bebird.focus.OverlayShape
import com.bockelie.bebird.focus.ScaleOverlay
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * The image circle inside a rectangular viewport. Pinch zooms (1-6x) and drag pans, clipped
 * to the viewport; double-tap resets. Display only: nothing here changes what is received.
 */
@Composable
fun ZoomableCircle(
    frame: Bitmap?, rotation: Int, outline: Boolean, view: ZoomView, modifier: Modifier,
    proximity: List<OverlayShape> = emptyList(), overlayRenderer: OverlayRenderer? = null,
) {
    // Outside the image circle the viewport is the band's black, not the theme's surface, so
    // image and band read as one panel (as in saved stills with the overlay).
    BoxWithConstraints(modifier.clipToBounds().background(Color(BandRenderer.BACKGROUND)), contentAlignment = Alignment.Center) {
        val w = constraints.maxWidth.toFloat()
        val h = constraints.maxHeight.toFloat()
        val side = minOf(w, h)
        SideEffect { view.viewportW = w; view.viewportH = h }  // for the zoomed snapshot's crop
        // How far the circle may move: only as far as it overhangs the viewport.
        fun clamp(o: Offset, z: Float) = Offset(
            o.x.coerceIn(-maxOf(0f, (side * z - w) / 2), maxOf(0f, (side * z - w) / 2)),
            o.y.coerceIn(-maxOf(0f, (side * z - h) / 2), maxOf(0f, (side * z - h) / 2)),
        )
        // A resize (rotation, multi-window) keeps the view inside the new bounds.
        LaunchedEffect(w, h) { view.offset = clamp(view.offset, view.zoom) }
        Box(
            Modifier.fillMaxSize()
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
                        scaleX = view.zoom
                        scaleY = view.zoom
                        translationX = view.offset.x
                        translationY = view.offset.y
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
                    ScaleLayer(proximity.filterNot(::isClose), overlayRenderer, side.toInt(), scaleLayerRotation(rotation))
                }
            }
            // CLOSE stays upright at the viewport's upper left, whatever the roll or zoom.
            if (frame != null && overlayRenderer != null) {
                CloseLayer(proximity.filter(::isClose), overlayRenderer, side.toInt(), Modifier.align(Alignment.TopStart))
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

private fun isClose(s: OverlayShape) =
    s is OverlayShape.Warning || (s is OverlayShape.Label && s.text == ScaleOverlay.CLOSE_LABEL)

/** The scale drawn at the circle's on-screen size [side] px, so its rings stay one pixel thin. */
@Composable
private fun ScaleLayer(shapes: List<OverlayShape>, renderer: OverlayRenderer, side: Int, rotation: Float) {
    if (shapes.isEmpty() || side <= 0) return
    val image = rendered(shapes, side) {
        renderer.render(shapes, side, side, side.toDouble() / FrameGeometry.SIZE).toBitmap().asImageBitmap()
    } ?: return
    Image(
        bitmap = image,
        contentDescription = null,
        contentScale = ContentScale.FillBounds,
        filterQuality = FilterQuality.None,
        modifier = Modifier.fillMaxSize().rotate(rotation),
    )
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
