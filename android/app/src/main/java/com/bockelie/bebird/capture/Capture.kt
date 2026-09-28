// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.bockelie.bebird.band.BandData
import com.bockelie.bebird.band.BandRenderer
import java.io.ByteArrayOutputStream
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Snapshots and recording, off the main thread on one worker. Captures only read frames the
 * session has already received: nothing here talks to the scope.
 */
class Capture(resolver: ContentResolver) {
    /** Everything a capture needs about one frame, as it was on screen. */
    class Shot(
        val frame: Bitmap,
        val rotation: Int,
        val overlay: Boolean,
        val renderer: BandRenderer?,
        val band: BandData,
        val meta: SnapshotMeta,
        val zoomRect: ZoomCrop.Rect?,
    )

    sealed interface Result {
        data class Saved(val uri: Uri, val name: String, val video: Boolean) : Result
        data class Failed(val what: String, val reason: String) : Result
    }

    private val files = MediaStoreFiles(resolver)
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "bebird-capture").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)  // a video frame is being encoded
    @Volatile private var recorder: VideoRecorder? = null  // touched on the worker only, read anywhere

    val recording: Boolean get() = recorder != null

    /** Save [shot] as a JPEG (and a _zoomed one when zoomed in); [done] gets each result. */
    fun snapshot(shot: Shot, done: (Result) -> Unit) = worker.execute {
        val rotated = Frames.rotated(shot.frame, shot.rotation)
        val time = shot.meta.taken.toLocalDateTime()
        save(CaptureNames.still(time), Frames.composed(rotated, shot.renderer, shot.band, shot.overlay), shot.meta, done)
        shot.zoomRect?.let { rect ->
            val zoomed = Frames.zoomed(rotated, rect, shot.renderer, shot.band, shot.overlay)
            save(CaptureNames.still(time, zoomed = true), zoomed, shot.meta.copy(zoomed = true), done)
        }
    }

    private fun save(name: String, image: Bitmap, meta: SnapshotMeta, done: (Result) -> Unit) {
        try {
            val jpeg = ByteArrayOutputStream().also { image.compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
            val uri = files.write(MediaStoreFiles.Kind.STILL, name, ExifWriter.insert(jpeg, meta))
            Log.i(TAG, "saved $name (${image.width}x${image.height})")
            done(Result.Saved(uri, name, video = false))
        } catch (e: Exception) {
            Log.e(TAG, "saving $name failed", e)
            done(Result.Failed(name, e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * Start recording; frames come from [addVideoFrame]. The first frame fixes the size (and
     * whether the overlay is burned in) for the whole file.
     */
    fun startRecording(name: String, first: Shot, done: (Result) -> Unit) = worker.execute {
        if (recorder != null) return@execute
        try {
            val image = videoImage(first)
            recorder = VideoRecorder(files, name, image.width, image.height).also {
                it.add(image, SystemClock.elapsedRealtimeNanos())
            }
        } catch (e: Exception) {
            Log.e(TAG, "recording failed to start", e)
            done(Result.Failed(name, e.message ?: e.javaClass.simpleName))
        }
    }

    /** Offer a frame to the recording; dropped if the previous one is still being encoded. */
    fun addVideoFrame(shot: Shot, nanos: Long) {
        if (recorder == null || !busy.compareAndSet(false, true)) return
        worker.execute {
            try {
                val r = recorder ?: return@execute
                val image = videoImage(shot)
                if (image.width == r.width && image.height == r.height) r.add(image, nanos)
            } catch (e: Exception) {
                Log.w(TAG, "video frame dropped", e)
            } finally {
                busy.set(false)
            }
        }
    }

    fun stopRecording(name: String, done: (Result) -> Unit) = worker.execute {
        val r = recorder ?: return@execute
        recorder = null
        val uri = r.finish(SystemClock.elapsedRealtimeNanos())
        done(if (uri != null) Result.Saved(uri, name, video = true) else Result.Failed(name, "no frames recorded"))
    }

    private fun videoImage(shot: Shot) = Frames.composed(Frames.rotated(shot.frame, shot.rotation), shot.renderer, shot.band, shot.overlay)

    private companion object {
        const val TAG = "BebirdSpike"
    }
}
