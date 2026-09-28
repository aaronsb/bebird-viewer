// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.capture

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.bockelie.bebird.band.BandRenderer
import com.bockelie.bebird.band.PixelImage

/**
 * An H.264 MP4 of composited frames, via MediaCodec and MediaMuxer, into a MediaStore entry.
 * Not thread-safe: use it from one thread (the capture worker). The picture is [width] ×
 * [height] padded to multiples of 16 with the band's black; timestamps come from when frames
 * arrived (the scope's rate varies around 10 fps). Make one with [create].
 */
class VideoRecorder private constructor(
    private val files: MediaStoreFiles,
    val uri: Uri,
    private val pfd: ParcelFileDescriptor,
    private val muxer: MediaMuxer,
    private val codec: MediaCodec,
    val width: Int,
    val height: Int,
) {
    private val encW = Yuv.padTo16(width)
    private val encH = Yuv.padTo16(height)
    private val info = MediaCodec.BufferInfo()
    private val clock = PtsClock()
    private var track = -1
    // Reused for every frame: the size is fixed for the file.
    private val padded = IntArray(encW * encH) { BandRenderer.BACKGROUND }
    private val yuv = Yuv.planes(encW, encH)
    var frames = 0; private set

    /**
     * Encode [frame] ([width] × [height], already rotated and composited) that arrived at
     * [nanos]. Throws if the encoder fails; the caller should then [finish].
     */
    fun add(frame: PixelImage, nanos: Long) {
        require(frame.width == width && frame.height == height) { "frame ${frame.width}x${frame.height}, file ${width}x$height" }
        val index = codec.dequeueInputBuffer(10_000)
        if (index < 0) return drain(false)  // encoder busy: drop this frame
        val pts = clock.ptsUs(nanos)
        val image = codec.getInputImage(index)
        if (image == null) {
            codec.queueInputBuffer(index, 0, 0, pts, 0)  // give the buffer back rather than lose it
            return drain(false)
        }
        for (y in 0 until height) frame.pixels.copyInto(padded, y * encW, y * width, y * width + width)
        Yuv.into(padded, yuv)
        copyPlane(image.planes[0], yuv.y, encW, encH)
        copyPlane(image.planes[1], yuv.u, encW / 2, encH / 2)
        copyPlane(image.planes[2], yuv.v, encW / 2, encH / 2)
        codec.queueInputBuffer(index, 0, encW * encH * 3 / 2, pts, 0)
        frames++
        drain(false)
    }

    /**
     * Finish the file at [nanos] (same clock as [add]). Returns its uri if a playable file was
     * written (the muxer stopped cleanly); otherwise removes the entry and returns null.
     */
    fun finish(nanos: Long): Uri? {
        try {
            val index = codec.dequeueInputBuffer(100_000)
            if (index >= 0) codec.queueInputBuffer(index, 0, 0, clock.ptsUs(nanos), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(true)
        } catch (e: Exception) {
            Log.w(TAG, "finishing the recording", e)
        }
        runCatching { codec.stop() }
        runCatching { codec.release() }
        // Without a clean muxer stop the MP4 has no index (moov) and won't play.
        val stopped = track >= 0 && runCatching { muxer.stop() }.onFailure { Log.w(TAG, "muxer stop failed", it) }.isSuccess
        runCatching { muxer.release() }
        runCatching { pfd.close() }
        if (stopped) files.publish(uri) else files.discard(uri)
        Log.i(TAG, if (stopped) "recording saved: $frames frames" else "recording not playable, discarded ($frames frames)")
        return if (stopped) uri else null
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

    /** Copy a plane of [w] × [h] samples into [plane], honouring its row and pixel strides. */
    private fun copyPlane(plane: Image.Plane, data: ByteArray, w: Int, h: Int) {
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

        /**
         * A new recording named [name], [width] × [height]. Everything it opens is closed again
         * (and the pending MediaStore entry removed) if any step fails.
         */
        fun create(files: MediaStoreFiles, name: String, width: Int, height: Int): VideoRecorder {
            val encW = Yuv.padTo16(width)
            val encH = Yuv.padTo16(height)
            val uri = files.create(MediaStoreFiles.Kind.VIDEO, name)
            var pfd: ParcelFileDescriptor? = null
            var muxer: MediaMuxer? = null
            var codec: MediaCodec? = null
            try {
                pfd = files.openForWriting(uri)
                muxer = MediaMuxer(pfd.fileDescriptor, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                codec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, encW, encH).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible)
                    setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                    setInteger(MediaFormat.KEY_FRAME_RATE, 10)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1)
                }
                codec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                codec.start()
                Log.i(TAG, "recording ${encW}x$encH to $uri")
                return VideoRecorder(files, uri, pfd, muxer, codec, width, height)
            } catch (e: Exception) {
                codec?.let { runCatching { it.release() } }
                muxer?.let { runCatching { it.release() } }
                pfd?.let { runCatching { it.close() } }
                files.discard(uri)
                throw e
            }
        }
    }
}
