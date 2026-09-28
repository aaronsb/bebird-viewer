// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import android.graphics.Bitmap
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.toPixelImage

/**
 * An H.264 MP4 of composited frames, via MediaCodec and MediaMuxer, into a MediaStore entry.
 * Not thread-safe: use it from one thread (the capture worker). The picture is [width] ×
 * [height] padded to multiples of 16 with the band's black; timestamps come from when frames
 * arrived (the scope's rate varies around 10 fps).
 */
class VideoRecorder(private val files: MediaStoreFiles, name: String, val width: Int, val height: Int) {
    private val encW = Yuv.padTo16(width)
    private val encH = Yuv.padTo16(height)
    val uri: Uri = files.create(MediaStoreFiles.Kind.VIDEO, name)
    private val pfd: ParcelFileDescriptor = files.openForWriting(uri)
    private val muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
    private val codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
    private val info = MediaCodec.BufferInfo()
    private val clock = PtsClock()
    private var track = -1
    var frames = 0; private set

    init {
        val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encW, encH).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
            setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, 10)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
        }
        try {
            codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
        } catch (e: Exception) {
            release(keep = false)
            throw e
        }
        Log.i(TAG, "recording ${encW}x$encH to $uri")
    }

    /** Encode [frame] (already rotated and composited, [width] × [height]) that arrived at [nanos]. */
    fun add(frame: Bitmap, nanos: Long) {
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return drain(false)  // encoder busy: drop this frame
        val px = frame.toPixelImage()
        val padded = if (px.width == encW && px.height == encH) px.pixels
            else IntArray(encW * encH) { i -> val x = i % encW; val y = i / encW
                if (x < px.width && y < px.height) px.pixels[y * px.width + x] else BandRenderer.BACKGROUND }
        val yuv = Yuv.from(padded, encW, encH)
        val image = codec.getInputImage(index) ?: return
        copyPlane(image.planes[0], yuv.y, encW, encH)
        copyPlane(image.planes[1], yuv.u, encW / 2, encH / 2)
        copyPlane(image.planes[2], yuv.v, encW / 2, encH / 2)
        codec.queueInputBuffer(index, 0, encW * encH * 3 / 2, clock.ptsUs(nanos), 0)
        frames++
        drain(false)
    }

    /** Finish the file at [nanos] (same clock as [add]); returns its uri, or null (removed) if nothing was recorded. */
    fun finish(nanos: Long): Uri? {
        try {
            val index = codec.dequeueInputBuffer(100_000)
            if (index >= 0) codec.queueInputBuffer(index, 0, 0, clock.ptsUs(nanos), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } catch (e: Exception) {
            Log.w(TAG, "finishing the recording", e)
        }
        val ok = track >= 0
        release(keep = ok)
        Log.i(TAG, if (ok) "recording saved: $frames frames" else "recording empty, discarded")
        return if (ok) uri else null
    }

    private fun drain(untilEnd: Boolean) {
        val deadline = System.nanoTime() + 2_000_000_000L
        while (true) {
            val out = codec.dequeueOutputBuffer(info, if (untilEnd) 10_000 else 0)
            when {
                out == MediaCodec.INFO_TRY_AGAIN_LATER -> if (!untilEnd || System.nanoTime() > deadline) return
                out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    track = muxer.addTrack(codec.outputFormat)
                    muxer.start()
                }
                out >= 0 -> {
                    val buf = codec.getOutputBuffer(out)
                    if (buf != null && info.size > 0 && track >= 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0) {
                        muxer.writeSampleData(track, buf, info)
                    }
                    codec.releaseOutputBuffer(out, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    private fun release(keep: Boolean) {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (track >= 0) runCatching { muxer.stop() }
        runCatching { muxer.release() }
        runCatching { pfd.close() }
        if (keep) files.publish(uri) else files.discard(uri)
    }

    /** Copy a plane of [w] × [h] samples into [plane], honouring its row and pixel strides. */
    private fun copyPlane(plane: android.media.Image.Plane, data: ByteArray, w: Int, h: Int) {
        val buf = plane.buffer
        val row = plane.rowStride
        val step = plane.pixelStride
        for (y in 0 until h) {
            if (step == 1) {
                buf.position(y * row)
                buf.put(data, y * w, w)
            } else {
                for (x in 0 until w) buf.put(y * row + x * step, data[y * w + x])
            }
        }
    }

    companion object {
        private const val TAG = "BebirdSpike"
        /** Plenty for 480 px at about 10 fps. */
        const val BIT_RATE = 2_000_000
    }
}
