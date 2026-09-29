// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import android.content.ContentResolver
import android.graphics.Bitmap
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import com.bockelie.bebird.annotate.AnnotationRenderer
import com.bockelie.bebird.annotate.Mark
import com.bockelie.bebird.annotate.annotatedStills
import com.bockelie.bebird.band.BandData
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.PixelImage
import com.bockelie.bebird.band.toBitmap
import com.bockelie.bebird.band.toPixelImage
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
    private var recorder: VideoRecorder? = null  // worker only
    private var recordingName = ""               // worker only
    private var onRecordingEnd: ((Result) -> Unit)? = null  // worker only

    init {
        worker.execute {
            val n = files.sweepPending()
            if (n > 0) Log.i(TAG, "removed $n unfinished capture(s) left by an earlier run")
        }
    }

    /** Whether any capture has been saved (so Pictures/Bebird exists). Queries MediaStore: not on the main thread. */
    fun hasCaptures(): Boolean = files.hasCaptures()

    /** Whether the day folder [dir] has captures (so it exists). Not on the main thread. */
    fun hasCapturesIn(dir: String): Boolean = files.hasCapturesIn(dir)

    /** Save [shot] as a JPEG (and a _zoomed one when zoomed in); [done] gets each result. */
    fun snapshot(shot: Shot, done: (Result) -> Unit) = worker.execute {
        val rotated = Frames.rotated(shot.frame, shot.rotation)
        val time = shot.meta.taken.toLocalDateTime()
        val dir = CaptureNames.folder(time.toLocalDate())
        save(CaptureNames.still(time), dir, Frames.composed(rotated.toPixelImage(), shot.renderer, shot.band, shot.overlay), shot.meta, done)
        shot.zoomRect?.let { rect ->
            val zoomed = Frames.zoomed(rotated, rect, shot.renderer, shot.band, shot.overlay)
            save(CaptureNames.still(time, zoomed = true), dir, zoomed, shot.meta.copy(zoomed = true), done)
        }
    }

    /**
     * Save the paused [shot] (its frame already upright, never a zoomed crop) as a snapshot
     * saves it, then the same picture with [marks] over the frame as <name>_annotated.jpg,
     * marked annotated in its EXIF. [done] gets each result.
     */
    fun snapshotAnnotated(shot: Shot, marks: List<Mark>, annotations: AnnotationRenderer, done: (Result) -> Unit) = worker.execute {
        val time = shot.meta.taken.toLocalDateTime()
        val name = CaptureNames.still(time)
        val (plain, annotated) = try {
            val upright = Frames.rotated(shot.frame, shot.rotation).toPixelImage()
            annotatedStills(upright, shot.renderer, shot.band, shot.overlay, marks, annotations)
        } catch (e: Throwable) {
            Log.e(TAG, "drawing the annotations failed", e)
            return@execute done(Result.Failed(name, e.message ?: e.javaClass.simpleName))
        }
        val dir = CaptureNames.folder(time.toLocalDate())
        save(name, dir, plain, shot.meta, done)
        save(CaptureNames.annotated(time), dir, annotated, shot.meta.copy(annotated = true), done)
    }

    private fun save(name: String, dir: String, image: PixelImage, meta: SnapshotMeta, done: (Result) -> Unit) {
        try {
            val jpeg = ByteArrayOutputStream().also { image.toBitmap().compress(Bitmap.CompressFormat.JPEG, 95, it) }.toByteArray()
            val uri = files.write(MediaStoreFiles.Kind.STILL, name, dir, ExifWriter.insert(jpeg, meta))
            Log.i(TAG, "saved $name (${image.width}x${image.height})")
            done(Result.Saved(uri, name, video = false))
        } catch (e: Throwable) {
            Log.e(TAG, "saving $name failed", e)
            done(Result.Failed(name, e.message ?: e.javaClass.simpleName))
        }
    }

    /**
     * Start recording [name]; frames come from [addVideoFrame]. The first frame fixes the size
     * (and whether the overlay is burned in) for the whole file. [ended] is called once, on the
     * worker, when the recording ends for any reason: stopped, failed to start, or an encoder
     * error (which stops it at once).
     */
    fun startRecording(name: String, first: Shot, ended: (Result) -> Unit) = worker.execute {
        if (recorder != null) return@execute ended(Result.Failed(name, "already recording"))
        val image = try {
            val dir = CaptureNames.folder(first.meta.taken.toLocalDate())
            videoImage(first).also { recorder = VideoRecorder.create(files, name, dir, it.width, it.height) }
        } catch (e: Exception) {
            Log.e(TAG, "recording failed to start", e)
            return@execute ended(Result.Failed(name, e.message ?: e.javaClass.simpleName))
        }
        recordingName = name
        onRecordingEnd = ended
        try {
            recorder!!.add(image, SystemClock.elapsedRealtimeNanos())
        } catch (e: Exception) {
            Log.e(TAG, "encoder error on the first frame", e)
            end(e.message ?: e.javaClass.simpleName)
        }
    }

    /** Offer a frame to the recording; dropped if the previous one is still being encoded. */
    fun addVideoFrame(shot: Shot, nanos: Long) {
        if (!busy.compareAndSet(false, true)) return
        worker.execute {
            try {
                val r = recorder ?: return@execute
                // The size is fixed for the file; a frame that differs is fitted, not dropped.
                r.add(PixelOps.fit(videoImage(shot), r.width, r.height), nanos)
            } catch (e: Exception) {
                Log.e(TAG, "encoder error: stopping the recording", e)
                end(e.message ?: e.javaClass.simpleName)
            } finally {
                busy.set(false)
            }
        }
    }

    /** Stop recording; the start's `ended` callback gets the result ("no recording" if none). */
    fun stopRecording(ifNone: (Result) -> Unit) = worker.execute {
        if (recorder == null) return@execute ifNone(Result.Failed("recording", "no recording"))
        end(null)
    }

    /** Finish the recording (after an [error], if any) and report how it ended. Worker only. */
    private fun end(error: String?) {
        val r = recorder ?: return
        recorder = null
        val uri = r.finish(SystemClock.elapsedRealtimeNanos())
        val ended = onRecordingEnd ?: return
        onRecordingEnd = null
        ended(when {
            error == null && uri != null -> Result.Saved(uri, recordingName, video = true)
            error == null -> Result.Failed(recordingName, "the video couldn't be finished")
            uri != null -> Result.Failed(recordingName, "stopped by an encoder error ($error); what was recorded is saved")
            else -> Result.Failed(recordingName, error)
        })
    }

    /** Finish any recording, then stop the worker. */
    fun close() {
        worker.execute { end(null) }
        worker.shutdown()
    }

    private fun videoImage(shot: Shot) =
        Frames.composed(Frames.rotated(shot.frame, shot.rotation).toPixelImage(), shot.renderer, shot.band, shot.overlay)

    private companion object {
        const val TAG = "BebirdSpike"
    }
}
