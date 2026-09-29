// SPDX-License-Identifier: GPL-3.0-or-later
package com.bockelie.bebird.focus

/** What the per-frame hook needs from an estimator; [FocusEstimator] is the real one. */
fun interface FrameEstimator {
    fun update(luma: ByteArray, t: Double, roll: Int): FocusResult
}

/** The per-frame buffers: [argb] for a decoded frame's pixels, [luma] for the estimator. */
class LumaBuffers {
    val argb = IntArray(FrameGeometry.SIZE * FrameGeometry.SIZE)
    val luma = ByteArray(FrameGeometry.SIZE * FrameGeometry.SIZE)
}

/**
 * The master switch for proximity estimation, held by whoever sees the frames. While disabled
 * there is no estimator and no buffers (about 1.1 MB): [onFrame] returns null without calling
 * its fill callback, so the frame path does no luma conversion, no mask learning, no metrics
 * and no allocation. Enabling creates a fresh estimator (warm-up, empty tip mask, disarmed, no
 * peak), so there is no reset() to forget a field; disabling releases it and its buffers.
 *
 * The persisted settings (the master switch itself, the scale style, the CLOSE indicator) are
 * the app's, not the gate's: see docs/focus-detection.md.
 *
 * [setEnabled] may be called from any thread; [onFrame] from the one frame thread.
 */
class ProximityGate(private val create: () -> FrameEstimator = { FocusEstimator() }) {
    @PublishedApi internal class Active(val estimator: FrameEstimator, val buffers: LumaBuffers)

    @Volatile @PublishedApi internal var active: Active? = null

    val enabled: Boolean get() = active != null

    fun setEnabled(on: Boolean) {
        synchronized(this) {
            if (!on) active = null
            else if (active == null) active = Active(create(), LumaBuffers())
        }
    }

    /**
     * One frame: when enabled, [fill] writes the raw, unrotated frame's luma into
     * [LumaBuffers.luma] (for a Bitmap: `bitmap.lumaInto(it)`) and the estimator runs; when
     * disabled, nothing runs and the result is null (no scale, no CLOSE).
     */
    inline fun onFrame(t: Double, roll: Int, fill: (LumaBuffers) -> Unit): FocusResult? {
        val a = active ?: return null
        fill(a.buffers)
        return a.estimator.update(a.buffers.luma, t, roll)
    }
}
